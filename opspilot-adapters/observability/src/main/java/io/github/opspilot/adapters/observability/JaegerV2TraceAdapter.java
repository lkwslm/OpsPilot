package io.github.opspilot.adapters.observability;

import java.net.URI;

/** Jaeger v2 query boundary with the same vendor-neutral Trace contract as v1. */
public final class JaegerV2TraceAdapter extends JaegerTraceAdapter {
    public JaegerV2TraceAdapter(URI endpoint) {
        super(endpoint, v2Descriptor());
    }
}
