package com.handoffly.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Public customer registration. There is deliberately no role (or plan) field: registration
 * always creates a USER on the default plan, whatever else a client sends.
 */
public record RegisterRequest(
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(min = 8, max = 100) String password,
        @NotBlank @Size(max = 150) String displayName,
        @Size(max = 200) String organization,
        @Size(max = 40) String phone
) {}
