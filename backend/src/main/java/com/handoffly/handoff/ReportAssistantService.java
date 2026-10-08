package com.handoffly.handoff;

import com.handoffly.ai.AiUnavailableException;
import com.handoffly.ai.IntentClassifier;
import com.handoffly.common.error.BadRequestException;
import com.handoffly.handoff.ReportQuestion.Condition;
import com.handoffly.handoff.ReportQuestion.Grouping;
import com.handoffly.handoff.ReportQuestion.Metric;
import com.handoffly.handoff.dto.HandoffSummaryResponse;
import com.handoffly.handoff.dto.ReportAnswerResponse;
import com.handoffly.handoff.dto.ReportAnswerResponse.Entry;
import com.handoffly.handoff.dto.ReportAnswerResponse.Kind;
import com.handoffly.handoff.dto.ReportAnswerResponse.Suggestion;
import com.handoffly.user.User;
import com.handoffly.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Questions about a customer's report, in their own words. The AI does one thing here: it turns the question text into a {@link ReportQuestion}
 * (a metric, an operation, a condition, a grouping — each one of a fixed list), and it is never shown any report data. Everything after that is
 * the report's own code: the rows come from {@link HandoffReportService#rows} (the customer's own handoffs only: all of them, or those created in
 * the period the question names — nothing about the page it was asked from narrows it), each handoff's figures are the ones {@code summarise}
 * adds up for the summary cards and the CSV, and the sentence is a fixed template that puts those figures and those handoff references in. Nothing the AI says
 * reaches the answer, so it can neither invent nor alter a number; a question the fixed lists cannot express is declined; nothing is ever
 * written. A ready-made suggestion is a {@link ReportQuestion} too, and skips the AI altogether.
 */
@Service
public class ReportAssistantService {

    private static final Logger log = LoggerFactory.getLogger(ReportAssistantService.class);

    static final String UNSUPPORTED_MESSAGE = "I can't answer that from the available report data.";
    static final String UNAVAILABLE_MESSAGE = "AI assistance is temporarily unavailable. Your normal report is still available.";
    static final String RATE_LIMITED_MESSAGE = "AI assistance has been used a lot recently. Please try again in a little while. Your normal report is still available.";
    static final String NOT_CONFIGURED_MESSAGE = "AI assistance isn't switched on for this server. Your normal report is still available.";

    /** How many handoffs an answer lists in full, and how many it names in its sentence. */
    static final int MAX_ENTRIES = 50;
    static final int NAMED_IN_SENTENCE = 12;

    /** What the AI is told about the job and about the words customers use (no data of theirs: see {@link IntentClassifier}). */
    static final String TASK = """
            You turn a customer's question about their handoff report into a structured request, which is then worked out from their own handoffs. \
            A handoff is one record of items given to a recipient and later returned; customers may call it a quotation, job, order, loan, \
            issue or similar, so treat those words as handoffs. The question is about all of the customer's handoffs unless it names a period \
            ("between 1 Oct and 10 Oct", "this month", "last week", "since March"): then give that period as the days from and to, using \
            the current year when none is said. A period is matched against when the handoffs were created. "Overdue items" means the items not yet \
            returned on overdue handoffs. "Total quantity in" some handoffs means the items handed over on them. "Pending", "outstanding" \
            and "still out" mean items not back yet. "Given" means handed over.""";

    private final HandoffReportService reports;
    private final IntentClassifier classifier;
    private final UserService users;

    public ReportAssistantService(HandoffReportService reports, IntentClassifier classifier, UserService users) {
        this.reports = reports;
        this.classifier = classifier;
        this.users = users;
    }

    /** The ready-made questions, for a customer whose plan includes the assistant. */
    public List<Suggestion> suggestions(Long userId) {
        allow(userId);
        return Arrays.stream(ReportIntent.values()).map(i -> new Suggestion(i, i.suggestion())).toList();
    }

    /**
     * Answers one question about the customer's handoffs: all of them unless the question names a period. The plan and the subscription are
     * checked first, and the time zone before any AI request is spent; then the question is interpreted and answered.
     */
    public ReportAnswerResponse ask(Long userId, String timezone, String question, ReportIntent suggestion) {
        allow(userId);
        if (suggestion == null && (question == null || question.isBlank())) {
            throw new BadRequestException("Ask a question, or choose one of the suggestions.");
        }
        ZoneId zone = HandoffReportService.zoneOf(timezone);

        Optional<ReportQuestion> asked;
        if (suggestion != null) {
            asked = Optional.of(suggestion.question());
        } else {
            try {
                String task = TASK + " " + calendar(LocalDate.now(zone));   // so that "this month" and "1 Oct" mean days: not a word of the customer's data
                asked = classifier.interpret(userId, question, task, ReportQuestion.fields(), ReportQuestion.days()).flatMap(ReportQuestion::of);
            } catch (AiUnavailableException e) {
                log.info("The Report Assistant had no answer from the AI: {}", e.reason());   // the category only, never the question or the provider's words
                return unavailable(e.reason());
            }
        }
        if (asked.isEmpty()) {
            return response(Kind.UNSUPPORTED, UNSUPPORTED_MESSAGE, null, null, List.of(), 0, false);
        }
        ReportQuestion q = asked.get();
        List<HandoffSummaryResponse> rows = reports.rows(userId, new HandoffReportService.Query(
                q.from() != null ? q.from() : HandoffReportService.EARLIEST, q.to() != null ? q.to() : HandoffReportService.LATEST, timezone, null, false));
        return answer(q, rows);
    }

    private void allow(Long userId) {
        User customer = users.getById(userId);
        users.requireActiveSubscription(customer);
        users.requireAiAssistantPlan(customer);
    }

    // ------------------------------------------------------------------ the facts, from the report's own figures

    /** One handoff, or one recipient's handoffs together, with the metric's value for it. */
    private record Group(String name, HandoffSummaryResponse first, List<String> codes, BigDecimal value, int handoffs) {}

    private ReportAnswerResponse answer(ReportQuestion q, List<HandoffSummaryResponse> rows) {
        List<HandoffSummaryResponse> matching = rows.stream().filter(q.condition().matches).toList();
        String lead = lead(q);
        return switch (q.operation()) {
            case TOTAL -> total(q, matching, lead);
            case LIST -> list(q, groups(q, matching), lead);
            case TOP -> top(q, groups(q, matching), lead);
        };
    }

    /** What today is, and the days of the periods customers name, worked out here so that the AI does no date arithmetic of its own. */
    static String calendar(LocalDate today) {
        LocalDate month = today.withDayOfMonth(1);
        LocalDate week = today.with(java.time.DayOfWeek.MONDAY);
        return "Today is " + today + ". This week is " + week + " to " + week.plusDays(6) + " (Monday to Sunday); last week is "
                + week.minusDays(7) + " to " + week.minusDays(1) + ". This month is " + month + " to " + month.plusMonths(1).minusDays(1)
                + "; last month is " + month.minusMonths(1) + " to " + month.minusDays(1) + ". This year is " + today.getYear() + "-01-01 to "
                + today.getYear() + "-12-31; last year is " + (today.getYear() - 1) + "-01-01 to " + (today.getYear() - 1) + "-12-31.";
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);

    private static String day(LocalDate date) {
        return DAY.format(date);
    }

    /** How a sentence starts: "Overall, " for all the handoffs, or the period the question named ("Between Oct 1, 2026 and Oct 10, 2026, "). */
    private static String lead(ReportQuestion q) {
        if (q.from() == null && q.to() == null) return "Overall, ";
        if (q.from() != null && q.to() != null) {
            return q.from().equals(q.to()) ? "On " + day(q.from()) + ", " : "Between " + day(q.from()) + " and " + day(q.to()) + ", ";
        }
        return q.from() != null ? "Since " + day(q.from()) + ", " : "Up to " + day(q.to()) + ", ";
    }

    /** Which handoffs an answer is about, in words: what the customer sees under it. */
    private static String basis(ReportQuestion q) {
        if (q.from() == null && q.to() == null) return "All your handoffs";
        if (q.from() != null && q.to() != null) return "Handoffs created " + day(q.from()) + (q.from().equals(q.to()) ? "" : " – " + day(q.to()));
        return q.from() != null ? "Handoffs created since " + day(q.from()) : "Handoffs created up to " + day(q.to());
    }

    // ---- one number

    private ReportAnswerResponse total(ReportQuestion q, List<HandoffSummaryResponse> matching, String lead) {
        Metric metric = q.metric();
        Condition condition = q.condition();
        BigDecimal value = sum(matching, metric);
        List<Entry> basis = condition == Condition.ANY ? List.of()   // the whole view is the summary cards; only a narrowed one needs its basis shown
                : matching.stream().filter(h -> metric.measure.apply(h).signum() > 0).map(h -> entry(h, detail(q, h))).toList();
        List<Entry> shown = basis.stream().limit(MAX_ENTRIES).toList();

        String sentence;
        if (metric == Metric.HANDOFFS) {
            long n = matching.size();
            sentence = lead + n + " " + (n == 1 ? "handoff " + condition.singular : "handoffs " + condition.plural) + ".";
        } else if (condition != Condition.ANY && matching.isEmpty()) {
            sentence = lead + "there are no " + handoffsNoun(condition, true) + ".";
        } else {
            sentence = lead + qty(value) + (value.compareTo(BigDecimal.ONE) == 0 ? " item " : " items ") + itemsVerb(metric, value)
                    + (condition == Condition.ANY ? "" : " in " + handoffs(condition, matching.size())) + companion(q, matching) + ".";
        }
        return response(Kind.ANSWER, sentence, q, value, shown, basis.size(), false);
    }

    /** "overdue items" or "the quantity in overdue handoffs" are two ways to ask for one thing: a figure is answered with the one beside it. */
    private static String companion(ReportQuestion q, List<HandoffSummaryResponse> matching) {
        boolean about = q.condition() == Condition.OVERDUE || q.condition() == Condition.OPEN || q.condition() == Condition.ACTIVE_WITH_RECIPIENT;
        if (!about || matching.isEmpty()) return "";
        return switch (q.metric()) {
            case ITEMS_STILL_OUT -> " (" + qty(sum(matching, Metric.ITEMS_GIVEN)) + " handed over on " + (matching.size() == 1 ? "it" : "them") + ")";
            case ITEMS_GIVEN -> " (" + qty(sum(matching, Metric.ITEMS_STILL_OUT)) + " not back yet)";
            default -> "";
        };
    }

    // ---- which ones, and the one(s) with the most

    private ReportAnswerResponse list(ReportQuestion q, List<Group> groups, String lead) {
        List<Entry> entries = groups.stream().limit(MAX_ENTRIES).map(g -> entry(q, g)).toList();
        if (groups.isEmpty()) {
            return response(Kind.ANSWER, lead + noOne(q) + ".", q, BigDecimal.ZERO, List.of(), 0, false);
        }
        boolean one = groups.size() == 1;
        List<String> names = groups.stream().map(g -> label(q, g)).toList();
        String sentence = lead + "the " + subject(q, !one) + (one ? " is " : " are ") + named(names) + ".";
        return response(Kind.ANSWER, sentence, q, BigDecimal.valueOf(groups.size()), entries, groups.size(), false);
    }

    private ReportAnswerResponse top(ReportQuestion q, List<Group> groups, String lead) {
        if (groups.isEmpty()) {
            return response(Kind.ANSWER, lead + noOne(q) + ".", q, BigDecimal.ZERO, List.of(), 0, false);
        }
        List<Group> ranked = groups.stream().sorted(Comparator.comparing(Group::value).reversed()).toList();   // a stable sort: ties stay in report order
        BigDecimal most = ranked.get(0).value();
        List<Group> chosen = q.limit() == 1 ? ranked.stream().filter(g -> g.value().compareTo(most) == 0).toList()
                : ranked.stream().limit(q.limit()).toList();
        String noun = mostOf(q);

        String sentence;
        if (q.limit() > 1 && chosen.size() > 1) {
            sentence = lead + "the " + chosen.size() + " with the most " + noun + " are "
                    + named(chosen.stream().map(g -> label(q, g) + " (" + qty(g.value()) + ")").toList()) + ".";
        } else if (chosen.size() == 1) {
            sentence = lead + label(q, chosen.get(0)) + " has the most " + noun + " (" + qty(most) + ").";
        } else {
            sentence = lead + named(chosen.stream().map(g -> label(q, g)).toList()) + " are tied for the most " + noun + " (" + qty(most) + " each).";
        }
        List<Entry> entries = chosen.stream().limit(MAX_ENTRIES).map(g -> entry(q, g)).toList();
        return response(Kind.ANSWER, sentence, q, most, entries, chosen.size(), false);
    }

    /** The handoffs, or the recipients, with something to show for the metric (a handoff that has none of what is asked about is not "one that has it"). */
    private static List<Group> groups(ReportQuestion q, List<HandoffSummaryResponse> matching) {
        Map<String, Group> groups = new LinkedHashMap<>();
        for (HandoffSummaryResponse h : matching) {
            BigDecimal value = q.metric().measure.apply(h);
            if (value.signum() <= 0) continue;
            if (q.groupBy() == Grouping.RECIPIENT) {
                String key = h.recipientName().trim().toLowerCase(Locale.ROOT);   // a name typed with other capitals or spacing is one recipient
                groups.merge(key, new Group(h.recipientName().trim(), h, List.of(h.publicCode()), value, 1), (a, b) -> {
                    List<String> codes = new ArrayList<>(a.codes());
                    codes.add(h.publicCode());
                    return new Group(a.name(), a.first(), codes, a.value().add(b.value()), a.handoffs() + 1);
                });
            } else {
                groups.put(h.publicCode(), new Group(h.publicCode(), h, List.of(h.publicCode()), value, 1));
            }
        }
        return List.copyOf(groups.values());
    }

    // ------------------------------------------------------------------ wording

    private static BigDecimal sum(List<HandoffSummaryResponse> handoffs, Metric metric) {
        return handoffs.stream().map(metric.measure).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String itemsVerb(Metric metric, BigDecimal value) {
        boolean one = value.compareTo(BigDecimal.ONE) == 0;
        return switch (metric) {
            case ITEMS_GIVEN -> one ? "was handed over" : "were handed over";
            case ITEMS_RETURNED -> one ? "was returned" : "were returned";
            case ITEMS_MISSING -> one ? "is missing" : "are missing";
            default -> one ? "is still out (not back yet)" : "are still out (not back yet)";
        };
    }

    /** "overdue handoffs", "handoff with missing items": the handoffs a condition picks out, in words. */
    private static String handoffsNoun(Condition condition, boolean plural) {
        return Arrays.asList(condition.before, plural ? "handoffs" : "handoff", condition.after).stream()
                .filter(s -> !s.isEmpty()).collect(Collectors.joining(" "));
    }

    private static String handoffs(Condition condition, long n) {
        return n + " " + handoffsNoun(condition, n != 1);
    }

    /** What an item metric is about when it is the object of a sentence: "missing items". */
    private static String itemsObject(Metric metric) {
        return switch (metric) {
            case ITEMS_GIVEN -> "items handed over";
            case ITEMS_RETURNED -> "returned items";
            case ITEMS_MISSING -> "missing items";
            default -> "items still out";
        };
    }

    /** "the overdue handoffs", "the handoffs with missing items", "the recipients with active handoffs": what a LIST names. */
    private static String subject(ReportQuestion q, boolean plural) {
        Metric metric = q.metric();
        Condition condition = q.condition();
        if (q.groupBy() == Grouping.RECIPIENT) {
            return (plural ? "recipients" : "recipient") + " with "
                    + (metric == Metric.HANDOFFS ? handoffsNoun(condition, true)
                    : itemsObject(metric) + (condition == Condition.ANY ? "" : " in " + handoffsNoun(condition, true)));
        }
        String noun = handoffsNoun(condition, plural);
        String with = switch (metric) {
            case HANDOFFS -> "";
            case ITEMS_GIVEN -> "with items handed over";
            case ITEMS_RETURNED -> "with returned items";
            case ITEMS_MISSING -> "with missing items";
            case ITEMS_STILL_OUT -> "with items still out";
        };
        return with.isEmpty() || with.equals(condition.after) ? noun : noun + (condition.after.isEmpty() ? " " : " and ") + with;
    }

    /** "the most ___": missing items, active handoffs, items still out among the overdue handoffs. */
    private static String mostOf(ReportQuestion q) {
        if (q.metric() == Metric.HANDOFFS) return handoffsNoun(q.condition(), true);
        return itemsObject(q.metric()) + (q.condition() == Condition.ANY || impliedByMetric(q) ? "" : " among the " + handoffsNoun(q.condition(), true));
    }

    /** "the most missing items among the handoffs with missing items" says it twice: the condition is the metric's own. */
    private static boolean impliedByMetric(ReportQuestion q) {
        return (q.metric() == Metric.ITEMS_MISSING && q.condition() == Condition.HAS_MISSING)
                || (q.metric() == Metric.ITEMS_STILL_OUT && q.condition() == Condition.HAS_STILL_OUT);
    }

    /** What is said when nothing matches: "no handoff has missing items", "no recipient has active handoffs". */
    private static String noOne(ReportQuestion q) {
        Metric metric = q.metric();
        Condition condition = q.condition();
        if (q.groupBy() == Grouping.RECIPIENT) {
            return "no recipient has " + (metric == Metric.HANDOFFS ? handoffsNoun(condition, true)
                    : itemsObject(metric) + (condition == Condition.ANY ? "" : " in " + handoffsNoun(condition, true)));
        }
        if (metric == Metric.HANDOFFS) {
            return "no handoff " + condition.singular;
        }
        String has = switch (metric) {
            case ITEMS_GIVEN -> "has items handed over";
            case ITEMS_RETURNED -> "has returned items";
            case ITEMS_MISSING -> "has missing items";
            default -> "has items still out";
        };
        return "no " + (condition.before.isEmpty() ? "" : condition.before + " ") + "handoff " + has;
    }

    private static String label(ReportQuestion q, Group g) {
        return q.groupBy() == Grouping.RECIPIENT ? g.name() + " (" + String.join(", ", g.codes()) + ")" : g.name();
    }

    private static String shortLabel(Metric metric) {
        return switch (metric) {
            case ITEMS_GIVEN -> "handed over";
            case ITEMS_RETURNED -> "returned";
            case ITEMS_MISSING -> "missing";
            default -> "still out";
        };
    }

    /** What a listed handoff is shown with: its figure for an item question; for a count of handoffs, what makes it one. */
    private static String detail(ReportQuestion q, HandoffSummaryResponse h) {
        if (q.metric().isItems()) {
            return qty(q.metric().measure.apply(h)) + " " + shortLabel(q.metric());
        }
        return switch (q.condition()) {
            case HAS_MISSING -> qty(HandoffReportService.itemsMissing(h)) + " missing";
            case OVERDUE, OPEN, ACTIVE_WITH_RECIPIENT, HAS_STILL_OUT -> qty(HandoffReportService.itemsStillOut(h)) + " not yet returned";
            default -> HandoffReportService.label(h.status());
        };
    }

    private static Entry entry(HandoffSummaryResponse h, String detail) {
        return new Entry(h.publicCode(), h.title(), h.recipientName(), detail);
    }

    private static Entry entry(ReportQuestion q, Group g) {
        if (q.groupBy() == Grouping.RECIPIENT) {
            String detail = q.metric() == Metric.HANDOFFS ? handoffs(q.condition(), g.handoffs()) : qty(g.value()) + " " + shortLabel(q.metric());
            return new Entry(String.join(", ", g.codes()), g.handoffs() + (g.handoffs() == 1 ? " handoff" : " handoffs"), g.name(), detail);
        }
        return entry(g.first(), detail(q, g.first()));
    }

    private ReportAnswerResponse unavailable(AiUnavailableException.Reason reason) {
        String message = switch (reason) {
            case NOT_CONFIGURED -> NOT_CONFIGURED_MESSAGE;
            case RATE_LIMITED -> RATE_LIMITED_MESSAGE;
            case UNAVAILABLE, INVALID_RESPONSE, NOTHING_FOUND -> UNAVAILABLE_MESSAGE;
        };
        boolean transientFailure = reason == AiUnavailableException.Reason.UNAVAILABLE || reason == AiUnavailableException.Reason.INVALID_RESPONSE;
        return response(Kind.UNAVAILABLE, message, null, null, List.of(), 0, transientFailure);
    }

    private static ReportAnswerResponse response(Kind kind, String answer, ReportQuestion question, BigDecimal value,
                                                 List<Entry> entries, int entryTotal, boolean canRetry) {
        return new ReportAnswerResponse(kind, answer, question, question == null ? null : basis(question), value, entries, entryTotal, canRetry);
    }

    /** "AK-1", "AK-1 and AK-2", "AK-1, AK-2 and AK-3"; a long list names the first few and counts the rest. */
    static String named(List<String> names) {
        List<String> shown = names.size() > NAMED_IN_SENTENCE ? names.subList(0, NAMED_IN_SENTENCE) : names;
        String joined = shown.size() == 1 ? shown.get(0)
                : String.join(", ", shown.subList(0, shown.size() - 1)) + " and " + shown.get(shown.size() - 1);
        return names.size() > NAMED_IN_SENTENCE
                ? String.join(", ", shown) + " and " + (names.size() - NAMED_IN_SENTENCE) + " more"
                : joined;
    }

    private static String qty(BigDecimal v) {
        BigDecimal stripped = v.stripTrailingZeros();
        return (stripped.signum() == 0 ? BigDecimal.ZERO : stripped).toPlainString();
    }
}
