package io.github.opspilot.core.provider;

import io.github.opspilot.core.application.provider.ChatModelProviderRegistry;
import io.github.opspilot.core.application.provider.EmbeddingProviderRegistry;
import io.github.opspilot.core.application.provider.ProviderRegistryContracts.CapabilityKind;
import io.github.opspilot.core.application.provider.ProviderRegistryContracts.ProtocolVersion;
import io.github.opspilot.core.application.provider.ProviderRegistryContracts.ProviderCapabilityKey;
import io.github.opspilot.core.application.provider.ProviderRegistryContracts.ProviderRegistryException;
import io.github.opspilot.core.application.provider.ProviderRegistryContracts.ProviderRequirement;
import io.github.opspilot.core.application.provider.RerankProviderRegistry;
import io.github.opspilot.core.port.agent.ChatPort;
import io.github.opspilot.core.port.provider.EmbeddingPort;
import io.github.opspilot.core.port.provider.RerankPort;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ProviderRegistryContractTest {

    @Test
    void specializedRegistriesKeepProviderTypesAndCapabilitiesSeparate() {
        ChatPort chatProvider = request -> null;
        EmbeddingPort embeddingProvider = request -> null;
        RerankPort rerankProvider = request -> null;
        ProviderCapabilityKey chatCapability = capability(
                CapabilityKind.CHAT, "chat.completion", "chat-primary", 1, 1, "chat-model", "chat-r1");
        ProviderCapabilityKey embeddingCapability = capability(
                CapabilityKind.EMBEDDING, "embedding.batch", "embedding-primary", 1, 0,
                "embedding-model", "embedding-r1");
        ProviderCapabilityKey rerankCapability = capability(
                CapabilityKind.RERANK, "rerank.documents", "rerank-primary", 1, 0,
                "rerank-model", "rerank-r1");

        ChatModelProviderRegistry chat = new ChatModelProviderRegistry();
        chat.register("chat-primary", chatProvider, Set.of(chatCapability));
        chat.freeze(Set.of(requirement("chat-primary", capability(
                CapabilityKind.CHAT, "chat.completion", "chat-primary", 1, 0,
                "chat-model", "chat-r1"))));

        EmbeddingProviderRegistry embedding = new EmbeddingProviderRegistry();
        embedding.register("embedding-primary", embeddingProvider, Set.of(embeddingCapability));
        embedding.freeze(Set.of(requirement("embedding-primary", embeddingCapability)));

        RerankProviderRegistry rerank = new RerankProviderRegistry();
        rerank.register("rerank-primary", rerankProvider, Set.of(rerankCapability));
        rerank.freeze(Set.of(requirement("rerank-primary", rerankCapability)));

        assertSame(chatProvider, chat.require(chatCapability));
        assertSame(embeddingProvider, embedding.require(embeddingCapability));
        assertSame(rerankProvider, rerank.require(rerankCapability));
        assertEquals(Set.of(chatCapability), chat.capabilityKeys());
        assertTrue(chat.isFrozen());
    }

    @Test
    void duplicateStableIdAndWrongCapabilityFamilyFailDeterministically() {
        ChatModelProviderRegistry registry = new ChatModelProviderRegistry();
        ProviderCapabilityKey chatCapability = capability(
                CapabilityKind.CHAT, "chat.completion", "chat-primary", 1, 0, "chat-model", "chat-r1");
        registry.register("chat-primary", request -> null, Set.of(chatCapability));

        ProviderRegistryException duplicate = assertThrows(ProviderRegistryException.class,
                () -> registry.register("chat-primary", request -> null, Set.of(chatCapability)));
        assertEquals("DUPLICATE_PROVIDER_ID", duplicate.code());
        assertEquals("chat", duplicate.registryName());
        assertEquals("chat-primary", duplicate.stableProviderId());

        ProviderRegistryException wrongFamily = assertThrows(ProviderRegistryException.class,
                () -> new ChatModelProviderRegistry().register("embedding-primary", request -> null,
                        Set.of(capability(CapabilityKind.EMBEDDING, "embedding.batch", "embedding-primary",
                                1, 0, "embedding-model", "embedding-r1"))));
        assertEquals("CAPABILITY_KIND_MISMATCH", wrongFamily.code());
    }

    @Test
    void freezeRejectsMissingRequiredProviderAndIncompatibleProtocolVersion() {
        ChatModelProviderRegistry missing = new ChatModelProviderRegistry();
        ProviderRegistryException missingFailure = assertThrows(ProviderRegistryException.class,
                () -> missing.freeze(Set.of(
                        requirement("z-provider", capability(CapabilityKind.CHAT, "chat.completion",
                                "z-provider", 1, 0, "model", "r1")),
                        requirement("a-provider", capability(CapabilityKind.CHAT, "chat.completion",
                                "a-provider", 1, 0, "model", "r1")))));
        assertEquals("REQUIRED_PROVIDER_MISSING", missingFailure.code());
        assertEquals("a-provider", missingFailure.stableProviderId(),
                "unordered requirement input must still produce a deterministic first error");
        assertFalse(missing.isFrozen());

        ChatModelProviderRegistry incompatible = new ChatModelProviderRegistry();
        incompatible.register("chat-primary", request -> null, Set.of(capability(
                CapabilityKind.CHAT, "chat.completion", "chat-primary", 2, 0, "model", "r1")));
        ProviderRegistryException versionFailure = assertThrows(ProviderRegistryException.class,
                () -> incompatible.freeze(Set.of(requirement("chat-primary", capability(
                        CapabilityKind.CHAT, "chat.completion", "chat-primary", 1, 0, "model", "r1")))));
        assertEquals("REQUIRED_CAPABILITY_INCOMPATIBLE", versionFailure.code());
        assertFalse(incompatible.isFrozen());
    }

    @Test
    void frozenRegistryRejectsAddReplaceAndRemoveWithoutChangingMapping() {
        ChatPort original = request -> null;
        ProviderCapabilityKey capability = capability(
                CapabilityKind.CHAT, "chat.completion", "chat-primary", 1, 0, "model", "r1");
        ChatModelProviderRegistry registry = new ChatModelProviderRegistry();
        registry.register("chat-primary", original, Set.of(capability));
        registry.freeze(Set.of(requirement("chat-primary", capability)));

        assertEquals("PROVIDER_REGISTRY_FROZEN", assertThrows(ProviderRegistryException.class,
                () -> registry.register("chat-secondary", request -> null, Set.of(capability(
                        CapabilityKind.CHAT, "chat.completion", "chat-secondary", 1, 0, "model", "r1"))))
                .code());
        assertEquals("PROVIDER_REGISTRY_FROZEN", assertThrows(ProviderRegistryException.class,
                () -> registry.replace("chat-primary", request -> null, Set.of(capability))).code());
        assertEquals("PROVIDER_REGISTRY_FROZEN", assertThrows(ProviderRegistryException.class,
                () -> registry.remove("chat-primary")).code());
        assertSame(original, registry.require(capability));
    }

    private static ProviderRequirement requirement(String providerId, ProviderCapabilityKey capability) {
        return new ProviderRequirement(providerId, Set.of(capability));
    }

    private static ProviderCapabilityKey capability(
            CapabilityKind kind,
            String capability,
            String providerId,
            int protocolMajor,
            int protocolMinor,
            String model,
            String revision) {
        return new ProviderCapabilityKey(kind, capability, providerId, "http-json",
                new ProtocolVersion(protocolMajor, protocolMinor), model, revision);
    }
}
