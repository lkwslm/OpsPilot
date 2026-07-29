package io.github.opspilot.server;

import io.github.opspilot.core.domain.state.StateMachines.AgentEndpointState;
import io.github.opspilot.core.domain.state.StateMachines.EndpointReasonCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AgentDirectoryTest {
    @TempDir
    Path temporary;

    @Test
    void loadsImmutableContractAndResolvesOnlyFreshReadyEndpoint() throws Exception {
        Path path = writeDirectory(temporary, directoryYaml());
        AgentDirectory directory = AgentDirectory.load(temporary, path);
        Instant now = Instant.parse("2026-07-18T10:00:00Z");
        byte[] card = Phase0Process.cardJson(
                "evidence-collector", "collect-observability-evidence")
                .getBytes(StandardCharsets.UTF_8);

        AgentDirectory.EndpointSnapshot snapshot = directory.probe(
                "evidence-collector", ignored -> card, now);

        assertEquals("1.0", directory.schemaVersion());
        assertEquals(5, directory.entries().size());
        assertEquals(64, directory.digest().length());
        assertEquals(AgentEndpointState.READY, snapshot.state());
        assertEquals(directory.entry("evidence-collector").cardUrl(), directory.resolve(
                "evidence-collector", directory.entry("evidence-collector").cardUrl(),
                snapshot, now.plusSeconds(10), Duration.ofMinutes(1)));
        assertThrows(IllegalStateException.class, () -> directory.resolve(
                "evidence-collector", directory.entry("evidence-collector").cardUrl(),
                snapshot, now.plusSeconds(61), Duration.ofMinutes(1)));
    }

    @Test
    void unknownEndpointAndCardDriftAreRejectedBeforeSelection() throws Exception {
        AgentDirectory directory = AgentDirectory.load(
                temporary, writeDirectory(temporary, directoryYaml()));
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IllegalArgumentException.class,
                () -> directory.probe("model-supplied-agent", ignored -> {
                    calls.incrementAndGet();
                    return new byte[0];
                }, Instant.now()));
        assertEquals(0, calls.get());

        AgentDirectory.EndpointSnapshot drifted = directory.probe("evidence-collector",
                ignored -> "{\"name\":\"attacker\"}".getBytes(StandardCharsets.UTF_8),
                Instant.now());
        assertEquals(AgentEndpointState.UNAVAILABLE, drifted.state());
        assertEquals(EndpointReasonCode.CARD_INVALID, drifted.reasonCode());
        assertThrows(IllegalStateException.class, () -> directory.resolve(
                "evidence-collector", directory.entry("evidence-collector").cardUrl(),
                drifted, Instant.now(), Duration.ofMinutes(1)));
    }

    @Test
    void pathInjectionAndPostStartupMutationFailClosed() throws Exception {
        Path path = writeDirectory(temporary, directoryYaml());
        AgentDirectory directory = AgentDirectory.load(temporary, path);
        Files.writeString(path, directoryYaml().replace("HTTP+JSON", "HTTP+JSON-CHANGED"));
        assertThrows(IllegalStateException.class, directory::verifyUnchanged);

        Path outside = Files.createTempDirectory("outside-directory")
                .resolve("agent-directory.yaml");
        Files.writeString(outside, directoryYaml());
        assertThrows(IllegalArgumentException.class,
                () -> AgentDirectory.load(temporary, outside));
        assertThrows(IllegalArgumentException.class, () -> AgentDirectory.load(temporary,
                writeDirectory(temporary, directoryYaml().replace(
                        "http://evidence-agent:8081", "http://unknown-agent:9999"))));
    }

    @Test
    void dedicatedEntrypointsRejectTheOtherProcessProfile() {
        ProductServerProcess.requireProfile("supervisor");
        ProfessionalAgentProcess.requireProfile("diagnosis");
        assertThrows(IllegalStateException.class,
                () -> ProductServerProcess.requireProfile("diagnosis"));
        assertThrows(IllegalStateException.class,
                () -> ProfessionalAgentProcess.requireProfile("supervisor"));
        assertTrue(Phase0Process.cardJson("supervisor", "supervise-incident")
                .contains("\"name\":\"supervisor\""));
        assertTrue(!Phase0Process.cardJson("supervisor", "supervise-incident")
                .contains("evidence-collector"));
    }

    private static Path writeDirectory(Path root, String content) throws Exception {
        Path path = root.resolve("agent-directory.yaml");
        Files.writeString(path, content);
        return path;
    }

    private static String directoryYaml() {
        return """
                schema_version: "1.0"
                agents:
                  - id: evidence-collector
                    card_url: http://evidence-agent:8081/.well-known/agent-card.json
                    expected_skill: collect-observability-evidence
                    expected_protocol: "1.0"
                    expected_binding: HTTP+JSON
                    service_identity: svc:evidence-agent
                    token_ref: file:/run/secrets/evidence-agent-token
                    expected_card_sha256: 15d9af26921e06ae3b1a5c632523dad7cd478e9e8986aced22c3e2505626b2a3
                  - id: code-analysis
                    card_url: http://code-agent:8082/.well-known/agent-card.json
                    expected_skill: analyze-code-location
                    expected_protocol: "1.0"
                    expected_binding: HTTP+JSON
                    service_identity: svc:code-agent
                    token_ref: file:/run/secrets/code-agent-token
                    expected_card_sha256: be069ac25582f333abf2e1eebe1dd2cd1a4b77f9ed74890a2586195fdbb6a45d
                  - id: knowledge
                    card_url: http://knowledge-agent:8083/.well-known/agent-card.json
                    expected_skill: retrieve-incident-knowledge
                    expected_protocol: "1.0"
                    expected_binding: HTTP+JSON
                    service_identity: svc:knowledge-agent
                    token_ref: file:/run/secrets/knowledge-agent-token
                    expected_card_sha256: 9ef5c244fcf85575c5ca95e36418123d8a6bcb201586a2bd7d9e446f0da34f21
                  - id: diagnosis
                    card_url: http://diagnosis-agent:8084/.well-known/agent-card.json
                    expected_skill: generate-and-verify-hypotheses
                    expected_protocol: "1.0"
                    expected_binding: HTTP+JSON
                    service_identity: svc:diagnosis-agent
                    token_ref: file:/run/secrets/diagnosis-agent-token
                    expected_card_sha256: 6a0e5e2880de8bbf264a6da09c72e69d07a15c12bea0202c12600d6d71a66f2b
                  - id: remediation
                    card_url: http://remediation-agent:8085/.well-known/agent-card.json
                    expected_skill: propose-remediation
                    expected_protocol: "1.0"
                    expected_binding: HTTP+JSON
                    service_identity: svc:remediation-agent
                    token_ref: file:/run/secrets/remediation-agent-token
                    expected_card_sha256: 428671c47103c90d4f18bd86da4e9aba04a04e6b910d6e0794c97645934e027a
                """;
    }
}
