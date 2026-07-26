package io.github.opspilot.core.application.context;

import io.github.opspilot.core.application.context.AgentContextContracts.AgentContext;
import io.github.opspilot.core.application.context.AgentContextContracts.ContextRequest;
import io.github.opspilot.core.application.context.AgentContextContracts.PromptVersion;
import io.github.opspilot.core.application.context.AgentContextContracts.VersionedPrompt;
import io.github.opspilot.core.port.agent.ChatPort.ChatMessage;
import io.github.opspilot.core.port.artifact.AuthorizedArtifactSnippetPort;
import io.github.opspilot.core.port.artifact.AuthorizedArtifactSnippetPort.ArtifactSnippet;
import io.github.opspilot.core.port.artifact.AuthorizedArtifactSnippetPort.ArtifactSnippetRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Builds only the explicitly authorized context required by one current task. */
public final class AgentContextBuilder {
    private final AuthorizedArtifactSnippetPort artifacts;

    public AgentContextBuilder(AuthorizedArtifactSnippetPort artifacts) {
        this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
    }

    public AgentContext build(ContextRequest request) {
        Objects.requireNonNull(request, "request");
        List<ChatMessage> messages = new ArrayList<>();
        addPrompt(messages, request.prompts().commonPrompt());
        addPrompt(messages, request.prompts().rolePrompt());
        addPrompt(messages, request.prompts().dynamicPrompt());
        messages.add(new ChatMessage("user", "CURRENT_TASK\n" + request.currentTask()));
        if (!request.necessaryState().isBlank()) {
            messages.add(new ChatMessage("user", "NECESSARY_STATE\n" + request.necessaryState()));
        }
        if (!request.evidenceReferences().isEmpty()) {
            String references = request.evidenceReferences().stream()
                    .map(reference -> reference.evidenceId().wire())
                    .reduce((left, right) -> left + "\n" + right)
                    .orElseThrow();
            messages.add(new ChatMessage("user", "EVIDENCE_REFERENCES\n" + references));
        }
        for (ArtifactSnippetRequest snippetRequest : request.artifactSnippetRequests()) {
            ArtifactSnippet snippet = artifacts.readAuthorized(request.actorId(), snippetRequest);
            validateSnippet(snippetRequest, snippet);
            messages.add(new ChatMessage("user", "ARTIFACT_SNIPPET " + snippet.artifactId().wire()
                    + " " + snippet.location() + "\n" + snippet.content()));
        }
        List<PromptVersion> promptVersions = List.of(
                versionOf(request.prompts().commonPrompt()),
                versionOf(request.prompts().rolePrompt()),
                versionOf(request.prompts().dynamicPrompt()));
        return new AgentContext(messages, request.allowedTools(), request.outputSchema(), promptVersions,
                request.evidenceReferences().stream().map(reference -> reference.evidenceId()).toList(),
                request.artifactSnippetRequests());
    }

    private static void addPrompt(List<ChatMessage> messages, VersionedPrompt prompt) {
        messages.add(new ChatMessage("system", prompt.content()));
    }

    private static PromptVersion versionOf(VersionedPrompt prompt) {
        return new PromptVersion(prompt.promptId(), prompt.version());
    }

    private static void validateSnippet(ArtifactSnippetRequest request, ArtifactSnippet snippet) {
        if (snippet == null
                || !request.artifactId().equals(snippet.artifactId())
                || !request.location().equals(snippet.location())) {
            throw new IllegalArgumentException("ARTIFACT_SNIPPET_IDENTITY_MISMATCH");
        }
        if (snippet.content().length() > request.maxCharacters()) {
            throw new IllegalArgumentException("ARTIFACT_SNIPPET_EXCEEDS_AUTHORIZED_LIMIT");
        }
    }
}
