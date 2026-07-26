package io.github.opspilot.adapters.retrieval.infinity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Objects;

/** Versioned text normalization used by the embedding reuse key. */
public final class NormalizedTextHasher {
    public static final String NORMALIZATION_VERSION = "embedding-text-nfkc-lf-trim-v1";

    private NormalizedTextHasher() { }

    public static String normalize(String text) {
        Objects.requireNonNull(text, "text");
        return Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .strip();
    }

    public static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalize(text).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
