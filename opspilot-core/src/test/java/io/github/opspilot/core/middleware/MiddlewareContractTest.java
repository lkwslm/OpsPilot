package io.github.opspilot.core.middleware;

import io.github.opspilot.core.application.security.SecureInvocation;
import io.github.opspilot.core.application.security.SecureInvocation.AuditRecord;
import io.github.opspilot.core.application.security.SecureInvocation.Budget;
import io.github.opspilot.core.application.security.SecureInvocation.InvocationRequest;
import io.github.opspilot.core.application.security.SecureInvocation.NormalizedResult;
import io.github.opspilot.core.application.security.SecureInvocation.Outcome;
import io.github.opspilot.core.application.security.SecureInvocation.RawPortResult;
import io.github.opspilot.core.application.security.SecureInvocation.Risk;
import io.github.opspilot.core.application.security.SecureInvocation.SecurityStepFailure;
import io.github.opspilot.core.application.security.SecureInvocation.Stage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiddlewareContractTest {
    private static final Instant NOW = Instant.parse("2026-07-18T12:00:00Z");

    @Test
    void successfulInvocationUsesTheExactFrozenOrder() {
        Fixture fixture = new Fixture();
        var result = fixture.chain().invoke(request(Risk.READ_ONLY, "fingerprint-1"), input -> {
            fixture.portCalls.incrementAndGet();
            return new RawPortResult("bounded result", true);
        });

        assertEquals(Outcome.SUCCEEDED, result.outcome());
        assertEquals(SecureInvocation.FROZEN_ORDER, fixture.stages);
        List<Stage> deliberatelyReordered = new ArrayList<>(SecureInvocation.FROZEN_ORDER);
        java.util.Collections.swap(deliberatelyReordered, 0, 1);
        assertNotEquals(SecureInvocation.FROZEN_ORDER, deliberatelyReordered);
    }

    @Test
    void everyPreconditionAndDuplicateShortCircuitsBeforePortExecution() {
        for (String failingStage : List.of("schema", "authorize", "approval", "budget")) {
            Fixture fixture = new Fixture();
            fixture.failingStage = failingStage;
            var result = fixture.chain().invoke(request(Risk.CONTROLLED_EXECUTION, failingStage), input -> {
                fixture.portCalls.incrementAndGet();
                return new RawPortResult("must not execute", true);
            });
            assertNotEquals(Outcome.SUCCEEDED, result.outcome(), failingStage);
            assertEquals(0, fixture.portCalls.get(), failingStage);
            assertEquals(1, fixture.audit.size(), failingStage);
        }

        Fixture duplicate = new Fixture();
        SecureInvocation chain = duplicate.chain();
        chain.invoke(request(Risk.READ_ONLY, "same"), input -> {
            duplicate.portCalls.incrementAndGet();
            return new RawPortResult("first", true);
        });
        var second = chain.invoke(request(Risk.READ_ONLY, "same"), input -> {
            duplicate.portCalls.incrementAndGet();
            return new RawPortResult("second", true);
        });
        assertEquals("DUPLICATE_INVOCATION", second.errorCode());
        assertEquals(1, duplicate.portCalls.get());

        Fixture invalidResult = new Fixture();
        var invalid = invalidResult.chain().invoke(request(Risk.READ_ONLY, "invalid-result"), input -> {
            invalidResult.portCalls.incrementAndGet();
            return new RawPortResult("unvalidated", false);
        });
        assertEquals(Outcome.FAILED, invalid.outcome());
        assertEquals(1, invalidResult.portCalls.get());
        assertNotEquals(Outcome.SUCCEEDED, invalidResult.audit.getFirst().outcome());
    }

    @Test
    void permissionIsFourWayIntersectionAndHighRiskIsAlwaysDenied() {
        Fixture fixture = new Fixture();
        fixture.chain().invoke(request(Risk.READ_ONLY, "intersection"),
                input -> new RawPortResult("ok", true));
        assertEquals(Set.of("read"), fixture.audit.getFirst().effectivePermissions());

        Fixture highRisk = new Fixture();
        var result = highRisk.chain().invoke(request(Risk.HIGH_RISK, "high-risk"), input -> {
            highRisk.portCalls.incrementAndGet();
            return new RawPortResult("must not execute", true);
        });
        assertEquals(Outcome.DENIED, result.outcome());
        assertEquals("HIGH_RISK_DENIED", result.errorCode());
        assertEquals(0, highRisk.portCalls.get());
    }

    @Test
    void successFailureDenialAndCancellationAuditNeverLeakRawContent() {
        for (String path : List.of("success", "failure", "denial", "cancel")) {
            Fixture fixture = new Fixture();
            if (path.equals("denial")) {
                fixture.failingStage = "approval";
            }
            fixture.chain().invoke(request(
                    path.equals("denial") ? Risk.CONTROLLED_EXECUTION : Risk.READ_ONLY, path), input -> {
                if (path.equals("failure")) {
                    throw new IllegalStateException("secret=live prompt: raw response body");
                }
                if (path.equals("cancel")) {
                    throw new CancellationException("secret=cancel-token");
                }
                return new RawPortResult("secret=live raw response body", true);
            });
            String audit = fixture.audit.getFirst().toString().toLowerCase();
            assertFalse(audit.contains("secret=live") || audit.contains("prompt:")
                    || audit.contains("raw response body") || audit.contains("cancel-token"), path);
            assertTrue(audit.contains(path.equals("failure") ? "secure_invocation_failed"
                    : path.equals("cancel") ? "invocation_cancelled" : path.equals("denial")
                    ? "approval_invalid" : "redacted"), path);
        }
    }

    @Test
    void observerFailureIsRecordedButAuthorizationExceptionFailsClosed() {
        Fixture observerFailure = new Fixture();
        observerFailure.observerFails = true;
        var success = observerFailure.chain().invoke(request(Risk.READ_ONLY, "observer"),
                input -> new RawPortResult("ok", true));
        assertEquals(Outcome.SUCCEEDED, success.outcome());
        assertFalse(observerFailure.observerErrors.isEmpty());

        Fixture authorizationFailure = new Fixture();
        authorizationFailure.failingStage = "authorize";
        var denied = authorizationFailure.chain().invoke(request(Risk.READ_ONLY, "auth-exception"), input -> {
            authorizationFailure.portCalls.incrementAndGet();
            return new RawPortResult("must not execute", true);
        });
        assertEquals(Outcome.FAILED, denied.outcome());
        assertEquals(0, authorizationFailure.portCalls.get());
    }

    private static InvocationRequest request(Risk risk, String fingerprint) {
        return new InvocationRequest(
                "subject-1", "agent-1", Set.of("read", "execute"), Set.of("read", "execute"),
                Set.of("read", "execute"), Set.of("read"), "read", risk, "approval-1",
                new Budget(100, 1_000, NOW.plusSeconds(30)), fingerprint, "schema-1",
                "secret=input", NOW);
    }

    private static final class Fixture {
        private final List<Stage> stages = new ArrayList<>();
        private final List<AuditRecord> audit = new ArrayList<>();
        private final List<String> observerErrors = new ArrayList<>();
        private final AtomicInteger portCalls = new AtomicInteger();
        private final Set<String> claimed = new HashSet<>();
        private String failingStage;
        private boolean observerFails;

        private SecureInvocation chain() {
            return new SecureInvocation(
                    (schema, input) -> fail("schema", "SCHEMA_INVALID"),
                    (request, permissions) -> fail("authorize", "AUTHORIZER_CRASHED"),
                    (approval, fingerprint) -> fail("approval", "APPROVAL_INVALID"),
                    (budget, now) -> fail("budget", "BUDGET_EXHAUSTED"),
                    claimed::add,
                    raw -> {
                        if (!raw.valid()) {
                            throw new IllegalArgumentException("RESULT_INVALID");
                        }
                        return new NormalizedResult(raw.rawBody().replaceAll(
                                "(?i)(secret|password|token)=[^\\s]+", "$1=[REDACTED]")
                                .replace("raw response body", "bounded body"));
                    },
                    audit::add,
                    (stage, fingerprint) -> {
                        stages.add(stage);
                        if (observerFails) {
                            throw new IllegalStateException("observer unavailable");
                        }
                    },
                    (stage, error) -> observerErrors.add(stage + ":" + error));
        }

        private void fail(String stage, String code) {
            if (stage.equals(failingStage)) {
                if (stage.equals("authorize")) {
                    throw new IllegalStateException(code);
                }
                throw new SecurityStepFailure(code);
            }
        }
    }
}
