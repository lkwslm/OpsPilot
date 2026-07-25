package io.github.opspilot.core.evidence;

import io.github.opspilot.core.application.evidence.EvidenceContracts.NormalizationContext;
import io.github.opspilot.core.application.evidence.EvidenceNormalizer;
import io.github.opspilot.core.application.evidence.EvidenceOnlyAnalysis;
import io.github.opspilot.core.application.evidence.RuntimeEvidenceNormalizer;
import io.github.opspilot.core.application.tool.ToolResultNormalizer;
import io.github.opspilot.core.domain.identity.DomainIds.ArtifactId;
import io.github.opspilot.core.domain.identity.DomainIds.EvidenceId;
import io.github.opspilot.core.domain.identity.DomainIds.RunId;
import io.github.opspilot.core.port.agent.ChatPort;
import io.github.opspilot.core.port.agent.ToolPort.ToolStatus;
import io.github.opspilot.core.port.artifact.ArtifactPort;
import io.github.opspilot.core.port.code.CodeAnalysisPort;
import io.github.opspilot.core.port.code.CodeContracts.CodeFinding;
import io.github.opspilot.core.port.code.CodeContracts.CodeSnapshot;
import io.github.opspilot.core.port.code.CodeSourcePort;
import io.github.opspilot.core.port.extension.ExtensionContracts;
import io.github.opspilot.core.port.knowledge.KnowledgeContracts.KnowledgeResult;
import io.github.opspilot.core.port.provider.EmbeddingPort;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderIdentity;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderResult;
import io.github.opspilot.core.port.provider.ProviderContracts.ProviderUsage;
import io.github.opspilot.core.port.provider.RerankPort;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceContractTest {
    private static final Instant NOW = Instant.parse("2026-07-18T12:00:00Z");
    private static final ProviderIdentity IDENTITY = new ProviderIdentity("provider-a", "model-a", "r1");
    private static final NormalizationContext CONTEXT = new NormalizationContext(uuid(1), uuid(2), uuid(3));

    @Test
    void chatEmbeddingAndRerankAreIndependentVendorNeutralPorts() {
        ChatPort chat = request -> new ChatPort.ChatResponse(
                "response-1", "bounded", List.of(), new ChatPort.TokenUsage(1, 1, 0), "stop");
        EmbeddingPort embedding = request -> new ProviderResult<>(
                List.of(new float[]{1.0f, 2.0f}), new ProviderUsage(1, 0, 10L), null);
        RerankPort rerank = request -> new ProviderResult<>(
                List.of(new RerankPort.RankedDocument("doc-1", .9)), new ProviderUsage(5, 1, 20L), null);

        var chatResult = chat.invoke(new ChatPort.ChatInvocation(
                new ChatPort.ChatRequest("model-a", List.of(), List.of()), NOW.plusSeconds(5), IDENTITY));
        assertEquals("bounded", chatResult.value().text());
        assertArrayEquals(new float[]{1.0f, 2.0f}, embedding.embed(
                new EmbeddingPort.EmbeddingRequest(IDENTITY, NOW.plusSeconds(5), List.of("text")))
                .value().getFirst());
        assertEquals("doc-1", rerank.rerank(new RerankPort.RerankRequest(
                IDENTITY, NOW.plusSeconds(5), "query", List.of(new RerankPort.DocumentCandidate("doc-1", "x"))))
                .value().getFirst().documentId());
        assertNotSame(chat.getClass(), embedding.getClass());
        assertFalse(allPortTypeNames().contains("openai") || allPortTypeNames().contains("deepseek"));
    }

    @Test
    void codeSourceMaterializesOneImmutableSnapshotTypeForRestrictedAnalyzer() {
        CodeSnapshot snapshot = new CodeSnapshot(
                "repository-1", "abc123", "sha256:" + "a".repeat(64), new ArtifactId(uuid(10)),
                "artifact://code-snapshots/abc123", NOW);
        CodeSourcePort github = request -> snapshot;
        CodeSourcePort gitlab = request -> snapshot;
        CodeAnalysisPort analyzer = (materialized, deadline) -> List.of(new CodeFinding(
                "finding-1", "null-check", "possible null dereference", "src/Main.java",
                "sha256:" + "b".repeat(64), List.of(new ArtifactId(uuid(11)))));

        CodeSnapshot fromGitHub = github.materialize(new CodeSourcePort.CodeSourceRequest(
                "repository-1", "abc123", "credential://github/repo-1", NOW.plusSeconds(5)));
        CodeSnapshot fromGitLab = gitlab.materialize(new CodeSourcePort.CodeSourceRequest(
                "repository-1", "abc123", "credential://gitlab/repo-1", NOW.plusSeconds(5)));
        assertEquals(fromGitHub, fromGitLab);
        assertEquals("finding-1", analyzer.analyze(fromGitHub, NOW.plusSeconds(5)).getFirst().findingId());
        assertFalse(CodeAnalysisPort.class.getMethods()[0].toGenericString().contains("credential"));
        assertThrows(IllegalArgumentException.class, () -> new CodeSnapshot(
                "repository-1", "abc123", "sha256:" + "a".repeat(64), new ArtifactId(uuid(10)),
                "C:\\host\\workspace", NOW));
    }

    @Test
    void runtimeCodeAndKnowledgeShareOneImmutableEvidenceBoundary() {
        RuntimeEvidenceNormalizer normalizer = new RuntimeEvidenceNormalizer();
        CodeSnapshot snapshot = new CodeSnapshot(
                "repository-1", "abc123", "sha256:" + "a".repeat(64), new ArtifactId(uuid(10)),
                "artifact://code-snapshots/abc123", NOW);
        var code = normalizer.normalizeCode(snapshot, List.of(new CodeFinding(
                "finding-1", "null-check", "possible null dereference", "src/Main.java",
                "sha256:" + "b".repeat(64), List.of(new ArtifactId(uuid(11))))), CONTEXT);
        var knowledge = normalizer.normalizeKnowledge(List.of(new KnowledgeResult(
                "result-1", "kb-1", "rev-1", "runbook match", List.of(new ArtifactId(uuid(12))))), CONTEXT);

        assertEquals("CODE_FINDING", code.evidence().getFirst().provenanceRefs().getFirst().kind());
        assertEquals("KNOWLEDGE_RESULT", knowledge.evidence().getFirst().provenanceRefs().getFirst().kind());
        assertThrows(UnsupportedOperationException.class,
                () -> code.evidence().add(knowledge.evidence().getFirst()));
        assertEquals(EvidenceNormalizer.class,
                RuntimeEvidenceNormalizer.class.getInterfaces()[0].getDeclaredMethods()[0].getDeclaringClass());
    }

    @Test
    void diagnosisHypothesisAndRcaAcceptOnlyEvidenceIdsAtCompileAndSchemaBoundaries() {
        EvidenceId evidenceId = new EvidenceId(uuid(20));
        assertEquals(List.of(evidenceId), new EvidenceOnlyAnalysis.DiagnosisInput(List.of(evidenceId)).evidenceIds());
        assertEquals(List.of(evidenceId), new EvidenceOnlyAnalysis.HypothesisInput(List.of(evidenceId)).evidenceIds());
        assertEquals(List.of(evidenceId), new EvidenceOnlyAnalysis.RcaInput(List.of(evidenceId)).evidenceIds());
        assertEquals(evidenceId, EvidenceOnlyAnalysis.parseDiagnosis(Map.of(
                "evidenceIds", List.of(evidenceId.wire()))).evidenceIds().getFirst());
        for (String rawBoundary : List.of("observationBatch", "codeFinding", "knowledgeResult", "vendorDto")) {
            assertThrows(IllegalArgumentException.class,
                    () -> EvidenceOnlyAnalysis.parseDiagnosis(Map.of(rawBoundary, Map.of())));
        }
    }

    @Test
    void largeToolBodyIsStoredThroughArtifactPortAndNeverReturnedInline() {
        CapturingArtifactPort artifacts = new CapturingArtifactPort();
        ToolResultNormalizer normalizer = new ToolResultNormalizer(artifacts, 32);
        String raw = "secret=live-value " + "x".repeat(200);
        var result = normalizer.normalize(new RunId(uuid(2)), ToolStatus.SUCCEEDED, raw, null);

        assertEquals(ToolStatus.SUCCEEDED, result.status());
        assertEquals(32, result.summary().length());
        assertFalse(result.summary().contains("live-value"));
        assertEquals(1, result.artifactIds().size());
        assertTrue(artifacts.body.length > 32);
    }

    @Test
    void fiveDescriptorsAreExplicitAndNoGenericDiscoveryRegistryExists() throws IOException {
        long descriptorInterfaces = List.of(ExtensionContracts.class.getDeclaredClasses()).stream()
                .filter(Class::isInterface).count();
        assertEquals(5, descriptorInterfaces);

        Path root = Path.of(System.getProperty("basedir"), "src", "main", "java");
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String java = Files.readString(file);
                assertFalse(java.contains("ServiceLoader") || java.contains("Class.forName")
                        || file.getFileName().toString().equals("ExtensionRegistry.java"), file.toString());
            }
        }
    }

    private static String allPortTypeNames() {
        return String.join(" ", ChatPort.class.getName(), EmbeddingPort.class.getName(), RerankPort.class.getName())
                .toLowerCase();
    }

    private static UUID uuid(long suffix) {
        return UUID.fromString("00000000-0000-4000-8000-%012d".formatted(suffix));
    }

    /** Test-only Artifact Port contract probe; it is not registered or used as a production fallback. */
    private static final class CapturingArtifactPort implements ArtifactPort {
        private byte[] body;

        @Override
        public ArtifactId store(RunId runId, String mediaType, byte[] content) {
            body = content.clone();
            return new ArtifactId(uuid(30));
        }
    }
}
