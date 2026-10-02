package com.handoffly.support.dto;

import com.handoffly.user.SupportEntitlements;

import java.util.List;

/**
 * A customer's profile for support. The plan decides what the customer may use (the entitlements are
 * shown for information, never edited on their own); the plan and the handoff prefix are the two things
 * support may change, and every change appears in {@code recentChanges}.
 */
public record CustomerProfileResponse(
        CustomerSummaryResponse customer,
        SupportEntitlements entitlements,
        String nextHandoffReference,
        long openTickets,
        List<TicketSummaryResponse> recentTickets,
        List<SupportAuditEventResponse> recentChanges
) {}
