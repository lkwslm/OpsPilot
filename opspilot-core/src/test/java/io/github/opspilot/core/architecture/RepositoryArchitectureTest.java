package io.github.opspilot.core.architecture;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RepositoryArchitectureTest {
    private static final List<String> ROOT_MODULES = List.of(
            "opspilot-core", "opspilot-tools-default", "opspilot-agent-runtime-agentscope",
            "opspilot-a2a", "opspilot-adapters", "opspilot-server", "opspilot-evaluation",
            "sample-system", "fault-lab", "deployment");
    private static final List<String> ADAPTER_MODULES = List.of(
            "persistence-postgres", "model-openai-compatible", "retrieval-infinity",
            "knowledge-pgvector", "observability", "code-java", "sandbox-maven");

    @Test
    void frozenModuleListAndPackageSkeletonArePresent() throws Exception {
        Path root = projectRoot();
        assertEquals(ROOT_MODULES, modules(root.resolve("pom.xml")));
        assertEquals(ADAPTER_MODULES, modules(root.resolve("opspilot-adapters/pom.xml")));
        assertEquals("pom", text(root.resolve("opspilot-adapters/pom.xml"), "packaging"));
        assertFalse(Files.exists(root.resolve("opspilot-adapters/src/main/java")));
        for (String packageName : List.of("domain", "application", "port", "policy")) {
            assertTrue(Files.isRegularFile(root.resolve(
                    "opspilot-core/src/main/java/io/github/opspilot/core/" + packageName + "/package-info.java")));
        }
        for (String packageName : List.of("contract", "client", "server")) {
            assertTrue(Files.isRegularFile(root.resolve(
                    "opspilot-a2a/src/main/java/io/github/opspilot/a2a/" + packageName + "/package-info.java")));
        }
    }

    @Test
    void productionModuleDependenciesFollowTheFrozenDirection() throws Exception {
        Path root = projectRoot();
        for (String adapter : ADAPTER_MODULES) {
            Set<String> dependencies = dependencies(root.resolve("opspilot-adapters/" + adapter + "/pom.xml"));
            assertNoForbiddenDependency("adapter", dependencies);
        }
        assertNoForbiddenDependency("agent-runtime",
                dependencies(root.resolve("opspilot-agent-runtime-agentscope/pom.xml")));
        assertNoForbiddenDependency("a2a", dependencies(root.resolve("opspilot-a2a/pom.xml")));
        assertNoForbiddenDependency("evaluation", dependencies(root.resolve("opspilot-evaluation/pom.xml")));
    }

    @Test
    void negativeControlsRejectEveryReverseDependencyKind() {
        assertThrows(IllegalArgumentException.class,
                () -> assertNoForbiddenDependency("adapter", Set.of("opspilot-adapter-code-java")));
        assertThrows(IllegalArgumentException.class,
                () -> assertNoForbiddenDependency("adapter", Set.of("opspilot-server")));
        assertThrows(IllegalArgumentException.class,
                () -> assertNoForbiddenDependency("agent-runtime", Set.of("opspilot-server")));
        assertThrows(IllegalArgumentException.class,
                () -> assertNoForbiddenDependency("evaluation", Set.of("opspilot-adapter-persistence-postgres")));
    }

    @Test
    void serverIsTheOnlyCompositionRootAndAgentsCannotOwnSupervisorState() throws IOException {
        Path root = projectRoot();
        List<Path> roots = new ArrayList<>();
        for (String module : ROOT_MODULES) {
            Path source = root.resolve(module + "/src/main/java");
            if (!Files.exists(source)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(source)) {
                roots.addAll(files.filter(path -> path.getFileName().toString().endsWith("CompositionRoot.java"))
                        .toList());
            }
        }
        assertEquals(List.of(root.resolve(
                "opspilot-server/src/main/java/io/github/opspilot/server/OpsPilotCompositionRoot.java")), roots);

        for (String module : List.of("opspilot-agent-runtime-agentscope", "opspilot-a2a")) {
            Path source = root.resolve(module + "/src/main/java");
            if (!Files.exists(source)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(source)) {
                assertTrue(files.filter(path -> path.toString().endsWith(".java"))
                        .noneMatch(path -> read(path).contains("IncidentAgentStateRepository")));
            }
        }
    }

    private static void assertNoForbiddenDependency(String moduleKind, Set<String> dependencies) {
        Set<String> forbidden = new HashSet<>();
        if (moduleKind.equals("adapter")) {
            dependencies.stream().filter(name -> name.startsWith("opspilot-adapter-")
                    || name.equals("opspilot-server")).forEach(forbidden::add);
        } else if (moduleKind.equals("agent-runtime") || moduleKind.equals("a2a")) {
            dependencies.stream().filter(name -> name.startsWith("opspilot-adapter-")
                    || name.equals("opspilot-server")).forEach(forbidden::add);
        } else if (moduleKind.equals("evaluation")) {
            dependencies.stream().filter(name -> name.startsWith("opspilot-adapter-")
                    || name.equals("opspilot-server")).forEach(forbidden::add);
        }
        if (!forbidden.isEmpty()) {
            throw new IllegalArgumentException("Forbidden " + moduleKind + " dependencies: " + forbidden);
        }
    }

    private static Path projectRoot() {
        String reactorRoot = System.getProperty("maven.multiModuleProjectDirectory");
        if (reactorRoot != null) {
            return Path.of(reactorRoot).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("basedir")).toAbsolutePath().normalize().getParent();
    }

    private static List<String> modules(Path pom) throws Exception {
        NodeList nodes = document(pom).getElementsByTagName("module");
        List<String> values = new ArrayList<>();
        for (int index = 0; index < nodes.getLength(); index++) {
            values.add(nodes.item(index).getTextContent().strip());
        }
        return values;
    }

    private static Set<String> dependencies(Path pom) throws Exception {
        NodeList nodes = document(pom).getElementsByTagName("dependency");
        Set<String> values = new HashSet<>();
        for (int index = 0; index < nodes.getLength(); index++) {
            Element dependency = (Element) nodes.item(index);
            NodeList scopes = dependency.getElementsByTagName("scope");
            if (scopes.getLength() > 0 && "test".equals(scopes.item(0).getTextContent().strip())) {
                continue;
            }
            NodeList artifactIds = dependency.getElementsByTagName("artifactId");
            values.add(artifactIds.item(0).getTextContent().strip());
        }
        return values;
    }

    private static String text(Path pom, String tag) throws Exception {
        return document(pom).getElementsByTagName(tag).item(0).getTextContent().strip();
    }

    private static org.w3c.dom.Document document(Path pom) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        return factory.newDocumentBuilder().parse(pom.toFile());
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
