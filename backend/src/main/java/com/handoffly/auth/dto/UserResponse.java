package com.handoffly.auth.dto;

import com.handoffly.user.Role;
import com.handoffly.user.User;

public record UserResponse(
        Long id,
        String email,
        String displayName,
        String organization,
        Role role
) {
    public static UserResponse from(User user) {
        return new UserResponse(
                user.getId(),
                user.getEmail(),
                user.getDisplayName(),
                user.getOrganization(),
                user.getRole());
    }
}
