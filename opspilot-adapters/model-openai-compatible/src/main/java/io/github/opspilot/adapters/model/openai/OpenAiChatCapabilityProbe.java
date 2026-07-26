package io.github.opspilot.adapters.model.openai;

import io.github.opspilot.core.port.agent.ChatPort.ChatInvocation;
import io.github.opspilot.core.port.agent.ChatPort.ChatMessage;
import io.github.opspilot.core.port.agent.ChatPort.ChatRequest;
import io.github.opspilot.core.port.agent.ChatPort.ChatResponse;
import io.github.opspilot.core.port.agent.ChatPort.StructuredOutput;
import io.github.opspilot.core.port.agent.ChatPort.ToolDefinition;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderIdentity;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Executes minimal real requests for every Chat capability before readiness is granted. */
public final class OpenAiChatCapabilityProbe {
    private final ProbeInvoker invoker;
    private final ProviderIdentity expectedIdentity;
    private final String configVersion;
    private final boolean streamingRequired;
    private final Duration timeoutPerProbe;
    private final Clock clock;

    public OpenAiChatCapabilityProbe(
            OpenAiCompatibleChatModelProvider provider,
            ProviderIdentity expectedIdentity,
            String configVersion,
            boolean streamingRequired,
            Duration timeoutPerProbe,
            Clock clock) {
        this((invocation, streaming) -> streaming
                        ? provider.completeStreaming(invocation)
                        : provider.complete(invocation),
                expectedIdentity, configVersion, streamingRequired, timeoutPerProbe, clock);
    }

    OpenAiChatCapabilityProbe(
            ProbeInvoker invoker,
            ProviderIdentity expectedIdentity,
            String configVersion,
            boolean streamingRequired,
            Duration timeoutPerProbe,
            Clock clock) {
        this.invoker = Objects.requireNonNull(invoker, "invoker");
        this.expectedIdentity = Objects.requireNonNull(expectedIdentity, "expectedIdentity");
        this.configVersion = requireText(configVersion, "configVersion");
        this.streamingRequired = streamingRequired;
        this.timeoutPerProbe = Objects.requireNonNull(timeoutPerProbe, "timeoutPerProbe");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (timeoutPerProbe.isZero() || timeoutPerProbe.isNegative()) {
            throw new IllegalArgumentException("timeoutPerProbe must be positive");
        }
    }

    public CapabilitySnapshot probe() {
        Instant startedAt = clock.instant();
        List<CapabilityResult> results = new ArrayList<>();
        results.add(run(Capability.IDENTITY, ordinaryRequest(), false,
                response -> response.actualIdentity() != null
                        && expectedIdentity.providerId().equals(response.actualIdentity().providerId())
                        && expectedIdentity.modelId().equals(response.actualIdentity().modelId())));
        results.add(run(Capability.ORDINARY_CHAT, ordinaryRequest(), false,
                response -> response.finishReason() != null));
        results.add(run(Capability.TOOL_CALLS, toolRequest(), false,
                response -> !response.toolCalls().isEmpty()));
        results.add(run(Capability.STRUCTURED_OUTPUT, structuredRequest(), false,
                response -> response.text() != null));
        if (streamingRequired) {
            results.add(run(Capability.STREAMING, ordinaryRequest(), true,
                    response -> response.finishReason() != null));
        } else {
            results.add(new CapabilityResult(Capability.STREAMING, ProbeStatus.NOT_REQUIRED,
                    null, null, null, "NOT_REQUIRED"));
        }
        Set<Capability> required = EnumSet.of(Capability.IDENTITY, Capability.ORDINARY_CHAT,
                Capability.TOOL_CALLS, Capability.STRUCTURED_OUTPUT);
        if (streamingRequired) {
            required.add(Capability.STREAMING);
        }
        boolean ready = results.stream()
                .filter(result -> required.contains(result.capability()))
                .allMatch(result -> result.status() == ProbeStatus.VALIDATED);
        return new CapabilitySnapshot("1.0.0", configVersion, startedAt, clock.instant(),
                expectedIdentity, List.copyOf(results), ready, "PASS_NO_SECRET_OR_BODY_RECORDED");
    }

    private CapabilityResult run(
            Capability capability, ChatRequest request, boolean streaming, ResponseAssertion assertion) {
        Instant attemptedAt = clock.instant();
        try {
            ChatInvocation invocation = new ChatInvocation(
                    request, attemptedAt.plus(timeoutPerProbe), expectedIdentity);
            ChatResponse response = invoker.invoke(invocation, streaming);
            if (!assertion.valid(response)) {
                return new CapabilityResult(capability, ProbeStatus.FAILED, attemptedAt,
                        usage(response), safeIdentity(response), "CAPABILITY_RESPONSE_INVALID");
            }
            return new CapabilityResult(capability, ProbeStatus.VALIDATED, attemptedAt,
                    usage(response), safeIdentity(response), "VALIDATED");
        } catch (RuntimeException failure) {
            return new CapabilityResult(capability, ProbeStatus.FAILED, attemptedAt,
                    null, null, "PROBE_CALL_FAILED");
        }
    }

    private ChatRequest ordinaryRequest() {
        return new ChatRequest(expectedIdentity.modelId(),
                List.of(new ChatMessage("user", "Reply with OK.")), List.of());
    }

    private ChatRequest toolRequest() {
        return new ChatRequest(expectedIdentity.modelId(),
                List.of(new ChatMessage("user", "Call the probe tool with value OK.")),
                List.of(new ToolDefinition("capability_probe", "Return the supplied value",
                        Map.of("type", "object", "properties", Map.of(
                                "value", Map.of("type", "string")), "required", List.of("value")))));
    }

    private ChatRequest structuredRequest() {
        return new ChatRequest(expectedIdentity.modelId(),
                List.of(new ChatMessage("user", "Return a JSON object whose status is OK.")), List.of(),
                new StructuredOutput("capability_probe", Map.of(
                        "type", "object",
                        "properties", Map.of("status", Map.of("type", "string")),
                        "required", List.of("status"),
                        "additionalProperties", false), true, false));
    }

    private static Usage usage(ChatResponse response) {
        return new Usage(response.usage().inputTokens(), response.usage().outputTokens(),
                response.usage().cachedTokens());
    }

    private static ProviderIdentity safeIdentity(ChatResponse response) {
        return response.actualIdentity();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    public enum Capability {
        IDENTITY,
        ORDINARY_CHAT,
        TOOL_CALLS,
        STRUCTURED_OUTPUT,
        STREAMING
    }

    public enum ProbeStatus {
        VALIDATED,
        FAILED,
        NOT_REQUIRED
    }

    public record Usage(long inputTokens, long outputTokens, long cachedTokens) {
    }

    public record CapabilityResult(
            Capability capability,
            ProbeStatus status,
            Instant attemptedAt,
            Usage usage,
            ProviderIdentity actualIdentity,
            String conclusion) {
    }

    public record CapabilitySnapshot(
            String schemaVersion,
            String configVersion,
            Instant startedAt,
            Instant completedAt,
            ProviderIdentity expectedIdentity,
            List<CapabilityResult> results,
            boolean allRequiredCapabilitiesValidated,
            String redactionConclusion) {
        public CapabilitySnapshot {
            results = List.copyOf(results);
        }
    }

    @FunctionalInterface
    interface ProbeInvoker {
        ChatResponse invoke(ChatInvocation invocation, boolean streaming);
    }

    @FunctionalInterface
    private interface ResponseAssertion {
        boolean valid(ChatResponse response);
    }
}
