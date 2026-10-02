package com.handoffly.auth;

import com.handoffly.common.error.BadRequestException;

import java.nio.charset.StandardCharsets;

/**
 * What a new password has to look like — the one place that says so for changing and resetting a password. It
 * matches registration (at least 8 characters) and adds the limit BCrypt really has: only the first 72 bytes of
 * a password count, so a longer one is refused instead of being silently cut short.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_BYTES = 72;

    private PasswordPolicy() {}

    /** @throws BadRequestException if the password is too short or too long, or the confirmation differs */
    public static void require(String password, String confirmation) {
        if (password.length() < MIN_LENGTH) {
            throw new BadRequestException("The new password must be at least " + MIN_LENGTH + " characters.");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new BadRequestException("The new password is too long (at most " + MAX_BYTES + " bytes).");
        }
        if (!password.equals(confirmation)) {
            throw new BadRequestException("The new password and its confirmation do not match.");
        }
    }
}
