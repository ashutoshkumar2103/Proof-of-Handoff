package com.handoffly.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generation and hashing of high-entropy opaque tokens (e.g. recipient links).
 * Raw tokens are shown to the user exactly once; only their SHA-256 hash is stored,
 * so a database leak never reveals a usable link.
 */
public final class SecureTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private SecureTokens() {}

    /** @return a URL-safe random token with {@code numBytes} bytes of entropy. */
    public static String randomToken(int numBytes) {
        byte[] bytes = new byte[numBytes];
        RANDOM.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }

    /** @return lowercase hex SHA-256 of the given raw token, for at-rest storage. */
    public static String sha256Hex(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            // SHA-256 is always available on a compliant JVM.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Constant-time comparison to avoid timing side channels on hash lookups. */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
