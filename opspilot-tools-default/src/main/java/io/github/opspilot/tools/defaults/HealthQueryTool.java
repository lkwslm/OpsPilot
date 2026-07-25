package io.github.opspilot.tools.defaults;

import io.github.opspilot.core.application.evidence.EvidenceNormalizer;
import io.github.opspilot.core.port.observability.ObservabilityQueryPort;
import io.github.opspilot.tools.defaults.ObservabilityToolContracts.*;
import java.util.Set;
import static io.github.opspilot.core.port.observability.ObservationContracts.SignalType.HEALTH;

public final class HealthQueryTool extends AbstractObservabilityQueryTool {
    public HealthQueryTool(ObservabilityQueryPort port, EvidenceNormalizer normalizer, ToolAuthorizationPort auth, ToolAuditSink audit) {
        super("HealthQueryTool", HEALTH, Set.of("health/readiness-v1"), Set.of("checks"), port, normalizer, auth, audit);
    }
}
