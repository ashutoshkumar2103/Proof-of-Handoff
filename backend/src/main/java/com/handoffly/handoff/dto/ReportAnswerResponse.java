package com.handoffly.handoff.dto;

import com.handoffly.handoff.ReportIntent;
import com.handoffly.handoff.ReportQuestion;

import java.math.BigDecimal;
import java.util.List;

/**
 * The Report Assistant's answer. {@code answer} is the sentence to show. Every number and every handoff in it comes from the report's own
 * figures for the handoffs named by {@code basis} (all of them, or those created in the period the question names) — the AI only worked out which question was being asked ({@code query}) — and
 * {@code value} / {@code entries} carry the same facts in structure ({@code entryTotal} is how many there were, of which {@code entries}
 * holds the first few). {@code kind} says whether this is an answer, a question the report cannot answer, or no answer because the AI was
 * not available (then the report itself is untouched and {@code canRetry} says whether asking again may work).
 */
public record ReportAnswerResponse(
        Kind kind,
        String answer,
        ReportQuestion query,
        String basis,
        BigDecimal value,
        List<Entry> entries,
        int entryTotal,
        boolean canRetry
) {
    public enum Kind { ANSWER, UNSUPPORTED, UNAVAILABLE }

    /** One handoff in an answer that is a list. */
    public record Entry(String reference, String title, String recipient, String detail) {}

    /** A ready-made question the page offers. */
    public record Suggestion(ReportIntent intent, String question) {}
}
