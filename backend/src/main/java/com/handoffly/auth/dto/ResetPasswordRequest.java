package com.handoffly.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Choosing a new password from a reset link — no old password needed, the link is the proof. Never logged. */
public record ResetPasswordRequest(
        @NotBlank @Size(max = 200) String token,
        @NotBlank @Size(max = 200) String newPassword,
        @NotBlank @Size(max = 200) String confirmPassword
) {
    @Override
    public String toString() {
        return "ResetPasswordRequest[***]";
    }
}
