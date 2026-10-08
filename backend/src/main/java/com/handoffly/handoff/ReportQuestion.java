package com.handoffly.handoff;

import com.handoffly.handoff.dto.HandoffSummaryResponse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A question about the report, as a request the backend can run: WHAT to measure, what to DO with it, over WHICH handoffs, and grouped how.
 * This is all the AI ever produces — it picks one value for each part from the lists below, nothing else — and {@link #of} refuses any
 * request that is not one of these (a made-up value, a combination that means nothing). The backend then works the answer out from the report's
 * own rows ({@link ReportAssistantService}); there is no query language, so there is nothing for a question to inject into.
 * {@code from} and {@code to} are the days the question names, if it names any ("between 1 Oct and 10 Oct"): the handoffs looked at are those
 * created in that period, and with neither it is all of them. {@code description}
 * on each value is what the AI is told it means, so that ordinary wording ("given", "pending", "quotations") finds the right one.
 */
public record ReportQuestion(Metric metric, Operation operation, Condition condition, Grouping groupBy, int limit, LocalDate from, LocalDate to) {

    /** The most handoffs or recipients a "top" question may name. */
    public static final int MAX_LIMIT = 10;

    /** What is counted or added up, for each handoff (the figures are {@link HandoffReportService}'s: the same ones the report shows). */
    public enum Metric {
        HANDOFFS("the number of handoffs (use for 'how many handoffs', 'how many quotations/jobs/orders')", h -> BigDecimal.ONE),
        ITEMS_GIVEN("the quantity of items handed over / given out", HandoffReportService::itemsGiven),
        ITEMS_RETURNED("the quantity of items that have come back", HandoffReportService::itemsReturned),
        ITEMS_MISSING("the quantity of items reported missing", HandoffReportService::itemsMissing),
        ITEMS_STILL_OUT("the quantity of items still out: handed over and not back yet ('pending', 'outstanding', 'not returned', "
                + "and also what 'overdue items' means: the items not yet returned on overdue handoffs)", HandoffReportService::itemsStillOut);

        final String description;
        final Function<HandoffSummaryResponse, BigDecimal> measure;

        Metric(String description, Function<HandoffSummaryResponse, BigDecimal> measure) {
            this.description = description;
            this.measure = measure;
        }

        boolean isItems() { return this != HANDOFFS; }
    }

    /** What is done with the measure over the handoffs that match. */
    public enum Operation {
        TOTAL("one number: how many / how much in total"),
        LIST("which ones: the handoffs (or recipients) that match, named"),
        TOP("the one(s) with the most: 'which handoff has the most ...', 'which recipient has the most ...' (set limit for 'top 3')");

        final String description;

        Operation(String description) { this.description = description; }
    }

    /** Which handoffs are looked at. Only ever one of these; the period and the status filter of the report page still apply first. */
    public enum Condition {
        ANY("every handoff in the report", "", "", "was created", "were created", h -> true),
        OVERDUE("only overdue handoffs: past their return date and not fully returned", "overdue", "", "is overdue", "are overdue",
                HandoffSummaryResponse::overdue),
        OPEN("only handoffs still open (not closed, cancelled or rejected)", "open", "", "is still open", "are still open",
                h -> HandoffActivityService.OPEN_STATUSES.contains(h.status())),
        CLOSED("only closed handoffs", "closed", "", "is closed", "are closed", h -> h.status() == HandoffStatus.CLOSED),
        ACTIVE_WITH_RECIPIENT("only active handoffs: the recipient has accepted and has items now", "active", "",
                "is active with the recipient", "are active with the recipient", h -> h.status().isActiveWithRecipient()),
        HAS_MISSING("only handoffs that have missing items", "", "with missing items", "has missing items", "have missing items",
                h -> HandoffReportService.itemsMissing(h).signum() > 0),
        HAS_STILL_OUT("only handoffs that have items not yet returned", "", "with items still out", "has items still out", "have items still out",
                h -> HandoffReportService.itemsStillOut(h).signum() > 0),
        CANCELLED("only cancelled handoffs", "cancelled", "", "was cancelled", "were cancelled", h -> h.status() == HandoffStatus.CANCELLED),
        REJECTED("only handoffs the recipient rejected", "rejected", "", "was rejected", "were rejected", h -> h.status() == HandoffStatus.REJECTED);

        final String description;
        final String before;        // "overdue": goes before "handoffs"
        final String after;         // "with missing items": goes after "handoffs"
        final String singular;      // "is overdue": what is said of one such handoff
        final String plural;
        final Predicate<HandoffSummaryResponse> matches;

        Condition(String description, String before, String after, String singular, String plural, Predicate<HandoffSummaryResponse> matches) {
            this.description = description;
            this.before = before;
            this.after = after;
            this.singular = singular;
            this.plural = plural;
            this.matches = matches;
        }
    }

    /** What a LIST or TOP is about: each handoff, or each recipient (all of a recipient's handoffs together). NONE for a TOTAL. */
    public enum Grouping {
        NONE("no grouping: a single total"),
        HANDOFF("per handoff"),
        RECIPIENT("per recipient (the person the items were handed to)");

        final String description;

        Grouping(String description) { this.description = description; }
    }

    /** The parts of a question, each with its allowed values and what they mean: what the AI chooses from (it can choose nothing else). */
    public static Map<String, Map<String, String>> fields() {
        Map<String, Map<String, String>> fields = new LinkedHashMap<>();
        fields.put("metric", describe(Metric.values(), m -> m.description));
        fields.put("operation", describe(Operation.values(), o -> o.description));
        fields.put("condition", describe(Condition.values(), c -> c.description));
        fields.put("groupBy", describe(Grouping.values(), g -> g.description));
        Map<String, String> limits = new LinkedHashMap<>();
        for (int i = 1; i <= MAX_LIMIT; i++) limits.put(String.valueOf(i), i == 1 ? "one (the default)" : i + " (only when the question asks for the top " + i + ")");
        fields.put("limit", limits);
        return fields;
    }

    /** The days a question may name, and what they mean (the period is matched against when each handoff was created, like the report's). */
    public static Map<String, String> days() {
        Map<String, String> days = new LinkedHashMap<>();
        days.put("from", "the first day of the period the question names ('between 1 Oct and 10 Oct', 'this month', 'since March', 'last week', 'in 2025'); empty if it names none or only an end");
        days.put("to", "the last day of the period the question names (included); empty if it names none or only a start");
        return days;
    }

    private static <E extends Enum<E>> Map<String, String> describe(E[] values, Function<E, String> description) {
        Map<String, String> described = new LinkedHashMap<>();
        for (E value : values) described.put(value.name(), description.apply(value));
        return described;
    }

    /**
     * The request the chosen values make, or nothing when they do not make one the report can answer. Every value must be a known name
     * (anything else is refused, never guessed at), and a combination that means nothing is refused too: a TOP of handoffs per handoff, say.
     * A period that ends before it starts, or lies outside the dates a report accepts, is refused too.
     * Choices that only say the same thing differently are made canonical: a total "per recipient" is the list it asks for.
     */
    public static Optional<ReportQuestion> of(Map<String, String> chosen) {
        try {
            Metric metric = Metric.valueOf(chosen.get("metric"));
            Operation operation = Operation.valueOf(chosen.get("operation"));
            Condition condition = Condition.valueOf(chosen.get("condition"));
            Grouping groupBy = Grouping.valueOf(chosen.get("groupBy"));
            int limit = Integer.parseInt(chosen.get("limit"));
            LocalDate from = day(chosen.get("from"));
            LocalDate to = day(chosen.get("to"));
            return limit < 1 || limit > MAX_LIMIT ? Optional.empty() : of(metric, operation, condition, groupBy, limit, from, to);
        } catch (RuntimeException e) {   // a missing field, a name that does not exist, a limit that is not a number
            return Optional.empty();
        }
    }

    private static LocalDate day(String text) {
        return text == null || text.isEmpty() ? null : LocalDate.parse(text);
    }

    public static Optional<ReportQuestion> of(Metric metric, Operation operation, Condition condition, Grouping groupBy, int limit,
                                              LocalDate from, LocalDate to) {
        boolean outOfRange = (from != null && from.isBefore(HandoffReportService.EARLIEST)) || (to != null && to.isAfter(HandoffReportService.LATEST));
        if (outOfRange || (from != null && to != null && from.isAfter(to))) return Optional.empty();   // not a period anyone means
        if (operation == Operation.TOTAL && groupBy != Grouping.NONE) operation = Operation.LIST;
        if (operation == Operation.LIST && groupBy == Grouping.NONE) groupBy = Grouping.HANDOFF;
        if (operation == Operation.TOP && groupBy == Grouping.NONE) groupBy = metric.isItems() ? Grouping.HANDOFF : Grouping.RECIPIENT;
        if (operation == Operation.TOP && metric == Metric.HANDOFFS && groupBy == Grouping.HANDOFF) return Optional.empty();   // every handoff is one
        return Optional.of(new ReportQuestion(metric, operation, condition, groupBy, operation == Operation.TOP ? limit : 1, from, to));
    }
}
