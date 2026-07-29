package io.github.opspilot.adapters.sandbox.maven;

import io.github.opspilot.core.domain.identity.DomainIds.ArtifactId;
import io.github.opspilot.core.domain.identity.DomainIds.RunId;
import io.github.opspilot.core.port.sandbox.SandboxRunnerPort.SandboxLimits;
import io.github.opspilot.core.port.sandbox.SandboxRunnerPort.SandboxRequest;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MavenSandboxRunnerTest {
    private static final Instant NOW = Instant.parse("2026-07-28T10:00:00Z");
    private static final SandboxLimits LIMITS = new SandboxLimits(300, 2, 2048);

    @Test
    void approvedAllowlistedSuiteUsesFixedMavenArgumentsAndIsolationLimits() {
        AtomicInteger executions = new AtomicInteger();
        ArtifactId output = new ArtifactId(UUID.randomUUID());
        MavenSandboxRunner runner = runner(executions, spec -> {
            assertEquals(List.of("test", "-DtestSuiteId=opspilot-regression-v1"), spec.mavenArguments());
            assertEquals(Set.of("/workspace/target"), spec.writableRoots());
            assertTrue(spec.networkDenied());
            assertTrue(spec.sourceReadOnly());
            return new MavenSandboxRunner.BackendResult(0, "tests passed", List.of(output));
        });

        var result = runner.run(request(false, false, false, false, true, false, LIMITS));
        assertTrue(result.accepted());
        assertEquals(List.of(output), result.artifactIds());
        assertEquals(1, executions.get());
    }

    @Test
    void highRiskShellMutationNetworkApprovalTimeoutCancellationAndLimitExpansionNeverStartBackend() {
        AtomicInteger executions = new AtomicInteger();
        MavenSandboxRunner runner = runner(executions,
                spec -> new MavenSandboxRunner.BackendResult(0, "unexpected", List.of()));

        List<SandboxRequest> denied = List.of(
                request(true, false, false, false, true, false, LIMITS),
                request(false, true, false, false, true, false, LIMITS),
                request(false, false, true, false, true, false, LIMITS),
                request(false, false, false, true, true, false, LIMITS),
                request(false, false, false, false, false, false, LIMITS),
                request(false, false, false, false, true, true, LIMITS),
                new SandboxRequest(run(), artifact(), "opspilot-regression-v1", UUID.randomUUID(), true,
                        false, false, false, false, Set.of("/workspace/target"), LIMITS, NOW, () -> false),
                request(false, false, false, false, true, false, new SandboxLimits(301, 2, 2048)),
                new SandboxRequest(run(), artifact(), "unknown-suite", UUID.randomUUID(), true,
                        false, false, false, false, Set.of("/workspace/target"), LIMITS,
                        NOW.plusSeconds(10), () -> false));
        denied.forEach(request -> assertFalse(runner.run(request).accepted()));
        assertEquals(0, executions.get());
    }

    @Test
    void backendWithoutNetworkAndSourceIsolationIsRejectedAtComposition() {
        assertThrows(IllegalArgumentException.class, () -> new MavenSandboxRunner(
                Set.of("suite"), Set.of("/workspace/target"), LIMITS,
                new MavenSandboxRunner.IsolatedMavenBackend() {
                    @Override public MavenSandboxRunner.IsolationAttestation attestation() {
                        return new MavenSandboxRunner.IsolationAttestation(false, true);
                    }
                    @Override public MavenSandboxRunner.BackendResult execute(
                            MavenSandboxRunner.ExecutionSpec spec,
                            io.github.opspilot.core.port.sandbox.SandboxRunnerPort.CancellationToken cancellation) {
                        throw new AssertionError();
                    }
                }, Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    @Test
    void registryRejectsDuplicateMissingVersionAndMutationAfterFreeze() {
        MavenSandboxRunner runner = runner(new AtomicInteger(),
                spec -> new MavenSandboxRunner.BackendResult(0, "ok", List.of()));
        MavenSandboxRegistry duplicate = new MavenSandboxRegistry();
        duplicate.register("maven-sandbox-v1", "1.0.0", runner);
        assertEquals("DUPLICATE_SANDBOX_ID", assertThrows(MavenSandboxRegistry.RegistryException.class,
                () -> duplicate.register("maven-sandbox-v1", "1.0.0", runner)).code());

        MavenSandboxRegistry incompatible = new MavenSandboxRegistry();
        incompatible.register("maven-sandbox-v1", "1.0.0", runner);
        assertEquals("SANDBOX_VERSION_INCOMPATIBLE", assertThrows(MavenSandboxRegistry.RegistryException.class,
                () -> incompatible.freeze(java.util.Map.of("maven-sandbox-v1", "2.0.0"))).code());

        MavenSandboxRegistry frozen = new MavenSandboxRegistry();
        frozen.register("maven-sandbox-v1", "1.0.0", runner);
        frozen.freeze(java.util.Map.of("maven-sandbox-v1", "1.0.0"));
        assertEquals(runner, frozen.require("maven-sandbox-v1", "1.0.0"));
        assertEquals("SANDBOX_REGISTRY_FROZEN", assertThrows(MavenSandboxRegistry.RegistryException.class,
                () -> frozen.register("other", "1.0.0", runner)).code());
    }

    private static MavenSandboxRunner runner(AtomicInteger executions, Backend backend) {
        return new MavenSandboxRunner(Set.of("opspilot-regression-v1"), Set.of("/workspace/target"), LIMITS,
                new MavenSandboxRunner.IsolatedMavenBackend() {
                    @Override public MavenSandboxRunner.IsolationAttestation attestation() {
                        return new MavenSandboxRunner.IsolationAttestation(true, true);
                    }
                    @Override public MavenSandboxRunner.BackendResult execute(
                            MavenSandboxRunner.ExecutionSpec spec,
                            io.github.opspilot.core.port.sandbox.SandboxRunnerPort.CancellationToken cancellation) {
                        executions.incrementAndGet();
                        return backend.execute(spec);
                    }
                }, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static SandboxRequest request(
            boolean highRisk, boolean mutation, boolean shell, boolean network,
            boolean approval, boolean cancelled, SandboxLimits limits) {
        return new SandboxRequest(run(), artifact(), "opspilot-regression-v1", UUID.randomUUID(), approval,
                highRisk, mutation, shell, network, Set.of("/workspace/target"), limits,
                NOW.plusSeconds(10), () -> cancelled);
    }

    private static RunId run() { return new RunId(UUID.randomUUID()); }
    private static ArtifactId artifact() { return new ArtifactId(UUID.randomUUID()); }
    @FunctionalInterface private interface Backend {
        MavenSandboxRunner.BackendResult execute(MavenSandboxRunner.ExecutionSpec spec);
    }
}
