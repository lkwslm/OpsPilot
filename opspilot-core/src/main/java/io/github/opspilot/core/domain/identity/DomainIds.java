package io.github.opspilot.core.domain.identity;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Strong identities used at every core boundary. */
public final class DomainIds {
    private static final Pattern UUID_TEXT = Pattern.compile(
            "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
    private static final Pattern REMOTE_AGENT = Pattern.compile("^[a-z][a-z0-9-]{2,63}$");

    private DomainIds() {
    }

    public interface DomainId {
        UUID value();

        default String wire() {
            return value().toString();
        }
    }

    public record IncidentId(UUID value) implements DomainId {
        public IncidentId { value = required(value); }
        public static IncidentId parse(String value) { return new IncidentId(parseUuid(value)); }
    }

    public record RunId(UUID value) implements DomainId {
        public RunId { value = required(value); }
        public static RunId parse(String value) { return new RunId(parseUuid(value)); }
    }

    public record StepId(UUID value) implements DomainId {
        public StepId { value = required(value); }
        public static StepId parse(String value) { return new StepId(parseUuid(value)); }
    }

    public record A2aTaskId(UUID value) implements DomainId {
        public A2aTaskId { value = required(value); }
        public static A2aTaskId parse(String value) { return new A2aTaskId(parseUuid(value)); }
    }

    public record EvidenceId(UUID value) implements DomainId {
        public EvidenceId { value = required(value); }
        public static EvidenceId parse(String value) { return new EvidenceId(parseUuid(value)); }
    }

    public record ArtifactId(UUID value) implements DomainId {
        public ArtifactId { value = required(value); }
        public static ArtifactId parse(String value) { return new ArtifactId(parseUuid(value)); }
    }

    public record HypothesisId(UUID value) implements DomainId {
        public HypothesisId { value = required(value); }
        public static HypothesisId parse(String value) { return new HypothesisId(parseUuid(value)); }
    }

    public record Attempt(int value) {
        public Attempt {
            if (value < 1) {
                throw new IllegalArgumentException("attempt must be a positive monotonic counter");
            }
        }
    }

    public record StepAttemptId(RunId runId, StepId stepId, Attempt attempt) {
        public StepAttemptId {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(stepId, "stepId");
            Objects.requireNonNull(attempt, "attempt");
        }
    }

    public record RemoteTaskId(String remoteAgentId, A2aTaskId a2aTaskId) {
        public RemoteTaskId {
            if (remoteAgentId == null || !REMOTE_AGENT.matcher(remoteAgentId).matches()) {
                throw new IllegalArgumentException("remoteAgentId is not a stable agent identity");
            }
            Objects.requireNonNull(a2aTaskId, "a2aTaskId");
        }
    }

    private static UUID parseUuid(String value) {
        if (value == null || !UUID_TEXT.matcher(value).matches()) {
            throw new IllegalArgumentException("identity must be a canonical lowercase UUID");
        }
        return UUID.fromString(value);
    }

    private static UUID required(UUID value) {
        return Objects.requireNonNull(value, "identity value");
    }
}
