package io.github.opspilot.core.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import io.github.opspilot.core.fixture.ForbiddenDependencyFixtures;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.Map;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CoreArchitectureTest {

    private static final Map<String, String> FORBIDDEN_CATEGORIES = Map.of(
            "Spring", "org.springframework..",
            "AgentScope", "io.agentscope..",
            "A2A SDK", "org.a2aproject.sdk..",
            "JPA", "jakarta.persistence..",
            "Prometheus", "io.prometheus..",
            "Jaeger", "io.jaegertracing..",
            "Infinity", "ai.infinity..",
            "vendor SDK", "com.openai..");

    private static final String[] FORBIDDEN_PACKAGES = {
            "org.springframework..",
            "io.agentscope..",
            "com.alibaba.agentscope..",
            "org.agentscope..",
            "io.a2a..",
            "com.google.a2a..",
            "org.a2a..",
            "jakarta.persistence..",
            "javax.persistence..",
            "org.hibernate..",
            "io.prometheus..",
            "io.jaegertracing..",
            "ai.infinity..",
            "com.openai..",
            "com.azure.ai.openai..",
            "com.alibaba.dashscope..",
            "software.amazon.awssdk..",
            "com.google.cloud..",
            "co.elastic.clients..",
            "org.elasticsearch..",
            "org.postgresql.."
    };

    private static final ArchRule CORE_DEPENDENCY_RULE = noClasses()
            .that().resideInAnyPackage("io.github.opspilot.core..")
            .should().dependOnClassesThat().resideInAnyPackage(FORBIDDEN_PACKAGES)
            .because("core must remain independent of frameworks, protocols, persistence, telemetry, and vendor SDKs");

    @Test
    void productionCoreHasNoForbiddenDependencies() {
        JavaClasses productionCore = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.opspilot.core");

        CORE_DEPENDENCY_RULE.check(productionCore);
    }

    @TestFactory
    Stream<DynamicTest> negativeControlsProveEveryForbiddenCategoryIsRejected() {
        JavaClasses forbiddenFixture = new ClassFileImporter()
                .importClasses(ForbiddenDependencyFixtures.class);

        return FORBIDDEN_CATEGORIES.entrySet().stream().map(entry -> DynamicTest.dynamicTest(
                entry.getKey(), () -> {
                    ArchRule categoryRule = noClasses()
                            .that().resideInAnyPackage("io.github.opspilot.core..")
                            .should().dependOnClassesThat().resideInAnyPackage(entry.getValue());
                    AssertionError violation = assertThrows(
                            AssertionError.class, () -> categoryRule.check(forbiddenFixture));
                    assertTrue(violation.getMessage().contains(entry.getValue().replace("..", ""))
                                    || violation.getMessage().contains(entry.getKey()),
                            violation.getMessage());
                }));
    }

    @Test
    void domainAndPortPackagesCannotDependOnApplicationImplementations() {
        JavaClasses productionCore = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.github.opspilot.core");

        noClasses().that().resideInAnyPackage("..core.domain..", "..core.port..")
                .should().dependOnClassesThat().resideInAnyPackage("..core.application..")
                .check(productionCore);
    }
}
