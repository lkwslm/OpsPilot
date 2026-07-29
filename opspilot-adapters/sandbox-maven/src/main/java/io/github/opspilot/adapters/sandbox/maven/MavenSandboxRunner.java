package io.github.opspilot.adapters.sandbox.maven;

import io.github.opspilot.core.port.sandbox.SandboxRunnerPort;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Executes only an approved suite through an injected isolation-enforcing backend. */
public final class MavenSandboxRunner implements SandboxRunnerPort {
    private final Set<String> allowedSuiteIds;
    private final Set<String> allowedWritableRoots;
    private final SandboxLimits maximum;
    private final IsolatedMavenBackend backend;
    private final Clock clock;

    public MavenSandboxRunner(
            Set<String> allowedSuiteIds, Set<String> allowedWritableRoots,
            SandboxLimits maximum, IsolatedMavenBackend backend, Clock clock) {
        this.allowedSuiteIds = Set.copyOf(allowedSuiteIds);
        this.allowedWritableRoots = Set.copyOf(allowedWritableRoots);
        this.maximum = Objects.requireNonNull(maximum, "maximum");
        this.backend = Objects.requireNonNull(backend, "backend");
        this.clock = Objects.requireNonNull(clock, "clock");
        IsolationAttestation attestation = backend.attestation();
        if (!attestation.networkDenied() || !attestation.sourceReadOnly()) {
            throw new IllegalArgumentException("SANDBOX_BACKEND_NOT_ISOLATED");
        }
    }

    @Override
    public SandboxResult run(SandboxRequest request) {
        String denied = deniedReason(request);
        if (denied != null) return new SandboxResult(false, "Sandbox request denied", List.of(), denied);
        ExecutionSpec spec = new ExecutionSpec(request.runId().value().toString(), request.testSuiteId(),
                List.of("test", "-DtestSuiteId=" + request.testSuiteId()), request.writableRoots(),
                request.limits(), request.deadline(), true, true);
        try {
            BackendResult result = backend.execute(spec, request.cancellation());
            if (request.cancellation().cancelled()) return rejected("SANDBOX_CANCELLED");
            if (!request.deadline().isAfter(clock.instant())) return rejected("SANDBOX_TIMEOUT");
            return new SandboxResult(result.exitCode() == 0, result.redactedSummary(),
                    result.artifactIds(), result.exitCode() == 0 ? null : "SANDBOX_TEST_FAILED");
        } catch (RuntimeException failure) {
            return rejected("SANDBOX_EXECUTION_FAILED");
        }
    }

    private String deniedReason(SandboxRequest request) {
        if (!request.approvalValid() || request.approvalId() == null) return "SANDBOX_APPROVAL_REQUIRED";
        if (request.highRisk()) return "HIGH_RISK_DENIED";
        if (request.codeMutation()) return "CODE_MUTATION_DENIED";
        if (request.arbitraryShellRequested()) return "ARBITRARY_COMMAND_DENIED";
        if (request.networkRequested()) return "SANDBOX_NETWORK_DENIED";
        if (!allowedSuiteIds.contains(request.testSuiteId())) return "SANDBOX_SUITE_DENIED";
        if (!allowedWritableRoots.containsAll(request.writableRoots())) return "SANDBOX_WRITE_SCOPE_DENIED";
        if (request.limits().maxDurationSeconds() > maximum.maxDurationSeconds()
                || request.limits().maxCpu() > maximum.maxCpu()
                || request.limits().maxMemoryMb() > maximum.maxMemoryMb()) return "SANDBOX_LIMIT_EXPANSION_DENIED";
        if (request.cancellation().cancelled()) return "SANDBOX_CANCELLED";
        if (!request.deadline().isAfter(clock.instant())) return "SANDBOX_TIMEOUT";
        return null;
    }

    private static SandboxResult rejected(String code) {
        return new SandboxResult(false, "Sandbox did not complete", List.of(), code);
    }

    public record ExecutionSpec(
            String runId, String testSuiteId, List<String> mavenArguments,
            Set<String> writableRoots, SandboxLimits limits, java.time.Instant deadline,
            boolean networkDenied, boolean sourceReadOnly) {
        public ExecutionSpec {
            mavenArguments = List.copyOf(mavenArguments);
            writableRoots = Set.copyOf(writableRoots);
        }
    }

    public record IsolationAttestation(boolean networkDenied, boolean sourceReadOnly) { }
    public record BackendResult(int exitCode, String redactedSummary,
                                List<io.github.opspilot.core.domain.identity.DomainIds.ArtifactId> artifactIds) {
        public BackendResult { artifactIds = List.copyOf(artifactIds); }
    }

    public interface IsolatedMavenBackend {
        IsolationAttestation attestation();
        BackendResult execute(ExecutionSpec spec, CancellationToken cancellation);
    }
}
