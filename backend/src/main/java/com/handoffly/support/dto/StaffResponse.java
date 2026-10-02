package com.handoffly.support.dto;

import com.handoffly.support.staff.SupportPermission;
import com.handoffly.support.staff.SupportRole;
import com.handoffly.support.staff.SupportStaff;

import java.util.List;

/**
 * The signed-in staff member as the support portal sees them. {@code permissions} is what their role allows —
 * the portal shows only those controls, so it never has to know the role table itself (the backend still
 * enforces every permission on every request).
 */
public record StaffResponse(String staffCode, String name, String email, SupportRole role, List<SupportPermission> permissions) {
    public static StaffResponse from(SupportStaff staff) {
        return new StaffResponse(staff.getStaffCode(), staff.getName(), staff.getEmail(), staff.getRole(),
                List.copyOf(staff.getRole().permissions()));
    }
}
