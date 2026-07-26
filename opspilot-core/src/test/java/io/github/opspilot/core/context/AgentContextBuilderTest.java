package io.github.opspilot.core.context;

import io.github.opspilot.core.application.context.AgentContextBuilder;
import io.github.opspilot.core.application.context.AgentContextContracts.ContextRequest;
import io.github.opspilot.core.application.context.AgentContextContracts.EvidenceReference;
import io.github.opspilot.core.application.context.AgentContextContracts.PromptBundle;
import io.github.opspilot.core.application.context.AgentContextContracts.VersionedPrompt;
import io.github.opspilot.core.domain.identity.DomainIds.ArtifactId;
import io.github.opspilot.core.domain.identity.DomainIds.EvidenceId;
import io.github.opspilot.core.port.agent.ChatPort.ToolDefinition;
import io.github.opspilot.core.port.artifact.AuthorizedArtifactSnippetPort.ArtifactSnippet;
import io.github.opspilot.core.port.artifact.AuthorizedArtifactSnippetPort.ArtifactSnippetRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentContextBuilderTest {
    private static final ArtifactId ARTIFACT_ID = new ArtifactId(UUID.fromString(
            "00000000-0000-0000-0000-000000000001"));
    private static final EvidenceId EVIDENCE_ID = new EvidenceId(UUID.fromString(
            "00000000-0000-0000-0000-000000000002"));

    @Test
    void buildsOnlyVersionedTaskStateToolsEvidenceAndAuthorizedMinimumArtifactSnippet() {
        AtomicReference<ArtifactSnippetRequest> requested = new AtomicReference<>();
        AgentContextBuilder builder = new AgentContextBuilder((actorId, request) -> {
            assertEquals("agent-1", actorId);
            requested.set(request);
            return new ArtifactSnippet(request.artifactId(), request.location(), "bounded excerpt");
        });
        ArtifactSnippetRequest snippet = new ArtifactSnippetRequest(ARTIFACT_ID, "lines:10-12", 32);
        ToolDefinition tool = new ToolDefinition("lookup", "Lookup evidence", Map.of("type", "object"));

        var context = builder.build(new ContextRequest(
                "agent-1",
                new PromptBundle(
                        new VersionedPrompt("common", "1.0.0", "common rules"),
                        new VersionedPrompt("diagnosis", "2.0.0", "role rules"),
                        new VersionedPrompt("task-shape", "3.0.0", "dynamic rules")),
                "diagnose incident INC-1",
                "service checkout is degraded",
                List.of(tool),
                List.of(new EvidenceReference(EVIDENCE_ID)),
                List.of(snippet),
                Map.of("type", "object")));

        assertEquals(snippet, requested.get());
        assertEquals(List.of("1.0.0", "2.0.0", "3.0.0"),
                context.promptVersions().stream().map(version -> version.version()).toList());
        assertEquals(List.of(EVIDENCE_ID), context.evidenceIds());
        assertEquals(List.of(tool), context.allowedTools());
        String rendered = context.messages().stream().map(message -> message.content()).reduce("", String::concat);
        assertTrue(rendered.contains("diagnose incident INC-1"));
        assertTrue(rendered.contains(EVIDENCE_ID.wire()));
        assertTrue(rendered.contains("bounded excerpt"));
        assertFalse(rendered.contains("unrelated conversation"));
    }

    @Test
    void failsClosedWhenReaderReturnsMoreThanTheAuthorizedSnippet() {
        AgentContextBuilder builder = new AgentContextBuilder((actorId, request) ->
                new ArtifactSnippet(request.artifactId(), request.location(), "too long"));
        ContextRequest request = request(new ArtifactSnippetRequest(ARTIFACT_ID, "bytes:0-2", 3));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> builder.build(request));

        assertEquals("ARTIFACT_SNIPPET_EXCEEDS_AUTHORIZED_LIMIT", failure.getMessage());
    }

    @Test
    void propagatesAuthorizationFailureWithoutUsingAnUntrustedFallback() {
        AgentContextBuilder builder = new AgentContextBuilder((actorId, request) -> {
            throw new SecurityException("ARTIFACT_ACCESS_DENIED");
        });

        SecurityException failure = assertThrows(SecurityException.class,
                () -> builder.build(request(new ArtifactSnippetRequest(ARTIFACT_ID, "lines:1-1", 8))));

        assertEquals("ARTIFACT_ACCESS_DENIED", failure.getMessage());
    }

    private static ContextRequest request(ArtifactSnippetRequest snippet) {
        return new ContextRequest(
                "agent-1",
                new PromptBundle(
                        new VersionedPrompt("common", "1", "common"),
                        new VersionedPrompt("role", "1", "role"),
                        new VersionedPrompt("dynamic", "1", "dynamic")),
                "task",
                "state",
                List.of(),
                List.of(),
                List.of(snippet),
                Map.of());
    }
}
