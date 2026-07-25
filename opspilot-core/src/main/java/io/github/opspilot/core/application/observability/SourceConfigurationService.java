package io.github.opspilot.core.application.observability;

import io.github.opspilot.core.port.observability.ObservationContracts.SignalType;
import io.github.opspilot.core.port.observability.ObservationContracts.SourceKind;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Mutable Source instance configuration, separate from the frozen adapter implementation registry. */
public final class SourceConfigurationService {
    private final Map<String, SourceInstance> sources = new LinkedHashMap<>();
    private final List<ConfigurationAudit> audit = new ArrayList<>();
    private final SourceConfigurationRepository repository;

    public SourceConfigurationService() {
        this(new SourceConfigurationRepository() {
            @Override public void save(SourceInstance source) { }
            @Override public void delete(String sourceId) { }
            @Override public List<SourceInstance> findAll() { return List.of(); }
        });
    }

    public SourceConfigurationService(SourceConfigurationRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
        repository.findAll().forEach(source -> sources.put(source.sourceId(), source));
        validateRoleBindings(sources.values());
    }

    public synchronized void upsert(SourceInstance source, String actorId) {
        validate(source);
        Map<String, SourceInstance> candidate = new LinkedHashMap<>(sources);
        candidate.put(source.sourceId(), source);
        validateRoleBindings(candidate.values());
        repository.save(source);
        sources.clear();
        sources.putAll(candidate);
        audit.add(new ConfigurationAudit(Instant.now(), actorId, "UPSERT", source.sourceId()));
    }

    public synchronized void remove(String sourceId, String actorId) {
        repository.delete(sourceId);
        sources.remove(sourceId);
        audit.add(new ConfigurationAudit(Instant.now(), actorId, "REMOVE", sourceId));
    }

    public synchronized List<SourceInstance> snapshot() { return List.copyOf(sources.values()); }
    public synchronized List<ConfigurationAudit> audit() { return List.copyOf(audit); }

    private static void validate(SourceInstance source) {
        if (source.sourceId() == null || !source.sourceId().matches("[A-Za-z0-9._:-]{1,256}")) {
            throw new SourceConfigurationException("SOURCE_ID_INVALID");
        }
        if (source.connectionRef() == null
                || !source.connectionRef().matches("observability-source://[A-Za-z0-9._/-]+")) {
            throw new SourceConfigurationException("CONNECTION_REF_INVALID");
        }
        if (source.timeout().isNegative() || source.timeout().isZero()
                || source.timeout().compareTo(Duration.ofSeconds(30)) > 0 || source.concurrency() < 1) {
            throw new SourceConfigurationException("SOURCE_LIMIT_INVALID");
        }
        if (source.queryTemplateIds().isEmpty() || source.signals().isEmpty() || source.resourceIds().isEmpty()) {
            throw new SourceConfigurationException("SOURCE_BINDING_MISSING");
        }
    }

    private static void validateRoleBindings(Collection<SourceInstance> sources) {
        Map<Binding, List<SourceInstance>> bindings = new HashMap<>();
        for (SourceInstance source : sources) {
            for (String resource : source.resourceIds()) {
                for (SignalType signal : source.signals()) {
                    bindings.computeIfAbsent(new Binding(source.targetSystemId(), resource, signal), ignored -> new ArrayList<>())
                            .add(source);
                }
            }
        }
        for (List<SourceInstance> bound : bindings.values()) {
            if (bound.size() > 1) {
                long primaryCount = bound.stream().filter(source -> source.role() == SourceRole.PRIMARY).count();
                if (primaryCount != 1 || bound.stream().map(SourceInstance::priority).distinct().count() != bound.size()) {
                    throw new SourceConfigurationException("SOURCE_ROLE_CONFLICT");
                }
            }
        }
    }

    public enum SourceRole { PRIMARY, CORROBORATING, FALLBACK_DISABLED }
    public enum SourceHealth { READY, UNAVAILABLE, DISABLED }
    public enum DataClassification { INTERNAL, CONFIDENTIAL, RESTRICTED }

    public record SourceInstance(
            String sourceId, SourceKind sourceKind, boolean enabled, Set<SignalType> signals,
            String connectionRef, String targetSystemId, Set<String> resourceIds,
            String adapterId, String adapterVersion, Set<String> queryTemplateIds,
            Duration timeout, int concurrency, DataClassification dataClassification,
            SourceHealth health, SourceRole role, int priority) {
        public SourceInstance {
            signals = Set.copyOf(signals);
            resourceIds = Set.copyOf(resourceIds);
            queryTemplateIds = Set.copyOf(queryTemplateIds);
        }
    }

    public record ConfigurationAudit(Instant occurredAt, String actorId, String action, String sourceId) { }
    public interface SourceConfigurationRepository {
        void save(SourceInstance source);
        void delete(String sourceId);
        List<SourceInstance> findAll();
    }
    private record Binding(String target, String resource, SignalType signal) { }

    public static final class SourceConfigurationException extends RuntimeException {
        public SourceConfigurationException(String code) { super(code); }
    }
}
