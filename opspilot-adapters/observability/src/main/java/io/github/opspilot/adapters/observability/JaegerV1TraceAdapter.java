package io.github.opspilot.adapters.observability;

import java.net.URI;

/** Jaeger v1 query boundary retained as a first-class supported adapter. */
public final class JaegerV1TraceAdapter extends JaegerTraceAdapter {
    public JaegerV1TraceAdapter(URI endpoint) {
        super(endpoint, v1Descriptor());
    }
}
