package com.handoffly.auth.dto;

import java.time.Instant;

public record AuthResponse(
        String token,
        Instant expiresAt,
        UserResponse user
) {}
