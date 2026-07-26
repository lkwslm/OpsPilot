package io.github.opspilot.server;

import io.github.opspilot.core.port.provider.SecretResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class EnvironmentFileSecretResolverTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesOnlyAuthorizedEnvironmentAndAbsoluteFileReferences() throws Exception {
        Path secretFile = temporaryDirectory.resolve("provider-key.txt");
        Files.writeString(secretFile, "file-secret\n");
        EnvironmentFileSecretResolver resolver = new EnvironmentFileSecretResolver(
                name -> Map.of("DEEPSEEK_API_KEY", "environment-secret").get(name), Files::readString);
        SecretResolver.ResolutionContext authorized =
                new SecretResolver.ResolutionContext("provider:deepseek", true);

        try (SecretResolver.ResolvedSecret environment = resolver.resolve("env:DEEPSEEK_API_KEY", authorized);
             SecretResolver.ResolvedSecret file = resolver.resolve("file:" + secretFile, authorized)) {
            assertArrayEquals("environment-secret".toCharArray(), environment.value());
            assertArrayEquals("file-secret".toCharArray(), file.value());
            assertEquals("ResolvedSecret[REDACTED]", environment.toString());
        }

        EnvironmentFileSecretResolver.SecretResolutionException unsupported = assertThrows(
                EnvironmentFileSecretResolver.SecretResolutionException.class,
                () -> resolver.resolve("literal:UNIQUE_SECRET_MARKER", authorized));
        assertEquals("SECRET_REF_SCHEME_UNSUPPORTED:literal", unsupported.getMessage());
        assertFalse(unsupported.getMessage().contains("UNIQUE_SECRET_MARKER"));
        assertEquals("SECRET_ACCESS_DENIED", assertThrows(
                EnvironmentFileSecretResolver.SecretResolutionException.class,
                () -> resolver.resolve("env:DEEPSEEK_API_KEY",
                        new SecretResolver.ResolutionContext("provider:deepseek", false))).getMessage());
        assertEquals("SECRET_VALUE_MISSING:env:NOT_SET", assertThrows(
                EnvironmentFileSecretResolver.SecretResolutionException.class,
                () -> resolver.resolve("env:NOT_SET", authorized)).getMessage());
        assertEquals("SECRET_FILE_PATH_NOT_ABSOLUTE", assertThrows(
                EnvironmentFileSecretResolver.SecretResolutionException.class,
                () -> resolver.resolve("file:relative.txt", authorized)).getMessage());
    }
}
