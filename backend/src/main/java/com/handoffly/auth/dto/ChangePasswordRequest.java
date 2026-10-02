package com.handoffly.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A signed-in customer changing their own password: the current one must be right. Never logged. */
public record ChangePasswordRequest(
        @NotBlank @Size(max = 200) String currentPassword,
        @NotBlank @Size(max = 200) String newPassword,
        @NotBlank @Size(max = 200) String confirmPassword
) {
    @Override
    public String toString() {
        return "ChangePasswordRequest[***]";
    }
}
