package com.handoffly.notification;

/**
 * The facts about a support ticket that its emails mention. Defined here so the notification module
 * does not depend on the support module's entities. {@code customerCanReply} says whether the customer's
 * plan lets them open the ticket and answer in the app (a plain support message cannot be answered there).
 */
public record TicketNotice(
        String ticketCode,
        String subject,
        String category,
        String accountCode,
        String customerName,
        String customerEmail,
        String customerPhone,
        String plan,
        String priority,
        String contactMethod,
        String handoffReference,
        boolean customerCanReply
) {}
