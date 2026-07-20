package io.github.opspilot.core.application.security;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;

/** Fixed, non-configurable security chain for every controlled Port invocation. */
public final class SecureInvocation {
    public static final List<Stage> FROZEN_ORDER = List.of(
            Stage.VALIDATE_SCHEMA,
            Stage.AUTHORIZE,
            Stage.REQUIRE_APPROVAL,
            Stage.ENFORCE_BUDGET_DEADLINE,
            Stage.EXECUTE_PORT,
            Stage.NORMALIZE_REDACT,
            Stage.AUDIT);

    private final SchemaValidator schema;
    private final AuthorizationValidator authorization;
    private final ApprovalValidator approvals;
    private final BudgetValidator budget;
    private final ReplayGuard replay;
    private final ResultNormalizer normalizer;
    private final AuditSink audit;
    private final Observer observer;
    private final ObserverFailureSink observerFailures;

    public SecureInvocation(
            SchemaValidator schema,
            AuthorizationValidator authorization,
            ApprovalValidator approvals,
            BudgetValidator budget,
            ReplayGuard replay,
            ResultNormalizer normalizer,
            AuditSink audit,
            Observer observer,
            ObserverFailureSink observerFailures) {
        this.schema = Objects.requireNonNull(schema, "schema");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.approvals = Objects.requireNonNull(approvals, "approvals");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.replay = Objects.requireNonNull(replay, "replay");
        this.normalizer = Objects.requireNonNull(normalizer, "normalizer");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.observerFailures = Objects.requireNonNull(observerFailures, "observerFailures");
    }

    public InvocationResult invoke(InvocationRequest request, ControlledPort port) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(port, "port");
        Set<String> effectivePermissions = Set.of();
        InvocationResult result;
        try {
            enter(Stage.VALIDATE_SCHEMA, request);
            schema.validate(request.schemaId(), request.input());

            enter(Stage.AUTHORIZE, request);
            effectivePermissions = intersection(request.subjectPermissions(), request.agentPermissions(),
                    request.toolPermissions(), request.contextPermissions());
            authorization.authorize(request, effectivePermissions);
            if (request.risk() == Risk.HIGH_RISK) {
                throw rejected("HIGH_RISK_DENIED", "high-risk actions are disabled in MVP");
            }
            if (!effectivePermissions.contains(request.requiredPermission())) {
                throw rejected("AUTHORIZATION_DENIED", "required permission is not in the effective intersection");
            }

            enter(Stage.REQUIRE_APPROVAL, request);
            if (request.risk() == Risk.CONTROLLED_EXECUTION) {
                approvals.requireValid(request.approvalId(), request.actionFingerprint());
            }

            enter(Stage.ENFORCE_BUDGET_DEADLINE, request);
            budget.enforce(request.budget(), request.now());
            if (!replay.claim(request.actionFingerprint())) {
                throw rejected("DUPLICATE_INVOCATION", "action fingerprint has already been claimed");
            }

            enter(Stage.EXECUTE_PORT, request);
            RawPortResult raw = port.execute(request.input());

            enter(Stage.NORMALIZE_REDACT, request);
            NormalizedResult normalized = normalizer.normalize(raw);
            result = new InvocationResult(Outcome.SUCCEEDED, normalized.summary(), null);
        } catch (CancellationException cancelled) {
            result = new InvocationResult(Outcome.CANCELLED, "invocation cancelled", "INVOCATION_CANCELLED");
        } catch (SecurityStepFailure denied) {
            result = new InvocationResult(Outcome.DENIED, "controlled invocation denied", denied.errorCode());
        } catch (InvocationRejected rejected) {
            result = new InvocationResult(Outcome.DENIED, rejected.getMessage(), rejected.errorCode());
        } catch (RuntimeException failure) {
            result = new InvocationResult(Outcome.FAILED, "controlled invocation failed", "SECURE_INVOCATION_FAILED");
        }

        enter(Stage.AUDIT, request);
        AuditRecord record = new AuditRecord(
                request.subjectId(), request.agentId(), request.actionFingerprint(), effectivePermissions,
                request.approvalId(), request.budget(), result.outcome(), sanitizeAudit(result.summary()), result.errorCode());
        try {
            audit.record(record);
        } catch (RuntimeException auditFailure) {
            return new InvocationResult(Outcome.FAILED, "audit persistence failed", "AUDIT_FAILED");
        }
        return result;
    }

    private void enter(Stage stage, InvocationRequest request) {
        try {
            observer.onStage(stage, request.actionFingerprint());
        } catch (RuntimeException observerFailure) {
            observerFailures.record(stage, "OBSERVER_FAILED");
        }
    }

    @SafeVarargs
    private static Set<String> intersection(Set<String> first, Set<String>... rest) {
        Set<String> effective = new java.util.HashSet<>(first);
        for (Set<String> permissions : rest) {
            effective.retainAll(permissions);
        }
        return Set.copyOf(effective);
    }

    private static InvocationRejected rejected(String code, String message) {
        return new InvocationRejected(code, message);
    }

    private static String sanitizeAudit(String value) {
        if (value == null) {
            return null;
        }
        return value.replaceAll("(?i)(secret|password|token)=[^\\s]+", "[REDACTED]")
                .replaceAll("(?i)prompt:", "[REDACTED]:")
                .replaceAll("(?i)raw response body", "bounded body");
    }

    public enum Stage {
        VALIDATE_SCHEMA, AUTHORIZE, REQUIRE_APPROVAL, ENFORCE_BUDGET_DEADLINE,
        EXECUTE_PORT, NORMALIZE_REDACT, AUDIT
    }

    public enum Risk { READ_ONLY, CONTROLLED_EXECUTION, HIGH_RISK }

    public enum Outcome { SUCCEEDED, DENIED, FAILED, CANCELLED }

    public record Budget(long remainingTokens, long remainingCostMicros, Instant deadline) {
    }

    public record InvocationRequest(
            String subjectId,
            String agentId,
            Set<String> subjectPermissions,
            Set<String> agentPermissions,
            Set<String> toolPermissions,
            Set<String> contextPermissions,
            String requiredPermission,
            Risk risk,
            String approvalId,
            Budget budget,
            String actionFingerprint,
            String schemaId,
            Object input,
            Instant now) {
        public InvocationRequest {
            subjectPermissions = Set.copyOf(subjectPermissions);
            agentPermissions = Set.copyOf(agentPermissions);
            toolPermissions = Set.copyOf(toolPermissions);
            contextPermissions = Set.copyOf(contextPermissions);
        }
    }

    public record RawPortResult(String rawBody, boolean valid) {
    }

    public record NormalizedResult(String summary) {
        public NormalizedResult {
            if (summary.length() > 512) {
                throw new IllegalArgumentException("normalized summary is too large");
            }
        }
    }

    public record InvocationResult(Outcome outcome, String summary, String errorCode) {
    }

    public record AuditRecord(
            String subjectId,
            String agentId,
            String actionFingerprint,
            Set<String> effectivePermissions,
            String approvalId,
            Budget budget,
            Outcome outcome,
            String summary,
            String errorCode) {
        public AuditRecord { effectivePermissions = Set.copyOf(effectivePermissions); }
    }

    @FunctionalInterface public interface SchemaValidator { void validate(String schemaId, Object input); }
    @FunctionalInterface public interface AuthorizationValidator {
        void authorize(InvocationRequest request, Set<String> effectivePermissions);
    }
    @FunctionalInterface public interface ApprovalValidator { void requireValid(String approvalId, String fingerprint); }
    @FunctionalInterface public interface BudgetValidator { void enforce(Budget budget, Instant now); }
    @FunctionalInterface public interface ReplayGuard { boolean claim(String actionFingerprint); }
    @FunctionalInterface public interface ControlledPort { RawPortResult execute(Object input); }
    @FunctionalInterface public interface ResultNormalizer { NormalizedResult normalize(RawPortResult raw); }
    @FunctionalInterface public interface AuditSink { void record(AuditRecord record); }
    @FunctionalInterface public interface Observer { void onStage(Stage stage, String actionFingerprint); }
    @FunctionalInterface public interface ObserverFailureSink { void record(Stage stage, String errorCode); }

    public static final class SecurityStepFailure extends RuntimeException {
        private final String errorCode;

        public SecurityStepFailure(String errorCode) {
            super(errorCode);
            this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
        }

        public String errorCode() { return errorCode; }
    }

    private static final class InvocationRejected extends RuntimeException {
        private final String errorCode;

        private InvocationRejected(String errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        private String errorCode() { return errorCode; }
    }
}
