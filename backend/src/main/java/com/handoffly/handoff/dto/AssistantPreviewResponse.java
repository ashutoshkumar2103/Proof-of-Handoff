package com.handoffly.handoff.dto;

import com.handoffly.handoff.HandoffIntent;

import java.util.List;

/**
 * What the AI Handoff Creator shows before anything is saved: a draft prepared from the customer's words and their own records, what is still
 * unclear, and notes about what is missing. Nothing here exists in the database; saving is a separate, explicit step. {@code draft} keeps every
 * piece that was understood even when something else needs an answer, so one open question never throws the rest away. {@code saveRequested} is true
 * when the customer's message asked to save the draft as well: the page then saves it (still only a draft) once nothing is left to answer.
 */
public record AssistantPreviewResponse(
        Status status,
        String message,
        Draft draft,
        List<Note> notes,
        List<Clarification> clarifications,
        HandoffIntent intent,
        boolean saveRequested
) {
    /** READY: nothing is unclear. NEEDS_INPUT: a question must be answered first. UNSUPPORTED: not a handoff request. UNAVAILABLE: the AI gave no answer. */
    public enum Status { READY, NEEDS_INPUT, UNSUPPORTED, UNAVAILABLE }

    /** The fields of a draft, as the existing New handoff form has them; text throughout, so a missing value is simply empty. */
    public record Draft(
            String title, String category, String purpose,
            String senderName, String senderOrganization,
            String recipientName, String recipientEmail, String recipientPhone,
            String dueDate,
            List<Item> items
    ) {}

    public record Item(String name, String quantity, String unit) {}

    /** Something to check, about one field ("recipientEmail", "items", "title"…). It never blocks saving a draft. */
    public record Note(String field, String text) {}

    /** A question with its answers. The answer is sent back as {@code choices[id] = option.id}. */
    public record Clarification(String id, String question, List<Option> options) {}

    public record Option(String id, String label) {}
}
