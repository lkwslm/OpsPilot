package io.github.opspilot.server;

import io.github.opspilot.core.domain.state.StateMachines.AgentEndpointState;
import io.github.opspilot.core.domain.state.StateMachines.EndpointReasonCode;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Immutable, allowlisted Agent Directory and endpoint selection authority. */
public final class AgentDirectory {
    private static final String FILE_NAME = "agent-directory.yaml";
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Set<String> ENTRY_KEYS = Set.of(
            "id", "card_url", "expected_skill", "expected_protocol", "expected_binding",
            "service_identity", "token_ref", "expected_card_sha256");
    private static final Map<String, Expected> ALLOWLIST = Map.of(
            "evidence-collector", new Expected("evidence-agent", 8081,
                    "collect-observability-evidence", "svc:evidence-agent", "evidence-agent-token"),
            "code-analysis", new Expected("code-agent", 8082,
                    "analyze-code-location", "svc:code-agent", "code-agent-token"),
            "knowledge", new Expected("knowledge-agent", 8083,
                    "retrieve-incident-knowledge", "svc:knowledge-agent", "knowledge-agent-token"),
            "diagnosis", new Expected("diagnosis-agent", 8084,
                    "generate-and-verify-hypotheses", "svc:diagnosis-agent", "diagnosis-agent-token"),
            "remediation", new Expected("remediation-agent", 8085,
                    "propose-remediation", "svc:remediation-agent", "remediation-agent-token"));

    private final Path path;
    private final String schemaVersion;
    private final String digest;
    private final Map<String, Entry> entries;

    private AgentDirectory(Path path, String schemaVersion, String digest, Map<String, Entry> entries) {
        this.path = path;
        this.schemaVersion = schemaVersion;
        this.digest = digest;
        this.entries = Map.copyOf(entries);
    }

    public static AgentDirectory load(Path allowedRoot, Path configuredPath) throws IOException {
        Path root = allowedRoot.toRealPath();
        Path path = configuredPath.toRealPath();
        if (!path.startsWith(root) || !FILE_NAME.equals(path.getFileName().toString())) {
            throw new IllegalArgumentException("AGENT_DIRECTORY_PATH_FORBIDDEN");
        }
        byte[] bytes = Files.readAllBytes(path);
        Parsed parsed = parse(new String(bytes, StandardCharsets.UTF_8));
        if (!"1.0".equals(parsed.schemaVersion()) || !parsed.entries().keySet().equals(ALLOWLIST.keySet())) {
            throw new IllegalArgumentException("AGENT_DIRECTORY_CONTRACT_INVALID");
        }
        return new AgentDirectory(path, parsed.schemaVersion(), sha256(bytes), parsed.entries());
    }

    public Entry entry(String agentId) {
        Entry entry = entries.get(agentId);
        if (entry == null) {
            throw new IllegalArgumentException("AGENT_DIRECTORY_ENDPOINT_UNKNOWN");
        }
        return entry;
    }

    public EndpointSnapshot probe(String agentId, CardProbe probe, Instant now) {
        Entry entry = entry(agentId);
        try {
            byte[] card = probe.fetch(entry.cardUrl());
            String cardDigest = sha256(card);
            String body = new String(card, StandardCharsets.UTF_8);
            boolean valid = cardDigest.equals(entry.expectedCardSha256())
                    && body.contains("\"name\":\"" + entry.id() + "\"")
                    && body.contains("\"protocolVersion\":\"" + entry.expectedProtocol() + "\"")
                    && body.contains("\"id\":\"" + entry.expectedSkill() + "\"");
            return valid
                    ? new EndpointSnapshot(agentId, AgentEndpointState.READY, null, false,
                            cardDigest, digest, entry.expectedSkill(), entry.expectedProtocol(), now)
                    : new EndpointSnapshot(agentId, AgentEndpointState.UNAVAILABLE,
                            EndpointReasonCode.CARD_INVALID, false, cardDigest, digest,
                            entry.expectedSkill(), entry.expectedProtocol(), now);
        } catch (Exception exception) {
            return new EndpointSnapshot(agentId, AgentEndpointState.UNAVAILABLE,
                    EndpointReasonCode.HEALTH_CHECK_FAILED, true, null, digest,
                    entry.expectedSkill(), entry.expectedProtocol(), now);
        }
    }

    public URI resolve(String agentId, URI requestedUrl, EndpointSnapshot snapshot,
            Instant now, Duration maximumAge) {
        Entry entry = entry(agentId);
        if (!entry.cardUrl().equals(requestedUrl)
                || snapshot == null
                || snapshot.state() != AgentEndpointState.READY
                || !agentId.equals(snapshot.agentId())
                || !digest.equals(snapshot.directoryDigest())
                || !entry.expectedCardSha256().equals(snapshot.cardDigest())
                || !entry.expectedSkill().equals(snapshot.skill())
                || !entry.expectedProtocol().equals(snapshot.protocol())
                || snapshot.probedAt().plus(maximumAge).isBefore(now)) {
            throw new IllegalStateException("AGENT_DIRECTORY_ENDPOINT_NOT_READY");
        }
        return entry.cardUrl();
    }

    public void verifyUnchanged() throws IOException {
        if (!digest.equals(sha256(Files.readAllBytes(path)))) {
            throw new IllegalStateException("AGENT_DIRECTORY_CHANGED_AFTER_STARTUP");
        }
    }

    public String schemaVersion() {
        return schemaVersion;
    }

    public String digest() {
        return digest;
    }

    public List<Entry> entries() {
        return entries.values().stream().sorted(java.util.Comparator.comparing(Entry::id)).toList();
    }

    private static Parsed parse(String yaml) {
        String schemaVersion = null;
        List<Map<String, String>> rawEntries = new ArrayList<>();
        Map<String, String> current = null;
        for (String line : yaml.lines().toList()) {
            String stripped = line.strip();
            if (stripped.isBlank() || stripped.startsWith("#") || "agents:".equals(stripped)) {
                continue;
            }
            if (stripped.startsWith("schema_version:")) {
                schemaVersion = value(stripped);
                continue;
            }
            if (stripped.startsWith("- ")) {
                current = new LinkedHashMap<>();
                rawEntries.add(current);
                put(current, stripped.substring(2));
                continue;
            }
            if (current == null) {
                throw new IllegalArgumentException("AGENT_DIRECTORY_CONTRACT_INVALID");
            }
            put(current, stripped);
        }
        Map<String, Entry> entries = new LinkedHashMap<>();
        for (Map<String, String> raw : rawEntries) {
            if (!raw.keySet().equals(ENTRY_KEYS)) {
                throw new IllegalArgumentException("AGENT_DIRECTORY_CONTRACT_INVALID");
            }
            Entry entry = entry(raw);
            if (entries.putIfAbsent(entry.id(), entry) != null) {
                throw new IllegalArgumentException("AGENT_DIRECTORY_DUPLICATE_ID");
            }
        }
        return new Parsed(schemaVersion, entries);
    }

    private static void put(Map<String, String> values, String line) {
        int separator = line.indexOf(':');
        if (separator < 1) {
            throw new IllegalArgumentException("AGENT_DIRECTORY_CONTRACT_INVALID");
        }
        String key = line.substring(0, separator).strip();
        if (!ENTRY_KEYS.contains(key) || values.putIfAbsent(key, value(line)) != null) {
            throw new IllegalArgumentException("AGENT_DIRECTORY_CONTRACT_INVALID");
        }
    }

    private static String value(String line) {
        String value = line.substring(line.indexOf(':') + 1).strip();
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static Entry entry(Map<String, String> raw) {
        String id = raw.get("id");
        Expected expected = ALLOWLIST.get(id);
        URI uri;
        try {
            uri = URI.create(raw.get("card_url"));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("AGENT_DIRECTORY_URL_INVALID", exception);
        }
        boolean urlValid = expected != null && "http".equals(uri.getScheme())
                && expected.host().equals(uri.getHost()) && expected.port() == uri.getPort()
                && "/.well-known/agent-card.json".equals(uri.getPath())
                && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null
                && uri.normalize().equals(uri);
        if (!urlValid
                || !expected.skill().equals(raw.get("expected_skill"))
                || !"1.0".equals(raw.get("expected_protocol"))
                || !"HTTP+JSON".equals(raw.get("expected_binding"))
                || !expected.serviceIdentity().equals(raw.get("service_identity"))
                || !("file:/run/secrets/" + expected.tokenFile()).equals(raw.get("token_ref"))
                || !SHA256.matcher(raw.get("expected_card_sha256")).matches()) {
            throw new IllegalArgumentException("AGENT_DIRECTORY_ENTRY_INVALID");
        }
        return new Entry(id, uri, raw.get("expected_skill"), raw.get("expected_protocol"),
                raw.get("expected_binding"), raw.get("service_identity"), raw.get("token_ref"),
                raw.get("expected_card_sha256"));
    }

    private static String sha256(byte[] bytes) {
        return java.util.HexFormat.of().formatHex(MessageDigestHolder.digest(bytes));
    }

    public record Entry(String id, URI cardUrl, String expectedSkill, String expectedProtocol,
            String expectedBinding, String serviceIdentity, String tokenRef,
            String expectedCardSha256) {
    }

    public record EndpointSnapshot(String agentId, AgentEndpointState state,
            EndpointReasonCode reasonCode, boolean retryable, String cardDigest,
            String directoryDigest, String skill, String protocol, Instant probedAt) {
    }

    @FunctionalInterface
    public interface CardProbe {
        byte[] fetch(URI cardUrl) throws Exception;
    }

    private record Parsed(String schemaVersion, Map<String, Entry> entries) {
    }

    private record Expected(String host, int port, String skill, String serviceIdentity,
            String tokenFile) {
    }

    private static final class MessageDigestHolder {
        private MessageDigestHolder() {
        }

        static byte[] digest(byte[] bytes) {
            try {
                return MessageDigest.getInstance("SHA-256").digest(bytes);
            } catch (java.security.NoSuchAlgorithmException exception) {
                throw new IllegalStateException(exception);
            }
        }
    }
}
