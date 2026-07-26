package io.github.opspilot.server;

import io.github.opspilot.core.application.provider.ProviderRegistryContracts.CapabilityKind;
import io.github.opspilot.core.application.provider.ProviderRegistryContracts.ProtocolVersion;
import io.github.opspilot.core.application.provider.ProviderRegistryContracts.ProviderCapabilityKey;
import io.github.opspilot.core.application.provider.ProviderRegistryContracts.ProviderRegistryException;
import io.github.opspilot.core.application.provider.ProviderRegistryContracts.ProviderRequirement;
import io.github.opspilot.core.port.agent.ChatPort;
import io.github.opspilot.core.port.provider.EmbeddingPort;
import io.github.opspilot.core.port.provider.RerankPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ModelProviderCompositionRootTest {

    @Test
    void compositionRootExplicitlyRegistersAndFreezesThreeProviderFamilies() {
        ChatPort chat = request -> null;
        EmbeddingPort embedding = request -> null;
        RerankPort rerank = request -> null;
        ProviderCapabilityKey chatKey = key(CapabilityKind.CHAT, "chat.completion", "primary", 1);
        ProviderCapabilityKey embeddingKey = key(CapabilityKind.EMBEDDING, "embedding.batch", "primary", 1);
        ProviderCapabilityKey rerankKey = key(CapabilityKind.RERANK, "rerank.documents", "primary", 1);

        var registries = OpsPilotCompositionRoot.composeModelProviderRegistries(
                List.of(new OpsPilotCompositionRoot.ChatProviderRegistration("primary", chat, Set.of(chatKey))),
                List.of(new OpsPilotCompositionRoot.EmbeddingProviderRegistration(
                        "primary", embedding, Set.of(embeddingKey))),
                List.of(new OpsPilotCompositionRoot.RerankProviderRegistration("primary", rerank, Set.of(rerankKey))),
                new OpsPilotCompositionRoot.ModelProviderRequirements(
                        Set.of(requirement("primary", chatKey)),
                        Set.of(requirement("primary", embeddingKey)),
                        Set.of(requirement("primary", rerankKey))));

        assertSame(chat, registries.chat().require(chatKey));
        assertSame(embedding, registries.embedding().require(embeddingKey));
        assertSame(rerank, registries.rerank().require(rerankKey));
        assertTrue(registries.chat().isFrozen());
        assertTrue(registries.embedding().isFrozen());
        assertTrue(registries.rerank().isFrozen());
    }

    @Test
    void compositionFailsForDuplicateMissingAndIncompatibleProvidersWithStableErrors() {
        ProviderCapabilityKey chatKey = key(CapabilityKind.CHAT, "chat.completion", "chat-primary", 1);
        var duplicate = assertThrows(ProviderRegistryException.class,
                () -> OpsPilotCompositionRoot.composeModelProviderRegistries(
                        List.of(
                                new OpsPilotCompositionRoot.ChatProviderRegistration(
                                        "chat-primary", request -> null, Set.of(chatKey)),
                                new OpsPilotCompositionRoot.ChatProviderRegistration(
                                        "chat-primary", request -> null, Set.of(chatKey))),
                        List.of(), List.of(), requirements(requirement("chat-primary", chatKey))));
        assertEquals("DUPLICATE_PROVIDER_ID", duplicate.code());
        assertEquals("chat", duplicate.registryName());
        assertEquals("chat-primary", duplicate.stableProviderId());

        var missing = assertThrows(ProviderRegistryException.class,
                () -> OpsPilotCompositionRoot.composeModelProviderRegistries(
                        List.of(), List.of(), List.of(), requirements(
                                requirement("chat-z", key(CapabilityKind.CHAT, "chat.completion", "chat-z", 1)),
                                requirement("chat-a", key(CapabilityKind.CHAT, "chat.completion", "chat-a", 1)))));
        assertEquals("REQUIRED_PROVIDER_MISSING", missing.code());
        assertEquals("chat-a", missing.stableProviderId());

        ProviderCapabilityKey actualV2 = key(CapabilityKind.CHAT, "chat.completion", "chat-primary", 2);
        var incompatible = assertThrows(ProviderRegistryException.class,
                () -> OpsPilotCompositionRoot.composeModelProviderRegistries(
                        List.of(new OpsPilotCompositionRoot.ChatProviderRegistration(
                                "chat-primary", request -> null, Set.of(actualV2))),
                        List.of(), List.of(), requirements(requirement("chat-primary", chatKey))));
        assertEquals("REQUIRED_CAPABILITY_INCOMPATIBLE", incompatible.code());
    }

    @Test
    void composedRegistryCannotBeChangedAfterStartupFreeze() {
        ProviderCapabilityKey chatKey = key(CapabilityKind.CHAT, "chat.completion", "chat-primary", 1);
        var registries = OpsPilotCompositionRoot.composeModelProviderRegistries(
                List.of(new OpsPilotCompositionRoot.ChatProviderRegistration(
                        "chat-primary", request -> null, Set.of(chatKey))),
                List.of(), List.of(), requirements(requirement("chat-primary", chatKey)));

        ProviderRegistryException failure = assertThrows(ProviderRegistryException.class,
                () -> registries.chat().remove("chat-primary"));
        assertEquals("PROVIDER_REGISTRY_FROZEN", failure.code());
    }

    private static OpsPilotCompositionRoot.ModelProviderRequirements requirements(
            ProviderRequirement... chatRequirements) {
        return new OpsPilotCompositionRoot.ModelProviderRequirements(
                Set.of(chatRequirements), Set.of(), Set.of());
    }

    private static ProviderRequirement requirement(String providerId, ProviderCapabilityKey key) {
        return new ProviderRequirement(providerId, Set.of(key));
    }

    private static ProviderCapabilityKey key(
            CapabilityKind kind, String capability, String providerId, int protocolMajor) {
        return new ProviderCapabilityKey(kind, capability, providerId, "http-json",
                new ProtocolVersion(protocolMajor, 0), "locked-model", "locked-revision");
    }
}
