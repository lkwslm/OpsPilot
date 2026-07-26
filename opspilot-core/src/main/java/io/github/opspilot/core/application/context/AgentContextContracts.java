package io.github.opspilot.core.application.context;

import io.github.opspilot.core.domain.identity.DomainIds.EvidenceId;
import io.github.opspilot.core.port.agent.ChatPort.ChatMessage;
import io.github.opspilot.core.port.agent.ChatPort.ToolDefinition;
import io.github.opspilot.core.port.artifact.AuthorizedArtifactSnippetPort.ArtifactSnippetRequest;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Narrow, auditable inputs and outputs for model context assembly. */
public final class AgentContextContracts {
    private AgentContextContracts() {
    }

    public record VersionedPrompt(String promptId, String version, String content) {
        public VersionedPrompt {
            promptId = requireText(promptId, "promptId");
            version = requireText(version, "version");
            content = requireText(content, "content");
        }
    }

    public record PromptBundle(
            VersionedPrompt commonPrompt,
            VersionedPrompt rolePrompt,
            VersionedPrompt dynamicPrompt) {
        public PromptBundle {
            Objects.requireNonNull(commonPrompt, "commonPrompt");
            Objects.requireNonNull(rolePrompt, "rolePrompt");
            Objects.requireNonNull(dynamicPrompt, "dynamicPrompt");
        }
    }

    public record EvidenceReference(EvidenceId evidenceId) {
        public EvidenceReference {
            Objects.requireNonNull(evidenceId, "evidenceId");
        }
    }

    public record ContextRequest(
            String actorId,
            PromptBundle prompts,
            String currentTask,
            String necessaryState,
            List<ToolDefinition> allowedTools,
            List<EvidenceReference> evidenceReferences,
            List<ArtifactSnippetRequest> artifactSnippetRequests,
            Map<String, Object> outputSchema) {
        public ContextRequest {
            actorId = requireText(actorId, "actorId");
            Objects.requireNonNull(prompts, "prompts");
            currentTask = requireText(currentTask, "currentTask");
            necessaryState = necessaryState == null ? "" : necessaryState;
            allowedTools = List.copyOf(Objects.requireNonNull(allowedTools, "allowedTools"));
            evidenceReferences = List.copyOf(Objects.requireNonNull(evidenceReferences, "evidenceReferences"));
            artifactSnippetRequests = List.copyOf(
                    Objects.requireNonNull(artifactSnippetRequests, "artifactSnippetRequests"));
            outputSchema = Map.copyOf(Objects.requireNonNull(outputSchema, "outputSchema"));
        }
    }

    public record PromptVersion(String promptId, String version) {
        public PromptVersion {
            promptId = requireText(promptId, "promptId");
            version = requireText(version, "version");
        }
    }

    public record AgentContext(
            List<ChatMessage> messages,
            List<ToolDefinition> allowedTools,
            Map<String, Object> outputSchema,
            List<PromptVersion> promptVersions,
            List<EvidenceId> evidenceIds,
            List<ArtifactSnippetRequest> artifactReferences) {
        public AgentContext {
            messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
            allowedTools = List.copyOf(Objects.requireNonNull(allowedTools, "allowedTools"));
            outputSchema = Map.copyOf(Objects.requireNonNull(outputSchema, "outputSchema"));
            promptVersions = List.copyOf(Objects.requireNonNull(promptVersions, "promptVersions"));
            evidenceIds = List.copyOf(Objects.requireNonNull(evidenceIds, "evidenceIds"));
            artifactReferences = List.copyOf(Objects.requireNonNull(artifactReferences, "artifactReferences"));
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
