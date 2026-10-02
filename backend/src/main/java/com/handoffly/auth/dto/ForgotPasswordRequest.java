package com.handoffly.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Asking for a password reset link: just the email. The answer never says whether an account has it. */
public record ForgotPasswordRequest(@NotBlank @Email @Size(max = 255) String email) {}
