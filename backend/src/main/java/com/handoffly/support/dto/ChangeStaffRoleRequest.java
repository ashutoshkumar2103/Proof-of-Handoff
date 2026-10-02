package com.handoffly.support.dto;

import com.handoffly.support.staff.SupportRole;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * An explicit, confirmed role change: "this staff member is {@code fromRole} and should be {@code toRole}".
 * Naming the role the administrator was looking at means a change made on a stale view is refused.
 */
public record ChangeStaffRoleRequest(
        @NotNull SupportRole fromRole,
        @NotNull SupportRole toRole,
        @Size(max = 500) String reason
) {}
