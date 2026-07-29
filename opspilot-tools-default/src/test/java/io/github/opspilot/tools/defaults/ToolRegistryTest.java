package io.github.opspilot.tools.defaults;

import io.github.opspilot.core.application.profile.AgentProfile.Permission;
import io.github.opspilot.tools.defaults.ObservabilityToolContracts.ToolResult;
import io.github.opspilot.tools.defaults.ObservabilityToolContracts.ToolStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ToolRegistryTest {
    private static final Set<String> NINE = Set.of(
            "LogQueryTool", "MetricQueryTool", "TraceQueryTool", "HealthQueryTool",
            "TopologyQueryTool", "ConfigReadTool", "CodeSearchTool",
            "KnowledgeSearchTool", "SandboxTestTool");

    @Test
    void nineBuiltInsExposeStableSchemasPermissionsAndFourStatesThenFreeze() {
        List<ObservabilityQueryTool> observable = NINE.stream().filter(id -> id.endsWith("QueryTool")
                        && !id.equals("CodeSearchTool") && !id.equals("KnowledgeSearchTool"))
                .map(ToolRegistryTest::stubObservable).toList();
        // ConfigReadTool does not end in QueryTool.
        observable = new java.util.ArrayList<>(observable);
        observable.add(stubObservable("ConfigReadTool"));
        ControlledTool code = ControlledTool.codeSearch(request ->
                ControlledTool.result(ToolStatus.SUCCEEDED, "code", null));
        ControlledTool knowledge = ControlledTool.knowledgeSearch(request ->
                ControlledTool.result(ToolStatus.EMPTY, "empty", null));
        ControlledTool sandbox = ControlledTool.sandboxTest(request -> { throw new ControlledTool.DeniedException(); });

        ToolRegistry registry = ToolRegistry.builtIns(observable, code, knowledge, sandbox);
        registry.freeze(NINE.stream().collect(java.util.stream.Collectors.toMap(id -> id, id -> 1)));
        assertEquals(NINE, registry.toolIds());
        NINE.forEach(id -> {
            var descriptor = registry.require(id, 1).descriptor();
            assertTrue(descriptor.inputSchemaId().contains("#/$defs/"));
            assertTrue(descriptor.outputSchemaId().endsWith("#/$defs/result"));
        });
        assertEquals(Permission.READ_ONLY, code.descriptor().permission());
        assertEquals(Permission.CONTROLLED_EXECUTION, sandbox.descriptor().permission());
        assertEquals(ToolStatus.SUCCEEDED, code.execute(new Object()).status());
        assertEquals(ToolStatus.EMPTY, knowledge.execute(new Object()).status());
        assertEquals(ToolStatus.DENIED, sandbox.execute(new Object()).status());
        ControlledTool failed = ControlledTool.codeSearch(request -> { throw new IllegalStateException(); });
        assertEquals(ToolStatus.FAILED, failed.execute(new Object()).status());
        assertEquals("TOOL_REGISTRY_FROZEN", assertThrows(ToolRegistry.ToolRegistryException.class,
                () -> registry.register(failed)).code());
    }

    @Test
    void duplicateMissingAndIncompatibleMajorFailDeterministically() {
        ToolRegistry duplicate = new ToolRegistry();
        duplicate.register(stubObservable("LogQueryTool"));
        assertEquals("DUPLICATE_TOOL_ID", assertThrows(ToolRegistry.ToolRegistryException.class,
                () -> duplicate.register(stubObservable("LogQueryTool"))).code());

        ToolRegistry missing = new ToolRegistry();
        assertEquals("REQUIRED_TOOL_MISSING", assertThrows(ToolRegistry.ToolRegistryException.class,
                () -> missing.freeze(Map.of("LogQueryTool", 1))).code());

        ToolRegistry incompatible = new ToolRegistry();
        incompatible.register(stubObservable("LogQueryTool"));
        assertEquals("TOOL_CONTRACT_MAJOR_INCOMPATIBLE", assertThrows(ToolRegistry.ToolRegistryException.class,
                () -> incompatible.freeze(Map.of("LogQueryTool", 2))).code());
    }

    private static ObservabilityQueryTool stubObservable(String name) {
        return new ObservabilityQueryTool() {
            @Override public String name() { return name; }
            @Override public String inputSchemaVersion() { return "1.0.0"; }
            @Override public ToolResult execute(ObservabilityToolContracts.ToolRequest request) {
                return ControlledTool.result(ToolStatus.EMPTY, "empty", null);
            }
        };
    }
}
