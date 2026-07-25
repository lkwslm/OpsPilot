package io.github.opspilot.tools.defaults;

import io.github.opspilot.core.application.evidence.EvidenceNormalizer;
import io.github.opspilot.core.port.observability.ObservabilityQueryPort;
import io.github.opspilot.tools.defaults.ObservabilityToolContracts.*;
import java.util.Set;
import static io.github.opspilot.core.port.observability.ObservationContracts.SignalType.TOPOLOGY;

public final class TopologyQueryTool extends AbstractObservabilityQueryTool {
    public TopologyQueryTool(ObservabilityQueryPort port, EvidenceNormalizer normalizer, ToolAuthorizationPort auth, ToolAuditSink audit) {
        super("TopologyQueryTool", TOPOLOGY, Set.of("topology/compose-v1"), Set.of("resourceTypes", "relationshipTypes", "maxNodes"), port, normalizer, auth, audit);
    }
}
