package io.github.opspilot.a2a.contract;

import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentProvider;
import org.a2aproject.sdk.spec.AgentSkill;
import org.a2aproject.sdk.spec.TransportProtocol;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Six Phase 0 Agent Cards built from the locked official A2A Java SDK objects. */
public final class Phase0AgentCards {

    public static final String PROTOCOL_RELEASE = "v1.0.1";
    public static final String PROTOCOL_VERSION = "1.0";
    public static final String SDK_COORDINATE =
            "org.a2aproject.sdk:a2a-java-sdk-spec:1.1.0.Final";

    private static final List<Definition> DEFINITIONS = List.of(
            new Definition("supervisor", "OpsPilot Supervisor", "incident-investigation",
                    "http://opspilot-server:8080/a2a",
                    "application/vnd.opspilot.investigation-request+json;v=1",
                    List.of("application/vnd.opspilot.rca+json;v=1")),
            new Definition("evidence-collector", "Evidence Collector", "collect-observability-evidence",
                    "http://evidence-agent:8081/a2a",
                    "application/vnd.opspilot.evidence-request+json;v=1",
                    List.of("application/vnd.opspilot.evidence-bundle+json;v=1")),
            new Definition("code-analysis", "Code Analysis", "analyze-code-location",
                    "http://code-agent:8082/a2a",
                    "application/vnd.opspilot.code-analysis-request+json;v=1",
                    List.of("application/vnd.opspilot.code-findings+json;v=1",
                            "application/vnd.opspilot.evidence-bundle+json;v=1")),
            new Definition("knowledge", "Knowledge Retrieval", "retrieve-incident-knowledge",
                    "http://knowledge-agent:8083/a2a",
                    "application/vnd.opspilot.knowledge-request+json;v=1",
                    List.of("application/vnd.opspilot.knowledge-result+json;v=1",
                            "application/vnd.opspilot.evidence-bundle+json;v=1")),
            new Definition("diagnosis", "Diagnosis", "generate-and-verify-hypotheses",
                    "http://diagnosis-agent:8084/a2a",
                    "application/vnd.opspilot.diagnosis-request+json;v=1",
                    List.of("application/vnd.opspilot.diagnosis-assessment+json;v=1")),
            new Definition("remediation", "Remediation", "propose-remediation",
                    "http://remediation-agent:8085/a2a",
                    "application/vnd.opspilot.remediation-request+json;v=1",
                    List.of("application/vnd.opspilot.remediation-plan+json;v=1")));

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
                .description("OpsPilot Phase 0 " + definition.skillId())
                .tags(List.of("opspilot", "phase0"))
                .examples(List.of())
                .inputModes(List.of(definition.inputMode()))
                .outputModes(definition.outputModes())
                .securityRequirements(List.of())
                .build();
        AgentCapabilities capabilities = AgentCapabilities.builder()
                .streaming(true)
                .pushNotifications(true)
                .extendedAgentCard(false)
                .extensions(List.of())
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
                .securitySchemes(Map.of())
                .securityRequirements(List.of())
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
