package io.github.opspilot.core.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import io.github.opspilot.core.fixture.ForbiddenSpringDependency;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CoreArchitectureTest {

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

    @Test
    void negativeControlProvesTheRuleRejectsAForbiddenDependency() {
        JavaClasses forbiddenFixture = new ClassFileImporter()
                .importClasses(ForbiddenSpringDependency.class);

        AssertionError violation = assertThrows(
                AssertionError.class,
                () -> CORE_DEPENDENCY_RULE.check(forbiddenFixture));

        assertTrue(violation.getMessage().contains("org.springframework.context.ApplicationContext"));
    }
}
