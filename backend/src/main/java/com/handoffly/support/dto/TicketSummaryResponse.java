package com.handoffly.support.dto;

import com.handoffly.support.ContactMethod;
import com.handoffly.support.SupportTicket;
import com.handoffly.support.TicketCategory;
import com.handoffly.support.TicketStatus;
import com.handoffly.user.SubscriptionPlan;
import com.handoffly.user.SupportPriority;
import com.handoffly.user.User;

import java.time.Instant;

/**
 * A ticket as listed, for the customer who owns it and for support. It holds what support needs to
 * answer — who, on which plan (and so at what priority, which follows the plan as it is now), about
 * what — and nothing from the customer's handoffs.
 */
public record TicketSummaryResponse(
        String ticketCode,
        String accountCode,
        String customerName,
        String customerEmail,
        String customerPhone,
        SubscriptionPlan plan,
        SupportPriority priority,
        ContactMethod contactMethod,
        TicketCategory category,
        String subject,
        String handoffReference,
        TicketStatus status,
        int messageCount,
        Instant createdAt,
        Instant updatedAt
) {
    public static TicketSummaryResponse from(SupportTicket t) {
        User account = t.getAccount();
        return new TicketSummaryResponse(
                t.getTicketCode(),
                account.getAccountCode(),
                account.getDisplayName(),
                account.getEmail(),
                t.getContactPhone(),
                account.getSubscriptionPlan(),
                account.entitledPlan().supportPriority(),   // a lapsed subscription is not priority
                t.getContactMethod(),
                t.getCategory(),
                t.getSubject(),
                t.getHandoffReference(),
                t.getStatus(),
                t.getMessageCount(),
                t.getCreatedAt(),
                t.getUpdatedAt());
    }
}
