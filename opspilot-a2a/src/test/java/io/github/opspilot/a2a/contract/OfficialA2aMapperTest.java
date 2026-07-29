package io.github.opspilot.a2a.contract;

import org.a2aproject.sdk.spec.TaskState;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class OfficialA2aMapperTest {

    @Test
    void messageTaskAndArtifactRoundTripThroughOfficialObjects() throws Exception {
        A2aSendRequest request = new A2aSendRequest(
                "message-1", "run-1", "diagnose", false).withCorrelation(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(), "a2a-task-1", UUID.randomUUID());
        assertEquals(request, OfficialA2aMapper.fromOfficialMessage(
                OfficialA2aMapper.toOfficialMessage(request)));

        String payload = "{\"result\":\"safe\"}";
        A2aArtifact artifact = new A2aArtifact(
                "artifact-1", request.outputMediaType(), "1.0.0", sha256(payload), payload,
                "task-1", "run-1", "supervisor", List.of("evidence-1"));
        A2aTask task = new A2aTask(
                "task-1", "run-1", "message-1", A2aTaskState.COMPLETED, artifact, 3);
        assertEquals(task, OfficialA2aMapper.fromOfficialTask(
                OfficialA2aMapper.toOfficialTask(task)));
        assertEquals(artifact, new A2aResultReceiver().receive(task));
    }

    @Test
    void everyOfficialStateIsMappedAndInvalidSentinelsFailClosed() {
        List<A2aTaskState> valid = Arrays.stream(A2aTaskState.values())
                .filter(A2aTaskState::valid)
                .toList();
        assertEquals(8, valid.size());
        valid.forEach(state -> assertEquals(state,
                OfficialA2aMapper.fromOfficialState(OfficialA2aMapper.toOfficialState(state))));
        assertThrows(A2aProtocolException.class,
                () -> OfficialA2aMapper.fromOfficialState(TaskState.UNRECOGNIZED));
        assertThrows(A2aProtocolException.class,
                () -> OfficialA2aMapper.toOfficialState(A2aTaskState.UNSPECIFIED));
    }

    @Test
    void statusMessageCannotSubstituteForFinalArtifact() {
        A2aTask completedWithoutArtifact = new A2aTask(
                "task-1", "run-1", "message-1", A2aTaskState.COMPLETED, null, 2);
        A2aResultReceiver receiver = new A2aResultReceiver();
        receiver.audit(new A2aStatusMessage(
                "task-1", A2aStatusMessage.Kind.PROGRESS, "looks complete"));
        assertEquals("A2A_FINAL_ARTIFACT_REQUIRED",
                assertThrows(A2aProtocolException.class,
                        () -> receiver.receive(completedWithoutArtifact)).code());
    }

    private static String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
