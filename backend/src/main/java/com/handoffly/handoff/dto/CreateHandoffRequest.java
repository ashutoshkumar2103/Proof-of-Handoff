package com.handoffly.handoff.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * Creates a handoff in DRAFT status. Items are optional at creation and can be edited
 * while the handoff remains a draft.
 */
public record CreateHandoffRequest(
        @NotBlank @Size(max = 200) String title,
        @Size(max = 2000) String purpose,
        @Size(max = 60) String category,
        @NotBlank @Size(max = 200) String senderName,
        @Size(max = 200) String senderOrganization,
        @NotBlank @Size(max = 200) String recipientName,
        @NotBlank @Email @Size(max = 255) String recipientEmail,
        @Size(max = 40) String recipientPhone,
        Instant dueAt,
        List<@Valid HandoffItemRequest> items
) {}
