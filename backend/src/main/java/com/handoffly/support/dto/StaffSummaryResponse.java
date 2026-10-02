package com.handoffly.support.dto;

import com.handoffly.support.staff.SupportRole;
import com.handoffly.support.staff.SupportStaff;

import java.time.Instant;

/** A staff member as an administrator sees them. Never any password hash or credential. */
public record StaffSummaryResponse(
        String staffCode,
        String name,
        String email,
        SupportRole role,
        boolean active,
        Instant createdAt
) {
    public static StaffSummaryResponse from(SupportStaff s) {
        return new StaffSummaryResponse(s.getStaffCode(), s.getName(), s.getEmail(), s.getRole(), s.isActive(), s.getCreatedAt());
    }
}
