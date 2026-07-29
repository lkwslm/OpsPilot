package io.github.opspilot.core.application.profile;

import io.github.opspilot.core.application.profile.AgentProfile.AgentRole;
import io.github.opspilot.core.application.profile.AgentProfile.ProfileKey;
import io.github.opspilot.core.application.profile.ProfileCapabilityClosure.CapabilityCatalog;
import io.github.opspilot.core.application.profile.ProfileCapabilityClosure.ValidatedClosure;

import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Explicit built-in profile registry; it has no scanning or discovery path. */
public final class AgentProfileRegistry {
    private final Map<ProfileKey, AgentProfile> profiles = new LinkedHashMap<>();
    private Map<ProfileKey, ValidatedClosure> closures = Map.of();
    private boolean frozen;

    public synchronized void register(AgentProfile profile) {
        ensureMutable();
        Objects.requireNonNull(profile, "profile");
        if (profiles.putIfAbsent(profile.key(), profile) != null) {
            throw new ProfileRegistryException("DUPLICATE_PROFILE_VERSION", profile.key().toString());
        }
    }

    public synchronized Map<ProfileKey, ValidatedClosure> validateAndFreeze(CapabilityCatalog catalog) {
        ensureMutable();
        if (profiles.size() != AgentRole.values().length
                || !profiles.values().stream().map(AgentProfile::role).collect(
                java.util.stream.Collectors.toSet()).equals(EnumSet.allOf(AgentRole.class))) {
            throw new ProfileRegistryException("BUILT_IN_ROLE_SET_INCOMPLETE", "profiles");
        }
        Map<ProfileKey, ValidatedClosure> validated = new LinkedHashMap<>();
        profiles.entrySet().stream().sorted(Map.Entry.comparingByKey(
                Comparator.comparing(ProfileKey::profileId).thenComparing(ProfileKey::profileVersion)))
                .forEach(entry -> validated.put(entry.getKey(),
                        ProfileCapabilityClosure.validate(entry.getValue(), catalog)));
        closures = Map.copyOf(validated);
        frozen = true;
        return closures;
    }

    public synchronized AgentProfile require(String profileId, String version) {
        ensureFrozen();
        ProfileKey key = new ProfileKey(profileId, version);
        AgentProfile profile = profiles.get(key);
        if (profile == null) throw new ProfileRegistryException("PROFILE_NOT_FOUND", key.toString());
        return profile;
    }

    public synchronized AgentProfile latest(String profileId) {
        ensureFrozen();
        return profiles.values().stream().filter(profile -> profile.profileId().equals(profileId))
                .max(Comparator.comparing(AgentProfile::profileVersion, AgentProfileRegistry::compareVersions))
                .orElseThrow(() -> new ProfileRegistryException("PROFILE_NOT_FOUND", profileId));
    }

    public synchronized ValidatedClosure closure(ProfileKey key) {
        ensureFrozen();
        ValidatedClosure closure = closures.get(key);
        if (closure == null) throw new ProfileRegistryException("PROFILE_NOT_FOUND", key.toString());
        return closure;
    }

    public synchronized boolean isFrozen() { return frozen; }

    private void ensureMutable() {
        if (frozen) throw new ProfileRegistryException("PROFILE_REGISTRY_FROZEN", "profiles");
    }

    private void ensureFrozen() {
        if (!frozen) throw new ProfileRegistryException("PROFILE_REGISTRY_NOT_FROZEN", "profiles");
    }

    private static int compareVersions(String left, String right) {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        for (int index = 0; index < 3; index++) {
            int compared = Integer.compare(Integer.parseInt(a[index]), Integer.parseInt(b[index]));
            if (compared != 0) return compared;
        }
        return 0;
    }

    public static final class ProfileRegistryException extends RuntimeException {
        private final String code;
        private final String fieldPath;

        public ProfileRegistryException(String code, String fieldPath) {
            super(code + ":" + fieldPath);
            this.code = code;
            this.fieldPath = fieldPath;
        }

        public String code() { return code; }
        public String fieldPath() { return fieldPath; }
    }
}
