package io.github.opspilot.a2a.contract;

import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentExtension;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentProvider;
import org.a2aproject.sdk.spec.AgentSkill;
import org.a2aproject.sdk.spec.APIKeySecurityScheme;
import org.a2aproject.sdk.spec.SecurityRequirement;
import org.a2aproject.sdk.spec.TransportProtocol;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Six versioned Agent Cards built from the locked official A2A Java SDK objects. */
public final class Phase0AgentCards {

    public static final String PROTOCOL_RELEASE = A2aProtocol.RELEASE;
    public static final String PROTOCOL_VERSION = A2aProtocol.VERSION;
    public static final String SDK_COORDINATE =
            "org.a2aproject.sdk:a2a-java-sdk-spec:1.1.0.Final";

    private static final List<Definition> DEFINITIONS = List.of(
            new Definition("supervisor", "OpsPilot Supervisor", "incident-investigation",
                    "http://opspilot-server:8080/a2a",
                    "application/vnd.opspilot.incident-investigation.request+json;v=1",
                    List.of("application/vnd.opspilot.incident-investigation.result+json;v=1")),
            new Definition("evidence-collector", "Evidence Collector", "collect-observability-evidence",
                    "http://evidence-agent:8081/a2a",
                    "application/vnd.opspilot.collect-observability-evidence.request+json;v=1",
                    List.of("application/vnd.opspilot.collect-observability-evidence.result+json;v=1")),
            new Definition("code-analysis", "Code Analysis", "analyze-code-location",
                    "http://code-agent:8082/a2a",
                    "application/vnd.opspilot.analyze-code-location.request+json;v=1",
                    List.of("application/vnd.opspilot.analyze-code-location.result+json;v=1")),
            new Definition("knowledge", "Knowledge Retrieval", "retrieve-incident-knowledge",
                    "http://knowledge-agent:8083/a2a",
                    "application/vnd.opspilot.retrieve-incident-knowledge.request+json;v=1",
                    List.of("application/vnd.opspilot.retrieve-incident-knowledge.result+json;v=1")),
            new Definition("diagnosis", "Diagnosis", "generate-and-verify-hypotheses",
                    "http://diagnosis-agent:8084/a2a",
                    "application/vnd.opspilot.generate-and-verify-hypotheses.request+json;v=1",
                    List.of("application/vnd.opspilot.generate-and-verify-hypotheses.result+json;v=1")),
            new Definition("remediation", "Remediation", "propose-remediation",
                    "http://remediation-agent:8085/a2a",
                    "application/vnd.opspilot.propose-remediation.request+json;v=1",
                    List.of("application/vnd.opspilot.propose-remediation.result+json;v=1")));

    private Phase0AgentCards() {
    }

    public static Map<String, AgentCard> create() {
        Map<String, AgentCard> cards = new LinkedHashMap<>();
        DEFINITIONS.forEach(definition -> cards.put(definition.id(), create(definition)));
        return Map.copyOf(cards);
    }

    private static AgentCard create(Definition definition) {
        AgentSkill skill = AgentSkill.builder()
                .id(definition.skillId())
                .name(definition.name())
                .description("OpsPilot " + definition.skillId())
                .tags(List.of("opspilot", "phase6"))
                .examples(List.of())
                .inputModes(List.of(definition.inputMode()))
                .outputModes(definition.outputModes())
                .securityRequirements(List.of(SecurityRequirement.builder()
                        .scheme("serviceIdentity", List.of())
                        .build()))
                .build();
        AgentExtension correlation = AgentExtension.builder()
                .uri(A2aProtocol.CORRELATION_EXTENSION)
                .description("Propagates authoritative OpsPilot correlation identifiers")
                .required(true)
                .params(Map.of("agentId", definition.id()))
                .build();
        AgentCapabilities capabilities = AgentCapabilities.builder()
                .streaming(true)
                .pushNotifications(true)
                .extendedAgentCard(false)
                .extensions(List.of(correlation))
                .build();
        AgentInterface agentInterface = new AgentInterface(
                TransportProtocol.HTTP_JSON.asString(),
                definition.url(),
                null,
                PROTOCOL_VERSION);
        return AgentCard.builder()
                .name(definition.name())
                .description("OpsPilot " + definition.id() + " Agent")
                .provider(new AgentProvider("OpsPilot", "https://github.com/opspilot"))
                .version("1.0.0")
                .documentationUrl("https://opspilot.local/docs/agents/" + definition.id())
                .capabilities(capabilities)
                .defaultInputModes(List.of(definition.inputMode()))
                .defaultOutputModes(definition.outputModes())
                .skills(List.of(skill))
                .securitySchemes(Map.of("serviceIdentity", APIKeySecurityScheme.builder()
                        .location(APIKeySecurityScheme.Location.HEADER)
                        .name(A2aProtocol.SERVICE_ID_HEADER)
                        .description("Authenticated internal service identity")
                        .build()))
                .securityRequirements(List.of(SecurityRequirement.builder()
                        .scheme("serviceIdentity", List.of())
                        .build()))
                .supportedInterfaces(List.of(agentInterface))
                .signatures(List.of())
                .additionalInterfaces(List.of())
                .build();
    }

    private record Definition(
            String id,
            String name,
            String skillId,
            String url,
            String inputMode,
            List<String> outputModes) {
    }
}
