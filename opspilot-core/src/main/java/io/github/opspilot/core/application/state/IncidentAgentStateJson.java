package io.github.opspilot.core.application.state;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.github.opspilot.core.domain.state.IncidentAgentState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Real JSON codec with explicit migration and validation on both save and recovery. */
public final class IncidentAgentStateJson {
    public static final String STATE_JSON_INVALID = "STATE_JSON_INVALID";

    private final ObjectMapper mapper;
    private final StateMigrationChain migrations;
    private final IncidentAgentStatePolicy policy;

    public IncidentAgentStateJson(StateMigrationChain migrations, IncidentAgentStatePolicy policy) {
        this.migrations = Objects.requireNonNull(migrations, "migrations");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
    }

    public byte[] write(IncidentAgentState state) {
        policy.validateBeforeSerialization(state);
        try {
            byte[] json = mapper.writeValueAsBytes(state);
            policy.validateSerializedSize(json);
            return json;
        } catch (JsonProcessingException exception) {
            throw invalid("checkpoint serialization failed", exception);
        }
    }

    public IncidentAgentState read(byte[] json) {
        Objects.requireNonNull(json, "json");
        policy.validateSerializedSize(json);
        try {
            IncidentAgentState state = mapper.treeToValue(
                    migrations.migrate(mapper.readTree(json)), IncidentAgentState.class);
            policy.validateBeforeSerialization(state);
            return state;
        } catch (StateContractException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalid("checkpoint deserialization failed", exception);
        }
    }

    public String writeString(IncidentAgentState state) {
        return new String(write(state), StandardCharsets.UTF_8);
    }

    private static StateContractException invalid(String message, Throwable cause) {
        return new StateContractException(STATE_JSON_INVALID, message, cause);
    }
}
