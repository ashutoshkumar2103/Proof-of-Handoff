package com.handoffly.auth;

import com.handoffly.user.Role;

/**
 * Lightweight authenticated principal derived from the JWT — no per-request DB hit.
 * Exposed to controllers via {@code @AuthenticationPrincipal}.
 */
public record UserPrincipal(Long id, String email, Role role) {
}
