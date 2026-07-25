package io.github.opspilot.tools.defaults;

import io.github.opspilot.core.application.evidence.EvidenceNormalizer;
import io.github.opspilot.core.port.observability.ObservabilityQueryPort;
import io.github.opspilot.tools.defaults.ObservabilityToolContracts.*;
import java.util.Set;
import static io.github.opspilot.core.port.observability.ObservationContracts.SignalType.CONFIG;

public final class ConfigReadTool extends AbstractObservabilityQueryTool {
    public ConfigReadTool(ObservabilityQueryPort port, EvidenceNormalizer normalizer, ToolAuthorizationPort auth, ToolAuditSink audit) {
        super("ConfigReadTool", CONFIG, Set.of("config/allowlist-v1"), Set.of("keys"), port, normalizer, auth, audit);
    }
}
