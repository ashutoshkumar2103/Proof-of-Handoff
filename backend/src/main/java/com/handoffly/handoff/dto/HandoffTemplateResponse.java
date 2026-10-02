package com.handoffly.handoff.dto;

import com.handoffly.handoff.Handoff;

import java.math.BigDecimal;
import java.util.List;

/**
 * What a new draft can start from when an existing handoff is duplicated: only the reusable description of it —
 * title, category, purpose, who is sending, and the items with the quantities that went out. The server decides
 * what is reusable, here, in one place. Deliberately left out: the handoff's ID and reference, its status and
 * dates, the recipient and their link, the acknowledgement, returns, missing and condition history, disputes,
 * attachments, the audit history, and item-specific details (serial numbers, notes, condition). The client puts
 * this into the ordinary New Handoff form; nothing exists until the customer saves it through the normal flow.
 */
public record HandoffTemplateResponse(
        String title,
        String category,
        String purpose,
        String senderName,
        String senderOrganization,
        List<Item> items
) {
    /** What distinguishes a copy in lists, and the longest a title may be. */
    static final String COPY_SUFFIX = " (copy)";
    private static final int MAX_TITLE = 200;

    public record Item(String name, BigDecimal quantity, String unit) {}

    public static HandoffTemplateResponse from(Handoff source) {
        String title = source.getTitle();
        String copyTitle = title.length() + COPY_SUFFIX.length() <= MAX_TITLE
                ? title + COPY_SUFFIX
                : title.substring(0, MAX_TITLE - COPY_SUFFIX.length()) + COPY_SUFFIX;
        return new HandoffTemplateResponse(
                copyTitle, source.getCategory(), source.getPurpose(), source.getSenderName(), source.getSenderOrganization(),
                source.getItems().stream()
                        .map(i -> new Item(i.getName(), i.getQuantity(), i.getUnit()))   // the quantity that went OUT, not what remains
                        .toList());
    }
}
