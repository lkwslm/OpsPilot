package io.github.opspilot.a2a.contract;

/** Non-authoritative progress, clarification, or limitation message. */
public record A2aStatusMessage(String taskId, Kind kind, String text) {
    public enum Kind {
        PROGRESS,
        CLARIFICATION,
        LIMITATION
    }
}
