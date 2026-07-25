package io.github.opspilot.tools.defaults;

import io.github.opspilot.core.application.evidence.EvidenceNormalizer;
import io.github.opspilot.core.port.observability.ObservabilityQueryPort;
import io.github.opspilot.tools.defaults.ObservabilityToolContracts.*;
import java.util.Set;
import static io.github.opspilot.core.port.observability.ObservationContracts.SignalType.METRIC;

public final class MetricQueryTool extends AbstractObservabilityQueryTool {
    public MetricQueryTool(ObservabilityQueryPort port, EvidenceNormalizer normalizer, ToolAuthorizationPort auth, ToolAuditSink audit) {
        super("MetricQueryTool", METRIC, Set.of("metric/http-v1", "metric/hikari-v1"), Set.of("metricNames", "aggregation", "stepSeconds"), port, normalizer, auth, audit);
    }
}
