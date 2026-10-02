package com.handoffly.support.dto;

import java.util.List;

/** One ticket with its original request, the replies so far and any attached files. */
public record TicketDetailResponse(
        TicketSummaryResponse ticket,
        String description,
        List<TicketMessageResponse> messages,
        List<TicketAttachmentResponse> attachments
) {}
