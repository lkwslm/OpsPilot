package io.github.opspilot.a2a.contract;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Canonical request hash paired with messageId for persistent idempotency. */
public final class A2aRequestHash {

    private A2aRequestHash() {
    }

    public static String compute(A2aSendRequest request) {
        String canonical = request.contextId() + "\u0000" + request.text()
                + "\u0000" + request.deferCompletion();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
