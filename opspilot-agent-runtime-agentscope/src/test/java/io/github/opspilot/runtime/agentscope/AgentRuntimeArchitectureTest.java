package io.github.opspilot.runtime.agentscope;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import io.github.opspilot.core.port.agent.AgentExecutionService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class AgentRuntimeArchitectureTest {

    private static final Pattern BUSINESS_LOOP = Pattern.compile("\\b(?:while|for)\\s*\\(");

    @Test
    void hasExactlyOneExecutionServiceAndItDelegatesToReactAgent() throws IOException {
        JavaClasses classes = new ClassFileImporter()
                .importPackages("io.github.opspilot.runtime.agentscope");
        List<String> implementations = classes.stream()
                .filter(type -> type.isAssignableTo(AgentExecutionService.class)
                        && !type.isInterface())
                .map(type -> type.getName())
                .toList();

        assertEquals(List.of(AgentScopeExecutionService.class.getName()), implementations);
        String source = Files.readString(Path.of(
                "src/main/java/io/github/opspilot/runtime/agentscope/AgentScopeExecutionService.java"));
        assertTrue(source.contains("agent.call(request.input(), runtimeContext)"));
        assertFalse(BUSINESS_LOOP.matcher(source).find(),
                "Agent execution adapter must not implement a second business loop");
    }
}
