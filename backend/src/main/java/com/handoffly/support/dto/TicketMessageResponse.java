package com.handoffly.support.dto;

import com.handoffly.support.MessageAuthor;
import com.handoffly.support.SupportTicketMessage;

import java.time.Instant;

public record TicketMessageResponse(
        Long id,
        MessageAuthor author,
        String authorName,
        String body,
        Instant createdAt
) {
    public static TicketMessageResponse from(SupportTicketMessage m) {
        return new TicketMessageResponse(
                m.getId(), m.getAuthorRole(), m.authorName(), m.getBody(), m.getCreatedAt());
    }
}
