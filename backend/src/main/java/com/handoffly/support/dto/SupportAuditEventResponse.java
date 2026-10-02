package com.handoffly.support.dto;

import com.handoffly.support.SupportAuditEvent;
import com.handoffly.support.SupportAuditEventType;

import java.time.Instant;

/**
 * One recorded change, read-only: what it was, who it was done to ({@code subjectCode} is an Account ID or
 * a Staff ID), who did it, and why.
 */
public record SupportAuditEventResponse(
        SupportAuditEventType type,
        String subjectCode,
        String previousValue,
        String newValue,
        String staffCode,
        String staffName,
        String reason,
        Instant at
) {
    public static SupportAuditEventResponse from(SupportAuditEvent e) {
        return new SupportAuditEventResponse(
                e.getType(), e.subjectCode(), e.getPreviousValue(), e.getNewValue(),
                e.getStaff().getStaffCode(), e.getStaff().getName(), e.getReason(), e.getCreatedAt());
    }
}
