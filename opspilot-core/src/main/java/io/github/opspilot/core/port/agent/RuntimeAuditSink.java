package io.github.opspilot.core.port.agent;

import java.util.Map;

/** Receives bounded runtime audit events; message bodies and model reasoning are forbidden. */
public interface RuntimeAuditSink {

    void append(AuditEvent event);

    record AuditEvent(
            long sequence,
            String type,
            int round,
            String actionFingerprint,
            Integer inputTokens,
            Integer outputTokens,
            String checkpointId,
            Boolean cancelled,
            Map<String, Object> attributes) {

        public AuditEvent {
            attributes = Map.copyOf(attributes);
        }
    }
}
