package com.handoffly.handoff;

import com.handoffly.handoff.ReportQuestion.Condition;
import com.handoffly.handoff.ReportQuestion.Grouping;
import com.handoffly.handoff.ReportQuestion.Metric;
import com.handoffly.handoff.ReportQuestion.Operation;

import java.util.Arrays;
import java.util.List;

/**
 * The ready-made questions the Report Assistant offers as buttons: each is just a {@link ReportQuestion} with a short wording, answered by the
 * same code as a question typed in the customer's own words — only the AI's step of working out what was meant is skipped. They are
 * shortcuts and examples, not a list of what may be asked.
 */
public enum ReportIntent {
    HANDOFFS_CLOSED("How many handoffs were closed?", Metric.HANDOFFS, Operation.TOTAL, Condition.CLOSED, Grouping.NONE),
    ITEMS_GIVEN("How many items were handed over?", Metric.ITEMS_GIVEN, Operation.TOTAL, Condition.ANY, Grouping.NONE),
    ITEMS_MISSING("How many items are missing?", Metric.ITEMS_MISSING, Operation.TOTAL, Condition.ANY, Grouping.NONE),
    ITEMS_STILL_OUT("How many items are pending?", Metric.ITEMS_STILL_OUT, Operation.TOTAL, Condition.ANY, Grouping.NONE),
    OVERDUE_ITEMS("How many total overdue items are there?", Metric.ITEMS_STILL_OUT, Operation.TOTAL, Condition.OVERDUE, Grouping.NONE),
    OVERDUE_HANDOFF_LIST("What is overdue?", Metric.HANDOFFS, Operation.LIST, Condition.OVERDUE, Grouping.HANDOFF),
    MISSING_ITEM_HANDOFFS("Which handoffs have missing items?", Metric.ITEMS_MISSING, Operation.LIST, Condition.ANY, Grouping.HANDOFF);

    private final String suggestion;
    private final ReportQuestion question;

    ReportIntent(String suggestion, Metric metric, Operation operation, Condition condition, Grouping groupBy) {
        this.suggestion = suggestion;
        this.question = new ReportQuestion(metric, operation, condition, groupBy, 1, null, null);
    }

    /** The short question the page shows on the button. */
    public String suggestion() { return suggestion; }

    /** What the question asks, ready to run. */
    public ReportQuestion question() { return question; }

    /** The ready-made questions the page offers. */
    public static List<ReportIntent> suggested() {
        return Arrays.asList(values());
    }
}
