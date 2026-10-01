package com.handoffly.common.util;

import java.security.SecureRandom;

/**
 * Human-friendly public codes for handoffs. The code shown to users is a readable,
 * sequential {@code HO-<id>} (e.g. {@code HO-1}, {@code HO-2}), derived from the record's
 * own id. A short random {@link #temporary()} value is used only to satisfy the unique
 * NOT NULL constraint on the very first insert, before the id is known; it is immediately
 * replaced with the sequential code.
 *
 * <p>The public code is a display reference only — it never grants access. Recipient
 * access is controlled by opaque, hashed, single-handoff tokens, so a guessable code
 * exposes no data.
 */
public final class PublicCode {

    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private PublicCode() {}

    /** The readable, sequential code for a persisted handoff. */
    public static String forId(long id) {
        return "HO-" + id;
    }

    /** A unique placeholder used only until the id is assigned (never shown long-term). */
    public static String temporary() {
        StringBuilder sb = new StringBuilder("TMP-");
        for (int i = 0; i < 10; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
