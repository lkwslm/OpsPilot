package io.github.opspilot.core.application.state;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.opspilot.core.domain.state.IncidentAgentState;

import java.util.Map;
import java.util.Objects;
import java.util.function.UnaryOperator;

/** Explicit source/target migrations; unknown versions are never guessed. */
public final class StateMigrationChain {
    public static final String STATE_SCHEMA_UNSUPPORTED = "STATE_SCHEMA_UNSUPPORTED";

    private final Map<String, Migration> migrations;

    public StateMigrationChain(Map<String, Migration> migrations) {
        this.migrations = Map.copyOf(migrations);
        migrations.forEach((source, migration) -> {
            if (!source.equals(migration.sourceVersion()) || source.equals(migration.targetVersion())) {
                throw new IllegalArgumentException("migration source/target declaration is invalid");
            }
        });
    }

    public static StateMigrationChain defaults() {
        Migration legacy = new Migration("0.9.0", IncidentAgentState.CURRENT_SCHEMA_VERSION, state -> {
            state.put("schemaVersion", IncidentAgentState.CURRENT_SCHEMA_VERSION);
            if (!state.has("cancellationRequested")) {
                state.put("cancellationRequested", false);
            }
            return state;
        });
        return new StateMigrationChain(Map.of(legacy.sourceVersion(), legacy));
    }

    ObjectNode migrate(JsonNode input) {
        if (!(input instanceof ObjectNode state)) {
            throw unsupported("snapshot root must be an object");
        }
        JsonNode versionNode = state.get("schemaVersion");
        if (versionNode == null || !versionNode.isTextual()) {
            throw unsupported("schemaVersion is required");
        }
        String version = versionNode.textValue();
        int remaining = migrations.size() + 1;
        while (!IncidentAgentState.CURRENT_SCHEMA_VERSION.equals(version)) {
            if (--remaining < 0) {
                throw unsupported("migration chain contains a cycle");
            }
            Migration migration = migrations.get(version);
            if (migration == null) {
                throw unsupported("unsupported state schema: " + version);
            }
            state = Objects.requireNonNull(migration.transform().apply(state.deepCopy()), "migration result");
            JsonNode migratedVersion = state.get("schemaVersion");
            if (migratedVersion == null || !migration.targetVersion().equals(migratedVersion.textValue())) {
                throw unsupported("migration did not produce its declared target version");
            }
            version = migration.targetVersion();
        }
        return state;
    }

    private static StateContractException unsupported(String message) {
        return new StateContractException(STATE_SCHEMA_UNSUPPORTED, message);
    }

    public record Migration(
            String sourceVersion,
            String targetVersion,
            UnaryOperator<ObjectNode> transform) {
        public Migration {
            Objects.requireNonNull(sourceVersion, "sourceVersion");
            Objects.requireNonNull(targetVersion, "targetVersion");
            Objects.requireNonNull(transform, "transform");
        }
    }
}
