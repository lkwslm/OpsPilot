package io.github.opspilot.tools.defaults;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

final class ObservabilityToolArchitectureTest {
    @Test
    void toolImplementationHasNoVendorClientOrFrameworkDependency() throws Exception {
        Path sourceRoot = Path.of(System.getProperty("user.dir"), "src", "main", "java");
        try (var files = Files.walk(sourceRoot)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file).toLowerCase(java.util.Locale.ROOT);
                assertFalse(source.contains("import io.prometheus"), file.toString());
                assertFalse(source.contains("import io.jaeger"), file.toString());
                assertFalse(source.contains("import org.springframework"), file.toString());
            }
        }
    }
}
