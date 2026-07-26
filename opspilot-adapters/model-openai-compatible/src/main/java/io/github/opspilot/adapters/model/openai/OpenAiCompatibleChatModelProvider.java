package io.github.opspilot.adapters.model.openai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.opspilot.core.port.agent.ChatPort;
import io.github.opspilot.core.port.agent.ChatPort.ChatMessage;
import io.github.opspilot.core.port.agent.ChatPort.StructuredOutput;
import io.github.opspilot.core.application.provider.ModelConfiguration.ModelCapability;
import io.github.opspilot.core.port.provider.SecretResolver;
import io.github.opspilot.core.port.provider.SecretResolver.ResolutionContext;
import io.github.opspilot.core.port.provider.SecretResolver.ResolvedSecret;
import io.github.opspilot.core.domain.failure.ChainFailure;
import io.github.opspilot.adapters.model.openai.OpenAiProviderFailureMapper.FailureContext;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** OpenAI-compatible Chat provider with one-attempt ordinary and complete-only streaming calls. */
public final class OpenAiCompatibleChatModelProvider implements ChatPort {
    private final String stableProviderId;
    private final String revision;
    private final OpenAiCompatibleClientConfiguration configuration;
    private final SecretResolver secrets;
    private final OpenAiCompatibleHttpClient http;
    private final OpenAiChatMapper mapper;
    private final OpenAiChatStreamAccumulator streams;
    private final StrictJsonSchemaValidator structuredOutput;
    private final Set<ModelCapability> validatedCapabilities;
    private final OpenAiProviderFailureMapper failures = new OpenAiProviderFailureMapper();

    public OpenAiCompatibleChatModelProvider(
            String stableProviderId,
            String revision,
            OpenAiCompatibleClientConfiguration configuration,
            SecretResolver secrets) {
        this(stableProviderId, revision, configuration, secrets,
                Set.of(), new OpenAiCompatibleHttpClient(), new ObjectMapper());
    }

    public OpenAiCompatibleChatModelProvider(
            String stableProviderId,
            String revision,
            OpenAiCompatibleClientConfiguration configuration,
            SecretResolver secrets,
            Set<ModelCapability> validatedCapabilities) {
        this(stableProviderId, revision, configuration, secrets,
                validatedCapabilities, new OpenAiCompatibleHttpClient(), new ObjectMapper());
    }

    OpenAiCompatibleChatModelProvider(
            String stableProviderId,
            String revision,
            OpenAiCompatibleClientConfiguration configuration,
            SecretResolver secrets,
            Set<ModelCapability> validatedCapabilities,
            OpenAiCompatibleHttpClient http,
            ObjectMapper json) {
        this.stableProviderId = requireText(stableProviderId, "stableProviderId");
        this.revision = requireText(revision, "revision");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.secrets = Objects.requireNonNull(secrets, "secrets");
        this.validatedCapabilities = Set.copyOf(
                Objects.requireNonNull(validatedCapabilities, "validatedCapabilities"));
        this.http = Objects.requireNonNull(http, "http");
        this.mapper = new OpenAiChatMapper(json);
        this.streams = new OpenAiChatStreamAccumulator(json);
        this.structuredOutput = new StrictJsonSchemaValidator(json);
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        return complete(new ChatInvocation(request, Instant.now().plusSeconds(30),
                new io.github.opspilot.core.port.provider.ProviderContracts.ProviderIdentity(
                        stableProviderId, requireConfigured(configuration.model(), "model"), revision)));
    }

    public ChatResponse complete(ChatInvocation invocation) {
        return execute(invocation, false);
    }

    public ChatResponse completeStreaming(ChatInvocation invocation) {
        return execute(invocation, true);
    }

    /** Failure-returning boundary used by governed callers; exactly one response or ChainFailure is present. */
    public CallOutcome call(ChatInvocation invocation, FailureContext context, boolean stream) {
        try {
            return new CallOutcome(execute(invocation, stream), null);
        } catch (ProviderInvocationException wrapper) {
            Throwable mapped = wrapper.getCause() == null ? wrapper : wrapper.getCause();
            return new CallOutcome(null, failures.map(mapped, context));
        } catch (RuntimeException failure) {
            return new CallOutcome(null, failures.map(failure, context));
        }
    }

    private ChatResponse execute(ChatInvocation invocation, boolean stream) {
        Objects.requireNonNull(invocation, "invocation");
        String model = requireConfigured(configuration.model(), "model");
        String secretRef = requireConfigured(configuration.secretRef(), "secretRef");
        if (!model.equals(invocation.request().modelId())) {
            throw new ProviderInvocationException("requested model does not match configured model");
        }
        verifyCapabilities(invocation.request(), stream);
        Duration timeout = Duration.between(Instant.now(), invocation.deadline());
        if (timeout.isZero() || timeout.isNegative()) {
            throw new DeadlineException();
        }
        URI endpoint = OpenAiCompatibleUris.resource(configuration.baseUrl(), "chat/completions");
        OpenAiChatDtos.CompletionRequest payload = mapper.toRequest(invocation.request(), stream);
        try (ResolvedSecret secret = secrets.resolve(secretRef, new ResolutionContext("model-provider", true))) {
            ChatResponse response = send(endpoint, payload, secret, timeout, stream);
            StructuredOutput requested = invocation.request().structuredOutput();
            if (requested == null) {
                return response;
            }
            try {
                validateStructured(response, requested);
                return response;
            } catch (StrictJsonSchemaValidator.StructuredOutputException invalid) {
                if (!requested.repairAllowed()) {
                    throw invalid;
                }
                Duration remaining = Duration.between(Instant.now(), invocation.deadline());
                if (remaining.compareTo(Duration.ofMillis(250)) < 0) {
                    throw new DeadlineException();
                }
                ChatRequest repairRequest = repairRequest(invocation.request(), response.text());
                ChatResponse repaired = send(endpoint, mapper.toRequest(repairRequest, false), secret, remaining, false);
                validateStructured(repaired, requested);
                return repaired;
            }
        } catch (IOException exception) {
            throw new ProviderInvocationException("provider transport failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ProviderInvocationException("provider invocation cancelled", exception);
        }
    }

    private ChatResponse send(
            URI endpoint,
            OpenAiChatDtos.CompletionRequest payload,
            ResolvedSecret secret,
            Duration timeout,
            boolean stream) throws IOException, InterruptedException {
        OpenAiCompatibleHttpClient.HttpResult result = stream
                ? http.postEventStream(endpoint, payload, secret, timeout)
                : http.postJson(endpoint, payload, secret, timeout);
        if (result.statusCode() < 200 || result.statusCode() >= 300) {
            throw new HttpStatusException(result.statusCode(), result.requestId());
        }
        OpenAiChatDtos.CompletionResponse wireResponse = stream
                ? streams.complete(result.body())
                : mapper.readResponse(result.body());
        return mapper.toDomain(wireResponse, stableProviderId, revision).response();
    }

    private void verifyCapabilities(ChatRequest request, boolean stream) {
        if (!request.tools().isEmpty() && !validatedCapabilities.contains(ModelCapability.TOOL_CALLS)) {
            throw new CapabilityException("TOOL_CALLS");
        }
        if (request.structuredOutput() != null
                && !validatedCapabilities.contains(ModelCapability.STRUCTURED_OUTPUT)) {
            throw new CapabilityException("STRUCTURED_OUTPUT");
        }
        if (stream && !validatedCapabilities.contains(ModelCapability.STREAMING)) {
            throw new CapabilityException("STREAMING");
        }
    }

    private void validateStructured(ChatResponse response, StructuredOutput requested) {
        if (response.text() == null) {
            throw new StrictJsonSchemaValidator.StructuredOutputException("structured response text is missing");
        }
        structuredOutput.parseAndValidate(response.text(), requested.jsonSchema());
    }

    private static ChatRequest repairRequest(ChatRequest original, String invalidOutput) {
        List<ChatMessage> messages = new ArrayList<>(original.messages());
        messages.add(new ChatMessage("assistant", invalidOutput));
        messages.add(new ChatMessage("user",
                "Return only corrected JSON that strictly matches the requested JSON Schema."));
        StructuredOutput originalOutput = original.structuredOutput();
        StructuredOutput repairOutput = new StructuredOutput(
                originalOutput.name(), originalOutput.jsonSchema(), originalOutput.strict(), false);
        return new ChatRequest(original.modelId(), messages, List.of(), repairOutput);
    }

    private static String requireConfigured(String value, String field) {
        if (value == null) {
            throw new ProviderInvocationException(field + " is not configured");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    public static final class ProviderInvocationException extends RuntimeException {
        ProviderInvocationException(String message) {
            super(message);
        }

        ProviderInvocationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static final class HttpStatusException extends RuntimeException {
        private final int statusCode;
        private final String requestId;

        HttpStatusException(int statusCode, String requestId) {
            super("provider returned an unsuccessful HTTP status");
            this.statusCode = statusCode;
            this.requestId = requestId;
        }

        int statusCode() {
            return statusCode;
        }

        String requestId() {
            return requestId;
        }
    }

    static final class CapabilityException extends RuntimeException {
        CapabilityException(String capability) {
            super("required capability is not validated: " + capability);
        }
    }

    static final class DeadlineException extends RuntimeException {
        DeadlineException() {
            super("provider deadline expired");
        }
    }

    public record CallOutcome(ChatResponse response, ChainFailure failure) {
        public CallOutcome {
            if ((response == null) == (failure == null)) {
                throw new IllegalArgumentException("call outcome must contain exactly one response or failure");
            }
        }
    }
}
