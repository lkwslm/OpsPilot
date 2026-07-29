package io.github.opspilot.runtime.agentscope;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import io.github.opspilot.core.port.agent.AgentExecutionService;
import io.github.opspilot.core.port.agent.ChatPort;
import io.github.opspilot.core.port.agent.RuntimeAuditSink;
import io.github.opspilot.core.port.agent.ToolPort;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderResult;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

/** AgentScope adapter for the project's single agent execution port. */
public final class AgentScopeExecutionService implements AgentExecutionService, AutoCloseable {

    private static final String CHECKPOINT_KEY = "opspilot.execution.checkpoint";

    private final ChatPort chatPort;
    private final Map<String, ToolPort> toolPorts;
    private final AgentStateStore stateStore;
    private final RuntimeAuditSink auditSink;
    private final Clock clock;
    private final ExecutorService executor;

    public AgentScopeExecutionService(
            ChatPort chatPort,
            List<ToolPort> toolPorts,
            AgentStateStore stateStore,
            RuntimeAuditSink auditSink) {
        this(chatPort, toolPorts, stateStore, auditSink, Clock.systemUTC(),
                Executors.newVirtualThreadPerTaskExecutor());
    }

    AgentScopeExecutionService(
            ChatPort chatPort,
            List<ToolPort> toolPorts,
            AgentStateStore stateStore,
            RuntimeAuditSink auditSink,
            Clock clock,
            ExecutorService executor) {
        this.chatPort = Objects.requireNonNull(chatPort, "chatPort");
        this.toolPorts = indexTools(toolPorts);
        this.stateStore = Objects.requireNonNull(stateStore, "stateStore");
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    @Override
    public ExecutionResult execute(ExecutionRequest request) {
        Objects.requireNonNull(request, "request");
        CollectingAuditSink collectingSink = new CollectingAuditSink(auditSink);
        AgentScopeAuditCollector audit = new AgentScopeAuditCollector(collectingSink);
        AgentScopeExecutionGuard guard = new AgentScopeExecutionGuard(
                toLimits(request.limits()),
                request.cancellationToken()::cancellationRequested,
                collectingSink,
                clock);
        Optional<ExecutionJournalState> recovered;
        try {
            recovered = stateStore.get(
                    request.session().userId(), request.session().sessionId(),
                    CHECKPOINT_KEY, ExecutionJournalState.class);
        } catch (RuntimeException persistenceFailure) {
            return failed(request, guard, collectingSink, "STATE_PERSISTENCE_FAILED", null);
        }
        if (request.session().continuation() && recovered.isEmpty()) {
            return failed(request, guard, collectingSink, "CHECKPOINT_NOT_FOUND", null);
        }

        audit.event("EXECUTION_STARTED", 0, null, null, null, null, false, Map.of(
                "executionId", request.executionId(),
                "serverAgentId", request.serverAgentId(),
                "sessionId", request.session().sessionId(),
                "attempt", request.session().attempt(),
                "continuation", request.session().continuation(),
                "recovered", recovered.isPresent()));
        if (recovered.isPresent()) {
            audit.event("CHECKPOINT_RECOVERED", 0, null, null, null,
                    recovered.get().checkpointId(), false, Map.of(
                            "priorOutcome", recovered.get().outcome(),
                            "priorCompletedAt", recovered.get().completedAt().toString()));
        }

        List<ToolPort> selectedTools;
        try {
            selectedTools = request.toolIds().stream().map(this::requiredTool).toList();
        } catch (IllegalArgumentException missingTool) {
            return failed(request, guard, collectingSink, "TOOL_NOT_REGISTERED", null);
        }
        ChatPort guardedChat = new GuardedChatPort(chatPort, guard, audit, request.limits().deadline());
        List<ToolPort> guardedTools = selectedTools.stream()
                .map(tool -> new GuardedToolPort(tool, guard, audit))
                .map(ToolPort.class::cast)
                .toList();

        AgentScopeRuntimeAdapter.PreparedAgent prepared = new AgentScopeRuntimeAdapter().prepare(
                request.agentName(), request.systemPrompt(), request.modelProfileRef(),
                request.limits().maxRounds(), guardedChat, guardedTools);
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId(request.session().userId())
                .sessionId(request.session().sessionId())
                .putAll(new LinkedHashMap<>(request.context()))
                .build();
        ReActAgent agent = prepared.builder()
                .middleware(new AgentScopeGuardMiddleware(guard, audit))
                .defaultSessionId(request.session().sessionId())
                .build();
        Future<Msg> future = executor.submit(() -> agent.call(request.input(), runtimeContext).block());
        Msg response;
        try {
            long remainingNanos = Duration.between(clock.instant(), request.limits().deadline()).toNanos();
            if (remainingNanos <= 0) {
                throw new TimeoutException("parent deadline elapsed");
            }
            response = future.get(remainingNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            agent.interrupt(runtimeContext);
            future.cancel(true);
            guard.deadlineExceeded();
            agent.close();
            return terminated(request, guard, collectingSink);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            agent.interrupt(runtimeContext);
            future.cancel(true);
            guard.externalCancelled();
            agent.close();
            return terminated(request, guard, collectingSink);
        } catch (ExecutionException executionFailure) {
            agent.close();
            AgentExecutionStoppedException stopped = findStopped(executionFailure);
            if (stopped != null || !guard.status().permitted()) {
                return terminated(request, guard, collectingSink);
            }
            return failed(request, guard, collectingSink, "AGENT_EXECUTION_FAILED", null);
        }
        agent.close();
        if (!guard.status().permitted()) {
            return terminated(request, guard, collectingSink);
        }
        if (response == null) {
            return failed(request, guard, collectingSink, "AGENT_RESULT_MISSING", null);
        }

        AgentDecision decision = new AgentDecision(response.getTextContent(), List.of(), List.of());
        String checkpointId = checkpointId(request, guard.usage(), decision);
        audit.event("CHECKPOINT", guard.usage().rounds(), null,
                guard.usage().inputTokens(), guard.usage().outputTokens(), checkpointId, false,
                Map.of("sessionId", request.session().sessionId()));
        ExecutionJournalState journal = journal(
                request, guard, collectingSink.events(), checkpointId, decision, "COMPLETED", null);
        AgentScopeCheckpointWriter checkpointWriter = new AgentScopeCheckpointWriter(stateStore, guard);
        AgentScopeExecutionGuard.StopDecision saved = checkpointWriter.save(
                request.session().userId(), request.session().sessionId(), CHECKPOINT_KEY, journal);
        if (!saved.permitted()) {
            return failed(request, guard, collectingSink, saved.reasonCode(), null);
        }
        audit.event("EXECUTION_COMPLETED", guard.usage().rounds(), null,
                guard.usage().inputTokens(), guard.usage().outputTokens(), checkpointId, false,
                Map.of("outcome", "COMPLETED"));
        return new ExecutionResult(
                ExecutionOutcome.COMPLETED,
                decision,
                usage(guard),
                new ExecutionCheckpoint(checkpointId, request.session().sessionId(), clock.instant()),
                collectingSink.events(),
                null,
                clock.instant());
    }

    @Override
    public void close() {
        executor.close();
    }

    private ExecutionResult terminated(
            ExecutionRequest request,
            AgentScopeExecutionGuard guard,
            CollectingAuditSink sink) {
        String reason = guard.status().reasonCode();
        AgentDecision emptyDecision = new AgentDecision("", List.of(), List.of());
        String checkpointId = checkpointId(request, guard.usage(), emptyDecision);
        try {
            stateStore.save(
                    request.session().userId(), request.session().sessionId(), CHECKPOINT_KEY,
                    journal(request, guard, sink.events(), checkpointId, null,
                            "TERMINATED", reason));
        } catch (RuntimeException persistenceFailure) {
            return failed(request, guard, sink, "STATE_PERSISTENCE_FAILED", null);
        }
        return new ExecutionResult(
                ExecutionOutcome.TERMINATED, null, usage(guard),
                new ExecutionCheckpoint(checkpointId, request.session().sessionId(), clock.instant()),
                sink.events(), reason == null ? "AGENT_EXECUTION_TERMINATED" : reason, clock.instant());
    }

    private ExecutionResult failed(
            ExecutionRequest request,
            AgentScopeExecutionGuard guard,
            CollectingAuditSink sink,
            String reason,
            ExecutionCheckpoint checkpoint) {
        return new ExecutionResult(
                ExecutionOutcome.FAILED, null, usage(guard), checkpoint,
                sink.events(), reason, clock.instant());
    }

    private ExecutionJournalState journal(
            ExecutionRequest request,
            AgentScopeExecutionGuard guard,
            List<ExecutionEvent> events,
            String checkpointId,
            AgentDecision decision,
            String outcome,
            String reason) {
        return new ExecutionJournalState(
                checkpointId,
                request.executionId(),
                request.serverAgentId(),
                request.session().sessionId(),
                request.session().attempt(),
                outcome,
                reason,
                decision == null ? null : decision.summary(),
                usage(guard),
                events.stream().map(event -> new JournalEvent(
                        event.sequence(), event.eventType(), event.actionFingerprint(), event.attributes())).toList(),
                clock.instant());
    }

    private static ExecutionUsage usage(AgentScopeExecutionGuard guard) {
        AgentScopeExecutionGuard.Usage value = guard.usage();
        return new ExecutionUsage(
                value.rounds(), value.modelCalls(), value.toolCalls(), value.inputTokens(),
                value.outputTokens(), value.cachedTokens());
    }

    private ToolPort requiredTool(String toolId) {
        ToolPort tool = toolPorts.get(toolId);
        if (tool == null) {
            throw new IllegalArgumentException("tool is not registered");
        }
        return tool;
    }

    private static Map<String, ToolPort> indexTools(List<ToolPort> tools) {
        Objects.requireNonNull(tools, "toolPorts");
        return Map.copyOf(tools.stream().collect(Collectors.toMap(
                ToolPort::name,
                Function.identity(),
                (left, right) -> {
                    throw new IllegalArgumentException("duplicate tool id: " + left.name());
                },
                LinkedHashMap::new)));
    }

    private static AgentScopeExecutionGuard.Limits toLimits(ExecutionLimits limits) {
        return new AgentScopeExecutionGuard.Limits(
                limits.maxRounds(), limits.maxModelCalls(), limits.maxToolCalls(), limits.maxTokens(),
                limits.deadline(), limits.maxNoEvidenceRounds());
    }

    private static String checkpointId(
            ExecutionRequest request, AgentScopeExecutionGuard.Usage usage, AgentDecision decision) {
        String value = request.executionId() + "|" + request.session().sessionId() + "|"
                + usage.modelCalls() + "|" + usage.toolCalls() + "|"
                + usage.inputTokens() + "|" + usage.outputTokens() + "|" + decision.summary();
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }

    private static AgentExecutionStoppedException findStopped(Throwable failure) {
        if (failure == null) {
            return null;
        }
        return failure instanceof AgentExecutionStoppedException stopped
                ? stopped
                : findStopped(failure.getCause());
    }

    public record ExecutionJournalState(
            String checkpointId,
            String executionId,
            String serverAgentId,
            String sessionId,
            int attempt,
            String outcome,
            String terminationReason,
            String decisionSummary,
            ExecutionUsage usage,
            List<JournalEvent> events,
            Instant completedAt) implements State {
        public ExecutionJournalState {
            events = List.copyOf(events);
        }
    }

    public record JournalEvent(
            long sequence,
            String eventType,
            String actionFingerprint,
            Map<String, String> attributes) {
        public JournalEvent {
            attributes = Map.copyOf(attributes);
        }
    }

    private static final class GuardedChatPort implements ChatPort {
        private final ChatPort delegate;
        private final AgentScopeExecutionGuard guard;
        private final AgentScopeAuditCollector audit;
        private final Instant deadline;

        private GuardedChatPort(
                ChatPort delegate,
                AgentScopeExecutionGuard guard,
                AgentScopeAuditCollector audit,
                Instant deadline) {
            this.delegate = delegate;
            this.guard = guard;
            this.audit = audit;
            this.deadline = deadline;
        }

        @Override
        public ChatResponse complete(ChatRequest request) {
            int currentRound = guard.usage().rounds();
            ProviderResult<ChatResponse> result = delegate.invoke(new ChatInvocation(request, deadline, null));
            if (result.failure() != null) {
                throw new ProviderCallFailed(result.failure().errorCode());
            }
            ChatResponse response = result.value();
            TokenUsage tokenUsage = response.usage();
            requireAllowed(guard.afterModelCall(
                    tokenUsage.inputTokens(), tokenUsage.outputTokens(), tokenUsage.cachedTokens()));
            audit.event("MODEL_COMPLETED", currentRound, null,
                    tokenUsage.inputTokens(), tokenUsage.outputTokens(), null, false,
                    Map.of("finishReason", String.valueOf(response.finishReason())));
            return response;
        }
    }

    private static final class GuardedToolPort implements ToolPort {
        private final ToolPort delegate;
        private final AgentScopeExecutionGuard guard;
        private final AgentScopeAuditCollector audit;

        private GuardedToolPort(
                ToolPort delegate, AgentScopeExecutionGuard guard, AgentScopeAuditCollector audit) {
            this.delegate = delegate;
            this.guard = guard;
            this.audit = audit;
        }

        public String name() { return delegate.name(); }
        public String description() { return delegate.description(); }
        public Map<String, Object> inputSchema() { return delegate.inputSchema(); }

        @Override
        public ToolResult execute(Map<String, Object> input) {
            String fingerprint = audit.actionFingerprint("CALL_TOOL", name(), input);
            audit.event("TOOL_STARTED", guard.usage().rounds(), fingerprint,
                    null, null, null, false, Map.of("toolName", name()));
            ToolResult result = delegate.execute(input);
            requireAllowed(guard.afterToolCall(result.evidenceIds().size()));
            audit.event("TOOL_COMPLETED", guard.usage().rounds(), fingerprint,
                    null, null, null, false, Map.of(
                            "toolName", name(), "status", result.status().name()));
            return result;
        }
    }

    private static void requireAllowed(AgentScopeExecutionGuard.StopDecision decision) {
        if (!decision.permitted()) {
            throw new AgentExecutionStoppedException(decision.reasonCode());
        }
    }

    private static final class ProviderCallFailed extends RuntimeException {
        private ProviderCallFailed(String reasonCode) {
            super(reasonCode, null, false, false);
        }
    }

    private static final class CollectingAuditSink implements RuntimeAuditSink {
        private final RuntimeAuditSink delegate;
        private final AtomicLong sequence = new AtomicLong();
        private final List<ExecutionEvent> events = new ArrayList<>();

        private CollectingAuditSink(RuntimeAuditSink delegate) {
            this.delegate = delegate;
        }

        @Override
        public synchronized void append(AuditEvent event) {
            long next = sequence.incrementAndGet();
            Map<String, Object> attributes = new LinkedHashMap<>(event.attributes());
            AuditEvent resequenced = new AuditEvent(
                    next, event.type(), event.round(), event.actionFingerprint(),
                    event.inputTokens(), event.outputTokens(), event.checkpointId(),
                    event.cancelled(), attributes);
            delegate.append(resequenced);
            Map<String, String> stringAttributes = new LinkedHashMap<>();
            attributes.forEach((key, value) -> stringAttributes.put(key, String.valueOf(value)));
            events.add(new ExecutionEvent(
                    next, event.type(), event.actionFingerprint(), stringAttributes));
        }

        private synchronized List<ExecutionEvent> events() {
            return List.copyOf(events);
        }
    }
}
