package io.github.opspilot.tools.defaults;

import io.github.opspilot.core.application.evidence.EvidenceNormalizer;
import io.github.opspilot.core.port.observability.ObservabilityQueryPort;
import io.github.opspilot.tools.defaults.ObservabilityToolContracts.*;
import java.util.Set;
import static io.github.opspilot.core.port.observability.ObservationContracts.SignalType.TRACE;

public final class TraceQueryTool extends AbstractObservabilityQueryTool {
    public TraceQueryTool(ObservabilityQueryPort port, EvidenceNormalizer normalizer, ToolAuthorizationPort auth, ToolAuditSink audit) {
        super("TraceQueryTool", TRACE, Set.of("trace/service-v1"), Set.of("serviceNames", "operationNames", "minDurationMs", "limit"), port, normalizer, auth, audit);
    }
}
