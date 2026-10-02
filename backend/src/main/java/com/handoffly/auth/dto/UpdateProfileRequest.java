package com.handoffly.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * What a customer may edit about themselves. Not the Account ID, email, plan or handoff prefix: those are
 * fixed or controlled elsewhere, so they cannot be sent here at all.
 */
public record UpdateProfileRequest(
        @NotBlank @Size(max = 150) String displayName,
        @Size(max = 200) String organization,
        @Size(max = 40) String phone
) {}
