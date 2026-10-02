package com.handoffly.support.staff;

/**
 * The signed-in SUPPORT STAFF member, rebuilt from the database on every support request (so a role
 * change or deactivation applies at once). Exposed to support controllers via {@code @AuthenticationPrincipal}.
 * Customers are a different identity ({@code UserPrincipal}).
 */
public record StaffPrincipal(Long id, String staffCode, String email, SupportRole role) {

    public boolean can(SupportPermission permission) {
        return role.can(permission);
    }
}
