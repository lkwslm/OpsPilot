package io.github.opspilot.a2a.contract;

import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bidirectional boundary mapper for the locked official A2A v1.0 objects. */
public final class OfficialA2aMapper {

    private OfficialA2aMapper() {
    }

    public static Message toOfficialMessage(A2aSendRequest request) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("callerServiceId", request.callerServiceId());
        metadata.put("targetAgentId", request.targetAgentId());
        metadata.put("skillId", request.skillId());
        metadata.put("inputMediaType", request.inputMediaType());
        metadata.put("outputMediaType", request.outputMediaType());
        metadata.put("protocolVersion", request.protocolVersion());
        metadata.put("deferCompletion", request.deferCompletion());
        metadata.put("requiredExtensions", request.requiredExtensions());
        put(metadata, "requestId", request.requestId());
        put(metadata, "traceId", request.traceId());
        put(metadata, "runId", request.runId());
        put(metadata, "stepId", request.stepId());
        put(metadata, "a2aTaskId", request.a2aTaskId());
        put(metadata, "invocationId", request.invocationId());
        return Message.builder()
                .role(Message.Role.ROLE_USER)
                .messageId(request.messageId())
                .contextId(request.contextId())
                .parts(new TextPart(request.text()))
                .extensions(join(request.requiredExtensions(), request.optionalExtensions()))
                .metadata(Map.copyOf(metadata))
                .build();
    }

    public static A2aSendRequest fromOfficialMessage(Message message) {
        if (message.role() != Message.Role.ROLE_USER || message.messageId() == null
                || message.contextId() == null) {
            throw invalidResponse();
        }
        String text = message.parts().stream()
                .filter(TextPart.class::isInstance)
                .map(TextPart.class::cast)
                .map(TextPart::text)
                .findFirst()
                .orElseThrow(OfficialA2aMapper::invalidResponse);
        Map<String, Object> metadata = message.metadata();
        List<String> required = strings(metadata.get("requiredExtensions"));
        List<String> optional = message.extensions().stream()
                .filter(extension -> !required.contains(extension))
                .toList();
        return new A2aSendRequest(
                message.messageId(), message.contextId(), text,
                Boolean.TRUE.equals(metadata.get("deferCompletion")),
                string(metadata, "callerServiceId"), string(metadata, "targetAgentId"),
                string(metadata, "skillId"), string(metadata, "inputMediaType"),
                string(metadata, "outputMediaType"), string(metadata, "protocolVersion"),
                required, optional,
                optionalString(metadata, "requestId"), optionalString(metadata, "traceId"),
                optionalString(metadata, "runId"), optionalString(metadata, "stepId"),
                optionalString(metadata, "a2aTaskId"), optionalString(metadata, "invocationId"));
    }

    public static Task toOfficialTask(A2aTask task) {
        List<Artifact> artifacts = task.artifact() == null
                ? List.of() : List.of(toOfficialArtifact(task.artifact()));
        return Task.builder()
                .id(task.taskId())
                .contextId(task.contextId())
                .status(new TaskStatus(toOfficialState(task.state())))
                .artifacts(artifacts)
                .history(List.of())
                .metadata(Map.of("messageId", task.messageId(), "revision", task.revision()))
                .build();
    }

    public static A2aTask fromOfficialTask(Task task) {
        if (task.id() == null || task.contextId() == null || task.status() == null) {
            throw invalidResponse();
        }
        Object messageId = task.metadata().get("messageId");
        Object revision = task.metadata().get("revision");
        if (!(messageId instanceof String) || !(revision instanceof Number)) {
            throw invalidResponse();
        }
        A2aTaskState state = fromOfficialState(task.status().state());
        A2aArtifact artifact = task.artifacts() == null || task.artifacts().isEmpty()
                ? null : fromOfficialArtifact(task.artifacts().getFirst());
        return new A2aTask(task.id(), task.contextId(), (String) messageId, state,
                artifact, ((Number) revision).longValue());
    }

    public static Artifact toOfficialArtifact(A2aArtifact artifact) {
        return Artifact.builder()
                .artifactId(artifact.artifactId())
                .name("OpsPilot reliable result")
                .description("Schema-validated A2A result artifact")
                .parts(new DataPart(A2aJson.read(artifact.payload(), Object.class)))
                .metadata(Map.of(
                        "mediaType", artifact.mediaType(),
                        "schemaVersion", artifact.schemaVersion(),
                        "sha256", artifact.sha256(),
                        "taskId", artifact.taskId(),
                        "runId", artifact.runId(),
                        "ownerAgentId", artifact.ownerAgentId(),
                        "references", artifact.references()))
                .extensions(List.of())
                .build();
    }

    public static A2aArtifact fromOfficialArtifact(Artifact artifact) {
        if (artifact == null || artifact.artifactId() == null || artifact.parts() == null
                || artifact.parts().size() != 1 || !(artifact.parts().getFirst() instanceof DataPart data)) {
            throw invalidResponse();
        }
        Map<String, Object> metadata = artifact.metadata();
        return new A2aArtifact(
                artifact.artifactId(), string(metadata, "mediaType"),
                string(metadata, "schemaVersion"), string(metadata, "sha256"),
                A2aJson.write(data.data()), string(metadata, "taskId"),
                string(metadata, "runId"), string(metadata, "ownerAgentId"),
                strings(metadata.get("references")));
    }

    public static A2aAgentDescriptor fromOfficialCard(String agentId, AgentCard card) {
        if (card.skills().size() != 1 || card.supportedInterfaces().size() != 1) {
            throw invalidResponse();
        }
        var skill = card.skills().getFirst();
        var endpoint = card.supportedInterfaces().getFirst();
        return new A2aAgentDescriptor(
                agentId, card.name(), card.version(), skill.id(), endpoint.url(),
                endpoint.protocolVersion(), skill.inputModes(), skill.outputModes(),
                card.capabilities().streaming(),
                card.capabilities().extensions().stream()
                        .filter(extension -> extension.required())
                        .map(extension -> extension.uri())
                        .toList());
    }

    public static AgentCard toOfficialCard(A2aAgentDescriptor descriptor) {
        AgentCard card = Phase0AgentCards.create().get(descriptor.agentId());
        if (card == null || !descriptor.equals(fromOfficialCard(descriptor.agentId(), card))) {
            throw invalidResponse();
        }
        return card;
    }

    public static TaskState toOfficialState(A2aTaskState state) {
        if (state == null || !state.valid()) {
            throw invalidResponse();
        }
        return switch (state) {
            case SUBMITTED -> TaskState.TASK_STATE_SUBMITTED;
            case WORKING -> TaskState.TASK_STATE_WORKING;
            case INPUT_REQUIRED -> TaskState.TASK_STATE_INPUT_REQUIRED;
            case AUTH_REQUIRED -> TaskState.TASK_STATE_AUTH_REQUIRED;
            case COMPLETED -> TaskState.TASK_STATE_COMPLETED;
            case CANCELED -> TaskState.TASK_STATE_CANCELED;
            case FAILED -> TaskState.TASK_STATE_FAILED;
            case REJECTED -> TaskState.TASK_STATE_REJECTED;
            case UNSPECIFIED, UNRECOGNIZED -> throw invalidResponse();
        };
    }

    public static A2aTaskState fromOfficialState(TaskState state) {
        if (state == null || state == TaskState.UNRECOGNIZED) {
            throw invalidResponse();
        }
        return switch (state) {
            case TASK_STATE_SUBMITTED -> A2aTaskState.SUBMITTED;
            case TASK_STATE_WORKING -> A2aTaskState.WORKING;
            case TASK_STATE_INPUT_REQUIRED -> A2aTaskState.INPUT_REQUIRED;
            case TASK_STATE_AUTH_REQUIRED -> A2aTaskState.AUTH_REQUIRED;
            case TASK_STATE_COMPLETED -> A2aTaskState.COMPLETED;
            case TASK_STATE_CANCELED -> A2aTaskState.CANCELED;
            case TASK_STATE_FAILED -> A2aTaskState.FAILED;
            case TASK_STATE_REJECTED -> A2aTaskState.REJECTED;
            case UNRECOGNIZED -> throw invalidResponse();
        };
    }

    private static List<String> join(List<String> first, List<String> second) {
        List<String> result = new ArrayList<>(first);
        second.stream().filter(extension -> !result.contains(extension)).forEach(result::add);
        return List.copyOf(result);
    }

    private static String string(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw invalidResponse();
        }
        return text;
    }

    private static String optionalString(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value == null) return null;
        if (!(value instanceof String text) || text.isBlank()) throw invalidResponse();
        return text;
    }

    private static void put(Map<String, Object> metadata, String key, String value) {
        if (value != null) metadata.put(key, value);
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> values)) {
            throw invalidResponse();
        }
        return values.stream().map(item -> {
            if (!(item instanceof String text)) {
                throw invalidResponse();
            }
            return text;
        }).toList();
    }

    private static A2aProtocolException invalidResponse() {
        return new A2aProtocolException(400, "A2A_INVALID_AGENT_RESPONSE");
    }
}
