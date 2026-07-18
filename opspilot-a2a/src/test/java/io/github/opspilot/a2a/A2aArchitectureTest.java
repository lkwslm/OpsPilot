package io.github.opspilot.a2a;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

final class A2aArchitectureTest {

    @Test
    void clientCannotDependOnServerOrSpringBeanTypes() {
        var classes = new ClassFileImporter().importPackages("io.github.opspilot.a2a");
        noClasses()
                .that().resideInAPackage("..a2a.client..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "..a2a.server..", "org.springframework..")
                .check(classes);
    }
}
