package io.github.opspilot.core.port.agent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The only project-owned boundary for executing an agent loop. */
public interface AgentExecutionService {

    String SYSTEM_USER_ID = "opspilot-system";

    ExecutionResult execute(ExecutionRequest request);

    record ExecutionRequest(
            String executionId,
            String serverAgentId,
            String agentName,
            String systemPrompt,
            String modelProfileRef,
            String input,
            List<String> toolIds,
            ExecutionLimits limits,
            ExecutionSession session,
            Map<String, String> context,
            CancellationToken cancellationToken) {
        public ExecutionRequest {
            executionId = required("executionId", executionId);
            serverAgentId = required("serverAgentId", serverAgentId);
            agentName = required("agentName", agentName);
            systemPrompt = required("systemPrompt", systemPrompt);
            modelProfileRef = required("modelProfileRef", modelProfileRef);
            input = required("input", input);
            toolIds = List.copyOf(Objects.requireNonNull(toolIds, "toolIds"));
            limits = Objects.requireNonNull(limits, "limits");
            session = Objects.requireNonNull(session, "session");
            context = Map.copyOf(Objects.requireNonNull(context, "context"));
            cancellationToken = Objects.requireNonNull(cancellationToken, "cancellationToken");
        }
    }

    record ExecutionLimits(
            int maxRounds,
            int maxModelCalls,
            int maxToolCalls,
            int maxTokens,
            int maxNoEvidenceRounds,
            Instant deadline) {
        public ExecutionLimits {
            if (maxRounds < 1 || maxModelCalls < 1 || maxToolCalls < 0
                    || maxTokens < 1 || maxNoEvidenceRounds < 1) {
                throw new IllegalArgumentException("execution limits must be positive");
            }
            Objects.requireNonNull(deadline, "deadline");
        }
    }

    record ExecutionSession(String userId, String sessionId, int attempt, boolean continuation) {
        public ExecutionSession {
            userId = required("userId", userId);
            sessionId = required("sessionId", sessionId);
            if (!SYSTEM_USER_ID.equals(userId)) {
                throw new IllegalArgumentException("MVP userId must be " + SYSTEM_USER_ID);
            }
            if (attempt < 1) {
                throw new IllegalArgumentException("attempt must be positive");
            }
        }

        public static ExecutionSession supervisor(String runId, int attempt, boolean continuation) {
            return new ExecutionSession(
                    SYSTEM_USER_ID, "supervisor:" + required("runId", runId), attempt, continuation);
        }

        public static ExecutionSession specialist(
                String serverAgentId, String a2aTaskId, int attempt, boolean continuation) {
            return new ExecutionSession(
                    SYSTEM_USER_ID,
                    required("serverAgentId", serverAgentId) + ":" + required("a2aTaskId", a2aTaskId),
                    attempt,
                    continuation);
        }
    }

    record AgentDecision(
            String summary,
            List<String> evidenceIds,
            List<String> artifactIds) {
        public AgentDecision {
            summary = summary == null ? "" : summary;
            evidenceIds = List.copyOf(Objects.requireNonNull(evidenceIds, "evidenceIds"));
            artifactIds = List.copyOf(Objects.requireNonNull(artifactIds, "artifactIds"));
        }
    }

    record ExecutionUsage(
            int rounds,
            int modelCalls,
            int toolCalls,
            int inputTokens,
            int outputTokens,
            int cachedTokens) {
        public int totalTokens() {
            return inputTokens + outputTokens;
        }
    }

    record ExecutionCheckpoint(String checkpointId, String sessionId, Instant savedAt) {
        public ExecutionCheckpoint {
            checkpointId = required("checkpointId", checkpointId);
            sessionId = required("sessionId", sessionId);
            Objects.requireNonNull(savedAt, "savedAt");
        }
    }

    record ExecutionEvent(
            long sequence,
            String eventType,
            String actionFingerprint,
            Map<String, String> attributes) {
        public ExecutionEvent {
            if (sequence < 1) {
                throw new IllegalArgumentException("sequence must be positive");
            }
            eventType = required("eventType", eventType);
            attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
        }
    }

    record ExecutionResult(
            ExecutionOutcome outcome,
            AgentDecision decision,
            ExecutionUsage usage,
            ExecutionCheckpoint checkpoint,
            List<ExecutionEvent> events,
            String terminationReason,
            Instant completedAt) {
        public ExecutionResult {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(usage, "usage");
            events = List.copyOf(Objects.requireNonNull(events, "events"));
            Objects.requireNonNull(completedAt, "completedAt");
            if (outcome == ExecutionOutcome.COMPLETED && decision == null) {
                throw new IllegalArgumentException("completed execution requires a decision");
            }
            if (outcome != ExecutionOutcome.COMPLETED
                    && (terminationReason == null || terminationReason.isBlank())) {
                throw new IllegalArgumentException("non-completed execution requires a reason");
            }
        }
    }

    enum ExecutionOutcome { COMPLETED, TERMINATED, FAILED }

    @FunctionalInterface
    interface CancellationToken {
        boolean cancellationRequested();

        static CancellationToken never() {
            return () -> false;
        }
    }

    private static String required(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
