package com.handoffly.handoff.dto;

import com.handoffly.common.util.EmailAddress;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;

/**
 * Saves a draft that the customer has reviewed in the AI Handoff Creator. It differs from {@link CreateHandoffRequest} in one way only: the
 * recipient's name and email may be empty (they were not given), though an email that is given must still be a valid address. A draft can never be
 * submitted without them (see {@code HandoffService#submit}). {@code saveKey} is made by the page for each preview, so saving twice (a double click,
 * a retry after a lost response) gives back the draft already saved instead of a second one.
 */
public record AiDraftRequest(
        @NotBlank @Pattern(regexp = "[A-Za-z0-9-]{8,64}") String saveKey,
        @NotBlank @Size(max = 200) String title,
        @Size(max = 2000) String purpose,
        @Size(max = 60) String category,
        @NotBlank @Size(max = 200) String senderName,
        @Size(max = 200) String senderOrganization,
        @Size(max = 200) String recipientName,
        @Pattern(regexp = "|" + EmailAddress.REGEX, message = EmailAddress.MESSAGE) @Size(max = 255) String recipientEmail,
        @Size(max = 40) String recipientPhone,
        Instant dueAt,
        List<@Valid HandoffItemRequest> items
) {}
