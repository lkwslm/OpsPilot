package io.github.opspilot.core.domain;

import io.github.opspilot.core.domain.failure.ChainFailure;
import io.github.opspilot.core.domain.identity.DomainIds;
import io.github.opspilot.core.domain.value.DomainValues;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DomainIdentityErrorContractTest {
    private static final String UUID_TEXT = "8eb04f9a-723b-4de6-9f63-c67e1121c256";

    @Test
    void identitiesExposeOnlyCanonicalUuidStrings() {
        assertEquals(UUID_TEXT, DomainIds.IncidentId.parse(UUID_TEXT).wire());
        assertEquals(UUID_TEXT, DomainIds.RunId.parse(UUID_TEXT).wire());
        assertEquals(UUID_TEXT, DomainIds.StepId.parse(UUID_TEXT).wire());
        assertEquals(UUID_TEXT, DomainIds.A2aTaskId.parse(UUID_TEXT).wire());
        assertEquals(UUID_TEXT, DomainIds.EvidenceId.parse(UUID_TEXT).wire());
        assertEquals(UUID_TEXT, DomainIds.ArtifactId.parse(UUID_TEXT).wire());
        assertEquals(UUID_TEXT, DomainIds.HypothesisId.parse(UUID_TEXT).wire());
        for (String invalid : List.of("1", "step-title", "0", "8eb04f9a-723b-4de6-9f63-c67e1121c25")) {
            assertThrows(IllegalArgumentException.class, () -> DomainIds.StepId.parse(invalid));
        }
    }

    @Test
    void compositeIdentitiesUseValueEqualityAndPositiveAttempts() {
        var first = new DomainIds.StepAttemptId(
                DomainIds.RunId.parse(UUID_TEXT),
                DomainIds.StepId.parse("2f1e938c-d126-49d8-a141-cc1fe268424a"),
                new DomainIds.Attempt(1));
        var equal = new DomainIds.StepAttemptId(
                DomainIds.RunId.parse(UUID_TEXT),
                DomainIds.StepId.parse("2f1e938c-d126-49d8-a141-cc1fe268424a"),
                new DomainIds.Attempt(1));
        var next = new DomainIds.StepAttemptId(first.runId(), first.stepId(), new DomainIds.Attempt(2));
        assertEquals(first, equal);
        assertNotEquals(first, next);
        assertThrows(IllegalArgumentException.class, () -> new DomainIds.Attempt(0));

        var remote = new DomainIds.RemoteTaskId("diagnosis-agent", DomainIds.A2aTaskId.parse(UUID_TEXT));
        assertEquals(remote, new DomainIds.RemoteTaskId("diagnosis-agent", DomainIds.A2aTaskId.parse(UUID_TEXT)));
    }

    @Test
    void hashesAndRootCauseCodesFailClosed() {
        String hash = "sha256:" + "a".repeat(64);
        assertEquals(hash, new DomainValues.Sha256(hash).value());
        assertThrows(IllegalArgumentException.class, () -> new DomainValues.Sha256("sha256:ABC"));
        assertEquals("database.pool.exhausted.order",
                DomainValues.RootCauseCode.known("database.pool.exhausted.order",
                        DomainValues.MVP_ROOT_CAUSES).value());
        assertTrue(DomainValues.RootCauseCode.resolve(null, DomainValues.MVP_ROOT_CAUSES).isEmpty());
        assertTrue(DomainValues.RootCauseCode.resolve("temporary.guess", DomainValues.MVP_ROOT_CAUSES).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> DomainValues.RootCauseCode.known("Temporary.Guess", DomainValues.MVP_ROOT_CAUSES));
    }

    @Test
    void chainFailureKeepsOnlyBoundedRedactedMetadata() {
        String hash = "sha256:" + "b".repeat(64);
        ChainFailure failure = ChainFailure.fromCause(
                ChainFailure.Category.DEPENDENCY,
                "MODEL_UNAVAILABLE",
                true,
                UUID.randomUUID(),
                new ChainFailure.CheckpointRef(UUID.randomUUID(), 3),
                List.of(new ChainFailure.ControlledLogRef(URI.create("audit://run/call-1"),
                        new DomainValues.Sha256(hash))),
                new IllegalStateException("token=super-secret password=hunter2\n at internal.Stack.secret(Stack.java:1)"));

        assertTrue(failure.redactedSummary().contains("[REDACTED]"));
        assertFalse(failure.redactedSummary().contains("super-secret"));
        assertFalse(failure.redactedSummary().contains("hunter2"));
        assertFalse(failure.redactedSummary().contains("Stack.java"));
        assertThrows(IllegalArgumentException.class,
                () -> new ChainFailure.ControlledLogRef(URI.create("file:///tmp/raw.log"),
                        new DomainValues.Sha256(hash)));
    }

    @Test
    void unknownEnumDoesNotMutateExistingValue() {
        ChainFailure.Category existing = ChainFailure.Category.VALIDATION;
        assertThrows(IllegalArgumentException.class, () -> ChainFailure.Category.valueOf("SUCCESS"));
        assertEquals(ChainFailure.Category.VALIDATION, existing);
        assertFalse(Set.of(ChainFailure.Category.values()).isEmpty());
    }
}
