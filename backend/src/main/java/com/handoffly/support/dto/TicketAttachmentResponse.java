package com.handoffly.support.dto;

import com.handoffly.support.SupportTicketAttachment;

import java.time.Instant;

public record TicketAttachmentResponse(
        Long id,
        String originalFilename,
        String contentType,
        long sizeBytes,
        Instant createdAt
) {
    public static TicketAttachmentResponse from(SupportTicketAttachment a) {
        return new TicketAttachmentResponse(
                a.getId(), a.getOriginalFilename(), a.getContentType(), a.getSizeBytes(), a.getCreatedAt());
    }
}
