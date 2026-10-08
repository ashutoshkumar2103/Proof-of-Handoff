package com.handoffly.handoff.dto;

import com.handoffly.handoff.ReportIntent;
import jakarta.validation.constraints.Size;

/**
 * A question for the Report Assistant. It is about ALL of the customer's handoffs unless the question itself names a period ("between 1 Oct
 * and 10 Oct"): nothing about the page it is asked from narrows it, so the customer is free to ask anything. {@code timezone} is only the
 * customer's own time zone, for what "today" and a named day mean. Either a {@code question} in the customer's own words, or one of the
 * page's ready-made {@code suggestion}s (which needs no AI at all).
 */
public record ReportQuestionRequest(
        String timezone,
        @Size(max = 300) String question,
        ReportIntent suggestion
) {}
