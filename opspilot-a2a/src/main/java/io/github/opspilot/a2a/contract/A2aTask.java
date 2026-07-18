package io.github.opspilot.a2a.contract;

/** Stable wire and persistence view of an A2A task. */
public record A2aTask(
        String taskId,
        String contextId,
        String messageId,
        A2aTaskState state,
        A2aArtifact artifact,
        long revision) {
}
