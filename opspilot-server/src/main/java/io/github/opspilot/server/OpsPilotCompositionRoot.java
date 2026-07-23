package io.github.opspilot.server;

import io.github.opspilot.adapters.persistence.postgres.DurableTaskRepository;
import io.github.opspilot.adapters.persistence.postgres.LocalVolumeArtifactAccessService;
import io.github.opspilot.adapters.persistence.postgres.PostgresCheckpointUnitOfWork;
import io.github.opspilot.adapters.persistence.postgres.PostgresIncidentAgentStateRepository;
import io.github.opspilot.adapters.persistence.postgres.PostgresProjectionReceiptRepository;
import io.github.opspilot.adapters.persistence.postgres.PostgresReadinessCheck;
import io.github.opspilot.adapters.persistence.postgres.SseEventRepository;
import io.github.opspilot.core.application.evidence.EvidenceNormalizer;
import io.github.opspilot.core.application.evidence.RuntimeEvidenceNormalizer;
import io.github.opspilot.core.port.extension.ExtensionContracts.ExtensionDescriptor;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.util.List;

/** The only production entry point allowed to construct concrete OpsPilot components. */
public final class OpsPilotCompositionRoot {
    private OpsPilotCompositionRoot() {
    }

    public static Components composeCore() {
        return composeCore(List.of());
    }

    /** Extensions are passed explicitly by deployment configuration; there is no runtime discovery. */
    public static Components composeCore(List<ExtensionDescriptor> extensions) {
        return new Components(new RuntimeEvidenceNormalizer(), extensions);
    }

    /** Production persistence is PostgreSQL-only and never substitutes an in-memory adapter. */
    public static PersistenceComponents composePersistence(
            DataSource dataSource, Path artifactRoot, long maxArtifactBytes) {
        return new PersistenceComponents(
                new PostgresIncidentAgentStateRepository(dataSource),
                new PostgresCheckpointUnitOfWork(dataSource),
                new PostgresProjectionReceiptRepository(dataSource, "opspilot-default-projector"),
                new DurableTaskRepository(dataSource),
                new SseEventRepository(dataSource),
                new LocalVolumeArtifactAccessService(dataSource, artifactRoot, maxArtifactBytes),
                new PostgresReadinessCheck(dataSource, "7", "0.8.4"));
    }

    public record Components(EvidenceNormalizer evidenceNormalizer, List<ExtensionDescriptor> extensions) {
        public Components { extensions = List.copyOf(extensions); }
    }

    public record PersistenceComponents(
            PostgresIncidentAgentStateRepository agentStateRepository,
            PostgresCheckpointUnitOfWork checkpointUnitOfWork,
            PostgresProjectionReceiptRepository projectionReceiptRepository,
            DurableTaskRepository durableTaskRepository,
            SseEventRepository sseEventRepository,
            LocalVolumeArtifactAccessService artifactAccessService,
            PostgresReadinessCheck readinessCheck) { }
}
