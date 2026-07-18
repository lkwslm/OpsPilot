package io.github.opspilot.a2a.contract;

/** Minimal send request; deferCompletion exists only to exercise cancellation. */
public record A2aSendRequest(
        String messageId,
        String contextId,
        String text,
        boolean deferCompletion) {
}
