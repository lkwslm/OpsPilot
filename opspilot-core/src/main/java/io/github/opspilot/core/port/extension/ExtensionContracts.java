package io.github.opspilot.core.port.extension;

import java.util.Set;

/** Stable descriptors for explicit composition-root assembly; deliberately no registry or discovery API. */
public final class ExtensionContracts {
    private ExtensionContracts() {
    }

    public record ExtensionDescriptor(String extensionId, String version, Set<String> capabilities) {
        public ExtensionDescriptor { capabilities = Set.copyOf(capabilities); }
    }

    public interface ProviderExtension { ExtensionDescriptor descriptor(); }
    public interface ToolExtension { ExtensionDescriptor descriptor(); }
    public interface SourceExtension { ExtensionDescriptor descriptor(); }
    public interface AnalyzerExtension { ExtensionDescriptor descriptor(); }
    public interface SandboxExtension { ExtensionDescriptor descriptor(); }
}
