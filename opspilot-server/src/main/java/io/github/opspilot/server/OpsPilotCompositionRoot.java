package io.github.opspilot.server;

import io.github.opspilot.core.application.evidence.EvidenceNormalizer;
import io.github.opspilot.core.application.evidence.RuntimeEvidenceNormalizer;
import io.github.opspilot.core.port.extension.ExtensionContracts.ExtensionDescriptor;

import java.util.List;

/** The only production entry point allowed to construct concrete OpsPilot components. */
public final class OpsPilotCompositionRoot {
    private OpsPilotCompositionRoot() {
    }

    public static Components composeCore() {
        return composeCore(List.of());
    }

    /** Extensions are passed explicitly by deployment configuration; there is no runtime discovery. */
    public static Components composeCore(List<ExtensionDescriptor> extensions) {
        return new Components(new RuntimeEvidenceNormalizer(), extensions);
    }

    public record Components(EvidenceNormalizer evidenceNormalizer, List<ExtensionDescriptor> extensions) {
        public Components { extensions = List.copyOf(extensions); }
    }
}
