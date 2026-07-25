package io.github.opspilot.tools.defaults;

import io.github.opspilot.core.application.evidence.EvidenceNormalizer;
import io.github.opspilot.core.port.observability.ObservabilityQueryPort;
import io.github.opspilot.tools.defaults.ObservabilityToolContracts.*;
import java.util.Set;
import static io.github.opspilot.core.port.observability.ObservationContracts.SignalType.LOG;

public final class LogQueryTool extends AbstractObservabilityQueryTool {
    public LogQueryTool(ObservabilityQueryPort port, EvidenceNormalizer normalizer, ToolAuthorizationPort auth, ToolAuditSink audit) {
        super("LogQueryTool", LOG, Set.of("log/errors-v1"), Set.of("levels", "contains", "limit"), port, normalizer, auth, audit);
    }
}
