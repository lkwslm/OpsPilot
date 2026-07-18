package io.github.opspilot.a2a.contract;

/** Replayable stream event. */
public record A2aTaskEvent(long sequence, A2aTask task) {
}
