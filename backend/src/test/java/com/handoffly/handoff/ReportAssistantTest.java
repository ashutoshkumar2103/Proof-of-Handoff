package com.handoffly.handoff;

import com.handoffly.ai.AiUnavailableException.Reason;
import com.handoffly.ai.StubAiModel;
import com.handoffly.testsupport.ApiTestBase;
import com.handoffly.user.SubscriptionPlan;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Report Assistant: the AI only turns the customer's own words into a structured request (a metric, an operation, a condition, a grouping);
 * the answer is worked out by the backend from the report's own figures for the view on screen. The AI is replaced by a stand-in that answers
 * whatever a test says, so these tests prove what the backend does with each request (the sums, the lists, the ranking), that nothing the AI
 * says can change a number or widen the view, that the answers agree with the report itself, and that a failing AI leaves the report untouched.
 */
@Import(StubAiModel.Config.class)
@TestPropertySource(properties = "handoffly.ai.gemini-api-key=test-only-secret-key-9d27a")
class ReportAssistantTest extends ApiTestBase {

    private static final String SECRET = "test-only-secret-key-9d27a";
    private static final String ASK = "/api/v1/reports/assistant/ask";
    private static final String SUGGESTIONS = "/api/v1/reports/assistant/suggestions";
    private static final String REPORT = "/api/v1/reports/handoffs";
    private static final String PAST = "2020-01-01T00:00:00Z";
    private static final String FUTURE = "2099-01-01T00:00:00Z";

    @Autowired
    private StubAiModel ai;

    @BeforeEach
    void freshStub() {
        ai.reset();
    }

    // ------------------------------------------------------------------ fixtures

    /** A Half-Yearly customer whose report has: an overdue handoff (4 of 10 back), one with 3 items missing, an untouched open one, a draft and a closed one. */
    private record Fixture(Account owner, long overdue, long missing, long open, long draft, long closed) {}

    private int itemId(Account owner, long id) throws Exception {
        return JsonPath.read(mvc.perform(as(owner, get("/api/v1/handoffs/" + id))).andReturn().getResponse().getContentAsString(), "$.items[0].id");
    }

    private void giveBack(Account owner, long id, int quantity, String condition) throws Exception {
        mvc.perform(as(owner, post("/api/v1/handoffs/" + id + "/returns").contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":[{\"itemId\":" + itemId(owner, id) + ",\"quantity\":" + quantity + ",\"condition\":\"" + condition + "\"}]}")))
                .andExpect(status().isCreated());
    }

    private Fixture fixture(SubscriptionPlan plan) throws Exception {
        Account a = register(plan);
        long overdue = activeHandoff(a, "Overdue one", PAST);
        giveBack(a, overdue, 4, "GOOD");
        long missing = activeHandoff(a, "Missing one", FUTURE);
        giveBack(a, missing, 3, "MISSING");
        long open = activeHandoff(a, "Open one", FUTURE);
        long draft = ((Number) JsonPath.read(createHandoff(a), "$.id")).longValue();
        long closed = activeHandoff(a, "Closed one", FUTURE);
        giveBack(a, closed, 10, "GOOD");
        mvc.perform(as(a, post("/api/v1/handoffs/" + closed + "/close"))).andExpect(status().isOk());
        return new Fixture(a, overdue, missing, open, draft, closed);
    }

    private String code(Account owner, long id) throws Exception {
        return JsonPath.read(mvc.perform(as(owner, get("/api/v1/handoffs/" + id))).andReturn().getResponse().getContentAsString(), "$.publicCode");
    }

    /** How an answer writes a day. */
    private static String day(LocalDate date) {
        return java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.ENGLISH).format(date);
    }

    private static LocalDate today() {
        return LocalDate.now(ZoneOffset.UTC);
    }

    /** What every question carries: the customer's time zone, and nothing about any page, period or filter (a question is about everything). */
    private static String wholeView() {
        return "\"timezone\":\"UTC\"";
    }

    private ResultActions ask(Bearer who, String view, String question) throws Exception {
        return mvc.perform(as(who, post(ASK).contentType(MediaType.APPLICATION_JSON)
                .content("{" + view + ",\"question\":\"" + question + "\"}")));
    }

    private ResultActions pick(Bearer who, String view, String intent) throws Exception {
        return mvc.perform(as(who, post(ASK).contentType(MediaType.APPLICATION_JSON).content("{" + view + ",\"suggestion\":\"" + intent + "\"}")));
    }

    private String reportJson(Account who, LocalDate from, LocalDate to, String... extra) throws Exception {
        var request = get(REPORT).param("from", from.toString()).param("to", to.toString());
        for (int i = 0; i < extra.length; i += 2) request.param(extra[i], extra[i + 1]);
        return mvc.perform(as(who, request)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static double summary(String report, String field) {
        return ((Number) JsonPath.read(report, "$.summary." + field)).doubleValue();
    }

    /** The AI "understands" the next question as this request (what the real one answers with, as JSON). */
    private void choose(String metric, String operation, String condition, String groupBy, int limit) {
        choose(metric, operation, condition, groupBy, limit, "", "");
    }

    /** The same, for a question that names a period: its first and last day, or an empty text for the one it does not name. */
    private void choose(String metric, String operation, String condition, String groupBy, int limit, String from, String to) {
        ai.answer("{\"supported\":true,\"metric\":\"" + metric + "\",\"operation\":\"" + operation + "\",\"condition\":\"" + condition
                + "\",\"groupBy\":\"" + groupBy + "\",\"limit\":\"" + limit + "\",\"from\":\"" + from + "\",\"to\":\"" + to + "\"}");
    }

    private void total(String metric, String condition) {
        choose(metric, "TOTAL", condition, "NONE", 1);
    }

    // ------------------------------------------------------------------ the answers are the report's figures

    @Test
    void howManyItemsWereHandedOverIsTheReportsOwnTotal() throws Exception {
        Fixture f = fixture(SubscriptionPlan.HALF_YEARLY);
        total("ITEMS_GIVEN", "ANY");

        String report = reportJson(f.owner(), today().minusDays(2), today().plusDays(1));
        assertThat(summary(report, "itemsGiven")).isEqualTo(40.0);   // the draft gave nothing

        ask(f.owner(), wholeView(), "How many items were handed over?").andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("ANSWER"))
                .andExpect(jsonPath("$.query.metric").value("ITEMS_GIVEN"))
                .andExpect(jsonPath("$.query.operation").value("TOTAL"))
                .andExpect(jsonPath("$.value").value(summary(report, "itemsGiven")))
                .andExpect(jsonPath("$.answer").value("Overall, 40 items were handed over."))
                .andExpect(jsonPath("$.basis").value("All your handoffs"));
        assertThat(ai.calls()).hasSize(1);
    }

    @Test
    void everyTotalOverTheWholeViewAgreesWithTheReportSummaryFigureForFigure() throws Exception {
        Fixture f = fixture(SubscriptionPlan.YEARLY);
        String report = reportJson(f.owner(), today().minusDays(2), today().plusDays(1));
        String[][] cases = {
                {"HANDOFFS", "ANY", "created"}, {"HANDOFFS", "CLOSED", "closed"}, {"HANDOFFS", "OPEN", "open"}, {"HANDOFFS", "OVERDUE", "overdue"},
                {"ITEMS_GIVEN", "ANY", "itemsGiven"}, {"ITEMS_RETURNED", "ANY", "itemsReturned"}, {"ITEMS_MISSING", "ANY", "itemsMissing"},
                {"ITEMS_STILL_OUT", "ANY", "itemsStillOut"}};
        for (String[] c : cases) {
            total(c[0], c[1]);
            ask(f.owner(), wholeView(), "a question for " + c[0] + " " + c[1]).andExpect(status().isOk())
                    .andExpect(jsonPath("$.kind").value("ANSWER"))
                    .andExpect(jsonPath("$.value").value(summary(report, c[2])));
        }
        assertThat(summary(report, "itemsReturned")).isEqualTo(14.0);
        assertThat(summary(report, "itemsMissing")).isEqualTo(3.0);
        assertThat(summary(report, "overdue")).isEqualTo(1.0);
        assertThat(summary(report, "closed")).isEqualTo(1.0);
    }

    @Test
    void totalsAreAnsweredInWords() throws Exception {
        Fixture f = fixture(SubscriptionPlan.HALF_YEARLY);

        total("ITEMS_MISSING", "ANY");
        ask(f.owner(), wholeView(), "How many items are missing?").andExpect(jsonPath("$.answer").value("Overall, 3 items are missing."));
        total("HANDOFFS", "OVERDUE");
        ask(f.owner(), wholeView(), "How many are overdue?").andExpect(jsonPath("$.answer").value("Overall, 1 handoff is overdue."));
        total("HANDOFFS", "CLOSED");
        ask(f.owner(), wholeView(), "How many were closed?").andExpect(jsonPath("$.answer").value("Overall, 1 handoff is closed."));
        total("ITEMS_STILL_OUT", "ANY");
        ask(f.owner(), wholeView(), "How many items are pending?").andExpect(jsonPath("$.answer").value("Overall, 23 items are still out (not back yet)."));
        total("ITEMS_RETURNED", "ANY");
        ask(f.owner(), wholeView(), "How many items came back?").andExpect(jsonPath("$.answer").value("Overall, 14 items were returned."));
        total("HANDOFFS", "ANY");
        ask(f.owner(), wholeView(), "How many handoffs?").andExpect(jsonPath("$.answer").value("Overall, 5 handoffs were created."));
    }

    // ------------------------------------------------------------------ the question is the customer's own: totals the cards do not show

    /** Three overdue handoffs of 10 items each, with 4, 0 and 7 back (6, 10 and 3 not back), and one that is not due yet (10, none back). */
    private record Overdue(Account owner, long a, long b, long c, long notDue) {}

    private Overdue overdueFixture(SubscriptionPlan plan) throws Exception {
        Account owner = register(plan);
        long a = activeHandoff(owner, "AK A", PAST);
        giveBack(owner, a, 4, "GOOD");
        long b = activeHandoff(owner, "AK B", PAST);
        long c = activeHandoff(owner, "AK C", PAST);
        giveBack(owner, c, 7, "GOOD");
        long notDue = activeHandoff(owner, "Not due", FUTURE);
        return new Overdue(owner, a, b, c, notDue);
    }

    @Test
    void totalOverdueItemsIsTheSumOverTheOverdueHandoffsAndShowsItsBasis() throws Exception {
        Overdue f = overdueFixture(SubscriptionPlan.HALF_YEARLY);
        total("ITEMS_STILL_OUT", "OVERDUE");   // "How many total overdue items are there?": nothing like it is a ready-made question the AI must pick

        ask(f.owner(), wholeView(), "How many total overdue items are there?").andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("ANSWER"))
                .andExpect(jsonPath("$.value").value(19))   // 6 + 10 + 3: not the 10 of the one that is not due, not the 30 handed over
                .andExpect(jsonPath("$.answer").value("Overall, 19 items are still out (not back yet) in 3 overdue handoffs (30 handed over on them)."))
                .andExpect(jsonPath("$.entryTotal").value(3))
                .andExpect(jsonPath("$.entries[*].reference").value(org.hamcrest.Matchers.containsInAnyOrder(
                        code(f.owner(), f.a()), code(f.owner(), f.b()), code(f.owner(), f.c()))))
                .andExpect(jsonPath("$.entries[?(@.title=='AK A')].detail").value("6 still out"))
                .andExpect(jsonPath("$.entries[?(@.title=='AK B')].detail").value("10 still out"))
                .andExpect(jsonPath("$.entries[?(@.title=='AK C')].detail").value("3 still out"));
        assertThat(ai.calls()).hasSize(1);
    }

    @Test
    void differentWordingsThatMeanTheSameRequestGiveTheSameVerifiedNumber() throws Exception {
        Overdue f = overdueFixture(SubscriptionPlan.HALF_YEARLY);
        total("ITEMS_STILL_OUT", "OVERDUE");

        // The AI's part is choosing the request; whatever the words, the same request is the same calculation.
        for (String words : new String[]{"How many total overdue items?", "How many items are overdue?", "Show me overdue item quantity.", "overdue items count"}) {
            ask(f.owner(), wholeView(), words).andExpect(jsonPath("$.value").value(19))
                    .andExpect(jsonPath("$.answer").value(org.hamcrest.Matchers.startsWith("Overall, 19 items are still out")));
        }
        // "The total quantity in overdue handoffs" is the quantity handed over on them, and says what of it is still out.
        total("ITEMS_GIVEN", "OVERDUE");
        ask(f.owner(), wholeView(), "What's the total quantity in overdue Handoffs?").andExpect(jsonPath("$.value").value(30))
                .andExpect(jsonPath("$.answer").value("Overall, 30 items were handed over in 3 overdue handoffs (19 not back yet)."));
    }

    @Test
    void theNumberOfOverdueHandoffsAndTheirNames() throws Exception {
        Overdue f = overdueFixture(SubscriptionPlan.HALF_YEARLY);
        String a = code(f.owner(), f.a());
        String b = code(f.owner(), f.b());
        String c = code(f.owner(), f.c());

        total("HANDOFFS", "OVERDUE");
        ask(f.owner(), wholeView(), "How many overdue Handoffs do I have?").andExpect(jsonPath("$.value").value(3))
                .andExpect(jsonPath("$.answer").value("Overall, 3 handoffs are overdue."))
                .andExpect(jsonPath("$.entries.length()").value(3));

        choose("HANDOFFS", "LIST", "OVERDUE", "HANDOFF", 1);
        ask(f.owner(), wholeView(), "Which Handoffs are overdue?")
                .andExpect(jsonPath("$.answer").value("Overall, the overdue handoffs are " + a + ", " + b + " and " + c + "."))
                .andExpect(jsonPath("$.entries[0].detail").value("6 not yet returned"));
    }

    @Test
    void totalMissingReturnedAndStillOutAreTheSumsOfTheHandoffsFigures() throws Exception {
        Fixture f = fixture(SubscriptionPlan.HALF_YEARLY);
        String report = reportJson(f.owner(), today().minusDays(2), today().plusDays(1));

        total("ITEMS_MISSING", "ANY");
        ask(f.owner(), wholeView(), "What is the total missing quantity?").andExpect(jsonPath("$.value").value(3)).andExpect(jsonPath("$.value").value(summary(report, "itemsMissing")));
        total("ITEMS_RETURNED", "ANY");
        ask(f.owner(), wholeView(), "How many items were returned this month?").andExpect(jsonPath("$.value").value(14));   // 4 and 10 came back; the 3 that were reported missing did not
        total("ITEMS_STILL_OUT", "ANY");
        ask(f.owner(), wholeView(), "How many items are still out?").andExpect(jsonPath("$.value").value(23));
        // Narrowed to a kind of handoff, the sum is over those handoffs only: the one that is overdue has 6 of its 10 still out.
        total("ITEMS_STILL_OUT", "OVERDUE");
        ask(f.owner(), wholeView(), "overdue items").andExpect(jsonPath("$.value").value(6));
        total("ITEMS_STILL_OUT", "HAS_MISSING");
        ask(f.owner(), wholeView(), "pending items on handoffs with missing items").andExpect(jsonPath("$.value").value(7));
        total("ITEMS_GIVEN", "CLOSED");
        ask(f.owner(), wholeView(), "items given on closed handoffs").andExpect(jsonPath("$.value").value(10))
                .andExpect(jsonPath("$.answer").value("Overall, 10 items were handed over in 1 closed handoff."));
    }

    @Test
    void listsAndTheHandoffWithTheMostMissingNameTheRealHandoffsTheReportHas() throws Exception {
        Fixture f = fixture(SubscriptionPlan.HALF_YEARLY);
        String overdue = code(f.owner(), f.overdue());
        String missing = code(f.owner(), f.missing());

        choose("HANDOFFS", "LIST", "OVERDUE", "HANDOFF", 1);
        ask(f.owner(), wholeView(), "Which handoffs are overdue?").andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Overall, the overdue handoff is " + overdue + "."))
                .andExpect(jsonPath("$.entries.length()").value(1))
                .andExpect(jsonPath("$.entries[0].reference").value(overdue))
                .andExpect(jsonPath("$.entries[0].title").value("Overdue one"))
                .andExpect(jsonPath("$.entries[0].detail").value("6 not yet returned"));

        choose("ITEMS_MISSING", "LIST", "ANY", "HANDOFF", 1);
        ask(f.owner(), wholeView(), "Which handoffs have missing items?").andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Overall, the handoff with missing items is " + missing + "."))
                .andExpect(jsonPath("$.entries[0].detail").value("3 missing"));

        choose("ITEMS_MISSING", "TOP", "ANY", "HANDOFF", 1);
        ask(f.owner(), wholeView(), "Which Handoff has the most missing items?").andExpect(jsonPath("$.answer")
                .value("Overall, " + missing + " has the most missing items (3)."))
                .andExpect(jsonPath("$.value").value(3));
    }

    @Test
    void topQuestionsRankTiesAndTheTopFew() throws Exception {
        Account a = register(SubscriptionPlan.HALF_YEARLY);
        long one = activeHandoff(a, "One", FUTURE);
        giveBack(a, one, 2, "MISSING");
        long two = activeHandoff(a, "Two", FUTURE);
        giveBack(a, two, 5, "MISSING");
        long three = activeHandoff(a, "Three", FUTURE);
        giveBack(a, three, 5, "MISSING");
        String codeOne = code(a, one);
        String codeTwo = code(a, two);
        String codeThree = code(a, three);

        choose("ITEMS_MISSING", "TOP", "ANY", "HANDOFF", 1);
        ask(a, wholeView(), "Which Handoff has the most missing items?")
                .andExpect(jsonPath("$.answer").value("Overall, " + codeTwo + " and " + codeThree + " are tied for the most missing items (5 each)."))
                .andExpect(jsonPath("$.entryTotal").value(2));
        choose("ITEMS_MISSING", "TOP", "ANY", "HANDOFF", 3);
        ask(a, wholeView(), "Top 3 handoffs by missing items")
                .andExpect(jsonPath("$.answer").value("Overall, the 3 with the most missing items are "
                        + codeTwo + " (5), " + codeThree + " (5) and " + codeOne + " (2)."));
        choose("ITEMS_MISSING", "TOP", "HAS_MISSING", "HANDOFF", 2);   // the condition and the metric say the same: said once
        ask(a, wholeView(), "Which two handoffs with missing items have the most?")
                .andExpect(jsonPath("$.answer").value("Overall, the 2 with the most missing items are " + codeTwo + " (5) and " + codeThree + " (5)."));
        choose("ITEMS_STILL_OUT", "TOP", "OVERDUE", "HANDOFF", 1);
        ask(a, wholeView(), "Which overdue handoff has the most items pending?")
                .andExpect(jsonPath("$.answer").value("Overall, no overdue handoff has items still out."));
    }

    @Test
    void theRecipientWithTheMostActiveHandoffsIsFoundByAddingUpTheirHandoffs() throws Exception {
        Account a = register(SubscriptionPlan.HALF_YEARLY);
        long first = activeHandoff(a, "Shared", FUTURE);   // the recipient is named after the title, so these two are one recipient
        long second = activeHandoff(a, "Shared", FUTURE);
        activeHandoff(a, "Alone", FUTURE);

        choose("HANDOFFS", "TOP", "ACTIVE_WITH_RECIPIENT", "RECIPIENT", 1);
        ask(a, wholeView(), "Which recipient has the most active Handoffs?").andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Overall, Recipient Shared (" + code(a, first) + ", " + code(a, second)
                        + ") has the most active handoffs (2)."))
                .andExpect(jsonPath("$.value").value(2))
                .andExpect(jsonPath("$.entries[0].recipient").value("Recipient Shared"))
                .andExpect(jsonPath("$.entries[0].detail").value("2 active handoffs"));

        choose("HANDOFFS", "LIST", "ACTIVE_WITH_RECIPIENT", "RECIPIENT", 1);
        ask(a, wholeView(), "Which recipients have active handoffs?")
                .andExpect(jsonPath("$.entryTotal").value(2))
                .andExpect(jsonPath("$.entries[*].recipient").value(org.hamcrest.Matchers.containsInAnyOrder("Recipient Shared", "Recipient Alone")));
    }

    @Test
    void aListThatIsEmptySaysSoRatherThanInventingOne() throws Exception {
        Account a = register(SubscriptionPlan.HALF_YEARLY);
        activeHandoff(a, "Fine", FUTURE);

        choose("ITEMS_MISSING", "LIST", "ANY", "HANDOFF", 1);
        ask(a, wholeView(), "Which handoffs have missing items?").andExpect(jsonPath("$.answer").value("Overall, no handoff has missing items."))
                .andExpect(jsonPath("$.entries.length()").value(0)).andExpect(jsonPath("$.entryTotal").value(0));
        choose("ITEMS_MISSING", "TOP", "ANY", "HANDOFF", 1);
        ask(a, wholeView(), "Which has the most missing?").andExpect(jsonPath("$.answer").value("Overall, no handoff has missing items."));
        choose("HANDOFFS", "LIST", "OVERDUE", "HANDOFF", 1);
        ask(a, wholeView(), "What is overdue?").andExpect(jsonPath("$.answer").value("Overall, no handoff is overdue."));
        total("ITEMS_STILL_OUT", "OVERDUE");
        ask(a, wholeView(), "How many total overdue items are there?").andExpect(jsonPath("$.value").value(0))
                .andExpect(jsonPath("$.answer").value("Overall, there are no overdue handoffs."));
    }

    // ------------------------------------------------------------------ everything, unless the question names a period

    @Test
    void aQuestionIsAboutAllTheHandoffsWhateverTheirAge() throws Exception {
        Fixture f = fixture(SubscriptionPlan.HALF_YEARLY);
        jdbc.update("UPDATE handoff SET created_at = ? WHERE id = ?", java.sql.Timestamp.from(Instant.now().minus(400, ChronoUnit.DAYS)), f.missing());
        total("ITEMS_MISSING", "ANY");

        ask(f.owner(), wholeView(), "How many items are missing?").andExpect(jsonPath("$.value").value(3))   // a year old, and still counted
                .andExpect(jsonPath("$.answer").value("Overall, 3 items are missing."))
                .andExpect(jsonPath("$.basis").value("All your handoffs"));
        total("HANDOFFS", "ANY");
        ask(f.owner(), wholeView(), "How many handoffs are there?").andExpect(jsonPath("$.value").value(5));
    }

    @Test
    void aPeriodTheQuestionNamesNarrowsTheAnswerToHandoffsCreatedInIt() throws Exception {
        Fixture f = fixture(SubscriptionPlan.HALF_YEARLY);
        jdbc.update("UPDATE handoff SET created_at = ? WHERE id = ?", java.sql.Timestamp.from(Instant.now().minus(60, ChronoUnit.DAYS)), f.missing());
        LocalDate old = today().minusDays(60);

        // "How many items are missing between <that week>?": the handoff with missing items was created then
        choose("ITEMS_MISSING", "TOTAL", "ANY", "NONE", 1, old.minusDays(3).toString(), old.plusDays(3).toString());
        ask(f.owner(), wholeView(), "How many items are missing between those days?").andExpect(jsonPath("$.value").value(3))
                .andExpect(jsonPath("$.answer").value("Between " + day(old.minusDays(3)) + " and " + day(old.plusDays(3)) + ", 3 items are missing."))
                .andExpect(jsonPath("$.basis").value("Handoffs created " + day(old.minusDays(3)) + " – " + day(old.plusDays(3))));
        // the recent days do not include it
        choose("ITEMS_MISSING", "TOTAL", "ANY", "NONE", 1, today().minusDays(2).toString(), today().plusDays(1).toString());
        ask(f.owner(), wholeView(), "How many items are missing in the last days?").andExpect(jsonPath("$.value").value(0))
                .andExpect(jsonPath("$.answer").value("Between " + day(today().minusDays(2)) + " and " + day(today().plusDays(1)) + ", 0 items are missing."));
        // and it is the question's period only: asked again with no period, the same question is about everything
        total("ITEMS_MISSING", "ANY");
        ask(f.owner(), wholeView(), "How many items are missing?").andExpect(jsonPath("$.value").value(3));
    }

    @Test
    void aPeriodMayHaveOnlyAStartOnlyAnEndOrBeASingleDay() throws Exception {
        Fixture f = fixture(SubscriptionPlan.HALF_YEARLY);
        jdbc.update("UPDATE handoff SET created_at = ? WHERE id = ?", java.sql.Timestamp.from(Instant.now().minus(60, ChronoUnit.DAYS)), f.missing());
        LocalDate old = today().minusDays(60);

        choose("HANDOFFS", "TOTAL", "ANY", "NONE", 1, old.plusDays(1).toString(), "");   // since the day after: the old one is out, today's four are in
        ask(f.owner(), wholeView(), "How many handoffs since then?").andExpect(jsonPath("$.value").value(4))
                .andExpect(jsonPath("$.answer").value("Since " + day(old.plusDays(1)) + ", 4 handoffs were created."))
                .andExpect(jsonPath("$.basis").value("Handoffs created since " + day(old.plusDays(1))));
        choose("HANDOFFS", "TOTAL", "ANY", "NONE", 1, "", old.plusDays(1).toString());    // up to that day: only the old one
        ask(f.owner(), wholeView(), "How many handoffs up to then?").andExpect(jsonPath("$.value").value(1))
                .andExpect(jsonPath("$.answer").value("Up to " + day(old.plusDays(1)) + ", 1 handoff was created."));
        choose("HANDOFFS", "TOTAL", "ANY", "NONE", 1, old.toString(), old.toString());      // one day
        ask(f.owner(), wholeView(), "How many handoffs that day?").andExpect(jsonPath("$.value").value(1))
                .andExpect(jsonPath("$.answer").value("On " + day(old) + ", 1 handoff was created."))
                .andExpect(jsonPath("$.basis").value("Handoffs created " + day(old)));
    }

    @Test
    void aPeriodThatMeansNothingIsDeclinedAndOneThatIsNotADayIsAnUnusableAnswer() throws Exception {
        Fixture f = fixture(SubscriptionPlan.HALF_YEARLY);

        choose("HANDOFFS", "TOTAL", "ANY", "NONE", 1, "2026-10-10", "2026-10-01");   // ends before it starts
        ask(f.owner(), wholeView(), "between the 10th and the 1st").andExpect(jsonPath("$.kind").value("UNSUPPORTED"));
        choose("HANDOFFS", "TOTAL", "ANY", "NONE", 1, "1900-01-01", "");               // before any report can start
        ask(f.owner(), wholeView(), "since 1900").andExpect(jsonPath("$.kind").value("UNSUPPORTED"));
        for (String notADay : new String[]{"yesterday", "2026-13-45", "2026-02-30", "10/01/2026", "2026-1-5", "1 Oct", "2026-10-01T00:00:00Z", "DROP TABLE"}) {
            choose("HANDOFFS", "TOTAL", "ANY", "NONE", 1, notADay, "");
            ask(f.owner(), wholeView(), "anything").andExpect(jsonPath("$.kind").value("UNAVAILABLE")).andExpect(jsonPath("$.value").doesNotExist());
        }
    }

    @Test
    void conditionsAreHowAQuestionNarrowsByStatusAndNothingOnThePageDoes() throws Exception {
        Fixture f = fixture(SubscriptionPlan.HALF_YEARLY);

        total("ITEMS_GIVEN", "CLOSED");
        ask(f.owner(), wholeView(), "How many items were given on closed handoffs?").andExpect(jsonPath("$.value").value(10));
        total("ITEMS_GIVEN", "OVERDUE");
        ask(f.owner(), wholeView(), "How many items are in overdue handoffs?").andExpect(jsonPath("$.value").value(10));
        total("HANDOFFS", "OPEN");
        ask(f.owner(), wholeView(), "How many are open?").andExpect(jsonPath("$.value").value(3));
        total("ITEMS_STILL_OUT", "OVERDUE");
        ask(f.owner(), wholeView(), "How many total overdue items are there?").andExpect(jsonPath("$.value").value(6));
    }

    @Test
    void anAnswerIsAboutThatCustomersHandoffsAndNobodyElses() throws Exception {
        Fixture mine = fixture(SubscriptionPlan.HALF_YEARLY);
        Fixture theirs = fixture(SubscriptionPlan.HALF_YEARLY);
        Account other = register(SubscriptionPlan.HALF_YEARLY);
        long alone = activeHandoff(other, "Only theirs", PAST);
        giveBack(other, alone, 7, "MISSING");

        total("ITEMS_MISSING", "ANY");
        ask(mine.owner(), wholeView(), "missing?").andExpect(jsonPath("$.value").value(3));
        ask(other, wholeView(), "missing?").andExpect(jsonPath("$.value").value(7));
        total("ITEMS_STILL_OUT", "OVERDUE");
        ask(other, wholeView(), "How many total overdue items are there?").andExpect(jsonPath("$.value").value(3));   // 10 - 7 missing; the other customers' overdue items are not added
        ask(mine.owner(), wholeView(), "How many total overdue items are there?").andExpect(jsonPath("$.value").value(6));
        choose("HANDOFFS", "LIST", "OVERDUE", "HANDOFF", 1);
        ask(other, wholeView(), "overdue?").andExpect(jsonPath("$.entries.length()").value(1)).andExpect(jsonPath("$.entries[0].title").value("Only theirs"));
        ask(mine.owner(), wholeView(), "overdue?").andExpect(jsonPath("$.entries[0].title").value("Overdue one"));
        assertThat(theirs.owner().id()).isNotEqualTo(mine.owner().id());
    }

    // ------------------------------------------------------------------ the AI can say which question, and nothing else

    @Test
    void aQuestionTheReportCannotAnswerIsDeclinedWithoutGuessing() throws Exception {
        Fixture f = fixture(SubscriptionPlan.HALF_YEARLY);
        ai.answer("{\"supported\":false}");

        ask(f.owner(), wholeView(), "What was my profit last month?").andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("UNSUPPORTED"))
                .andExpect(jsonPath("$.answer").value("I can't answer that from the available report data."))
                .andExpect(jsonPath("$.value").doesNotExist())
                .andExpect(jsonPath("$.entries.length()").value(0));
    }

    @Test
    void aCombinationThatMeansNothingIsDeclinedToo() throws Exception {
        Fixture f = fixture(SubscriptionPlan.HALF_YEARLY);

        choose("HANDOFFS", "TOP", "ANY", "HANDOFF", 1);   // "the handoff with the most handoffs"
        ask(f.owner(), wholeView(), "Which handoff has the most handoffs?").andExpect(jsonPath("$.kind").value("UNSUPPORTED"))
                .andExpect(jsonPath("$.value").doesNotExist());
    }

    @Test
    void nothingTheAiAddsCanChangeANumberOrMakeUpARequest() throws Exception {
        Fixture f = fixture(SubscriptionPlan.HALF_YEARLY);

        // The model "answers" with a figure of its own: only the chosen names are read, and the number is still the report's.
        ai.answer("{\"supported\":true,\"metric\":\"ITEMS_GIVEN\",\"operation\":\"TOTAL\",\"condition\":\"ANY\",\"groupBy\":\"NONE\",\"limit\":\"1\",\"from\":\"\",\"to\":\"\","
                + "\"answer\":\"Overall, 999999 items were handed over.\",\"value\":999999}");
        ask(f.owner(), wholeView(), "How many items were handed over?").andExpect(jsonPath("$.value").value(40))
                .andExpect(jsonPath("$.answer").value("Overall, 40 items were handed over."));

        // A name that is not one of the known ones is refused as an unusable answer, never acted on.
        for (String bad : new String[]{
                "{\"supported\":true,\"metric\":\"SET_ITEMS_GIVEN_TO_999\",\"operation\":\"TOTAL\",\"condition\":\"ANY\",\"groupBy\":\"NONE\",\"limit\":\"1\",\"from\":\"\",\"to\":\"\"}",
                "{\"supported\":true,\"metric\":\"ITEMS_GIVEN\",\"operation\":\"DELETE\",\"condition\":\"ANY\",\"groupBy\":\"NONE\",\"limit\":\"1\",\"from\":\"\",\"to\":\"\"}",
                "{\"supported\":true,\"metric\":\"ITEMS_GIVEN\",\"operation\":\"TOTAL\",\"condition\":\"1=1; DROP TABLE handoff\",\"groupBy\":\"NONE\",\"limit\":\"1\",\"from\":\"\",\"to\":\"\"}",
                "{\"supported\":true,\"metric\":\"ITEMS_GIVEN\",\"operation\":\"TOTAL\",\"condition\":\"ANY\",\"groupBy\":\"NONE\",\"limit\":\"100000\"}",
                "{\"supported\":true,\"metric\":\"ITEMS_GIVEN\",\"operation\":\"TOTAL\",\"condition\":\"ANY\",\"groupBy\":\"NONE\",\"limit\":1}",
                "{\"supported\":true,\"metric\":\"SELECT * FROM handoff\"}",
                "40 items were handed over", "{\"intent\":\"ITEMS_GIVEN\"}"}) {
            ai.answer(bad);
            ask(f.owner(), wholeView(), "anything").andExpect(status().isOk())
                    .andExpect(jsonPath("$.kind").value("UNAVAILABLE"))
                    .andExpect(jsonPath("$.value").doesNotExist())
                    .andExpect(jsonPath("$.answer").value(ReportAssistantService.UNAVAILABLE_MESSAGE));
        }
    }

    @Test
    void theAssistantNeverWritesAnything() throws Exception {
        Fixture f = fixture(SubscriptionPlan.HALF_YEARLY);
        List<String> tables = List.of("handoff", "handoff_item", "return_event", "return_line", "audit_event", "app_user", "customer_job",
                "customer_job_run", "subscription_history", "attachment");
        List<Long> before = tables.stream().map(t -> jdbc.queryForObject("SELECT COUNT(*) FROM " + t, Long.class)).toList();
        String accountBefore = jdbc.queryForObject("SELECT CONCAT(subscription_plan, '|', password_hash, '|', token_version) FROM app_user WHERE id = ?", String.class, f.owner().id());
        java.math.BigDecimal itemsBefore = jdbc.queryForObject("SELECT SUM(quantity) FROM handoff_item", java.math.BigDecimal.class);

        for (ReportIntent intent : ReportIntent.values()) {
            pick(f.owner(), wholeView(), intent.name()).andExpect(status().isOk());
        }
        for (String metric : List.of("HANDOFFS", "ITEMS_GIVEN", "ITEMS_RETURNED", "ITEMS_MISSING", "ITEMS_STILL_OUT")) {
            for (String operation : List.of("TOTAL", "LIST", "TOP")) {
                choose(metric, operation, "OVERDUE", "NONE", 2);
                ask(f.owner(), wholeView(), "ask " + metric + operation).andExpect(status().isOk());
            }
        }

        assertThat(tables.stream().map(t -> jdbc.queryForObject("SELECT COUNT(*) FROM " + t, Long.class)).toList()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT CONCAT(subscription_plan, '|', password_hash, '|', token_version) FROM app_user WHERE id = ?", String.class, f.owner().id()))
                .isEqualTo(accountBefore);
        assertThat(jdbc.queryForObject("SELECT SUM(quantity) FROM handoff_item", java.math.BigDecimal.class)).isEqualTo(itemsBefore);
    }

    @Test
    void theAiIsToldTheQuestionAndNothingAboutTheCustomerOrTheReport() throws Exception {
        Fixture f = fixture(SubscriptionPlan.HALF_YEARLY);
        total("ITEMS_GIVEN", "ANY");

        ask(f.owner(), wholeView(), "How many items were handed over?").andExpect(status().isOk());

        StubAiModel.Call call = ai.calls().get(0);
        assertThat(call.data()).isEqualTo("{\"question\":\"How many items were handed over?\"}");
        String everything = call.instruction() + call.data();
        assertThat(everything).doesNotContain("Overdue one", "Missing one", "Closed one", "recipient@example.test", f.owner().email(),
                f.owner().accountCode(), f.owner().token(), SECRET, "Chairs");
        assertThat(call.data()).doesNotContain("40");
        assertThat(call.instruction()).contains("Today is " + today() + ". This week is").contains("This month is " + today().withDayOfMonth(1) + " to " + today().withDayOfMonth(today().lengthOfMonth())).contains("from:").contains("to:").contains("between 1 Oct and 10 Oct");
        assertThat(call.instruction()).contains("ITEMS_STILL_OUT").contains("OVERDUE").contains("quotation");   // it is told what the words mean
    }

    // ------------------------------------------------------------------ the ready-made questions need no AI

    @Test
    void theReadyMadeQuestionsAreAnsweredWithoutAskingTheAi() throws Exception {
        Fixture f = fixture(SubscriptionPlan.HALF_YEARLY);

        mvc.perform(as(f.owner(), get(SUGGESTIONS))).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].intent").value(org.hamcrest.Matchers.hasItems("ITEMS_STILL_OUT", "OVERDUE_HANDOFF_LIST", "ITEMS_MISSING", "HANDOFFS_CLOSED", "OVERDUE_ITEMS")))
                .andExpect(jsonPath("$[?(@.intent=='ITEMS_STILL_OUT')].question").value("How many items are pending?"));

        pick(f.owner(), wholeView(), "ITEMS_MISSING").andExpect(status().isOk()).andExpect(jsonPath("$.value").value(3));
        pick(f.owner(), wholeView(), "OVERDUE_HANDOFF_LIST").andExpect(jsonPath("$.entries.length()").value(1));
        mvc.perform(as(f.owner(), get(SUGGESTIONS))).andExpect(jsonPath("$[?(@.intent=='OVERDUE_ITEMS')].question").value("How many total overdue items are there?"));
        pick(f.owner(), wholeView(), "OVERDUE_ITEMS").andExpect(jsonPath("$.value").value(6))
                .andExpect(jsonPath("$.answer").value("Overall, 6 items are still out (not back yet) in 1 overdue handoff (10 handed over on it)."));
        assertThat(ai.calls()).isEmpty();
    }

    @Test
    void aReadyMadeQuestionWorksEvenWhenTheAiIsOff() throws Exception {
        Fixture f = fixture(SubscriptionPlan.HALF_YEARLY);
        ai.configured(false);
        ai.fail(Reason.UNAVAILABLE);

        pick(f.owner(), wholeView(), "ITEMS_GIVEN").andExpect(jsonPath("$.kind").value("ANSWER")).andExpect(jsonPath("$.value").value(40));
    }

    // ------------------------------------------------------------------ when the AI does not help, the report still does

    @Test
    void aFailingAiIsAFriendlyMessageAndTheReportIsUntouched() throws Exception {
        Fixture f = fixture(SubscriptionPlan.HALF_YEARLY);
        String reportBefore = reportJson(f.owner(), today().minusDays(2), today().plusDays(1));

        ai.fail(Reason.UNAVAILABLE);
        ask(f.owner(), wholeView(), "How many items are missing?").andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.answer").value("AI assistance is temporarily unavailable. Your normal report is still available."))
                .andExpect(jsonPath("$.canRetry").value(true))
                .andExpect(jsonPath("$.value").doesNotExist());
        ai.fail(Reason.INVALID_RESPONSE);
        ask(f.owner(), wholeView(), "x").andExpect(jsonPath("$.kind").value("UNAVAILABLE")).andExpect(jsonPath("$.canRetry").value(true));
        ai.fail(Reason.RATE_LIMITED);
        ask(f.owner(), wholeView(), "x").andExpect(jsonPath("$.answer").value(ReportAssistantService.RATE_LIMITED_MESSAGE)).andExpect(jsonPath("$.canRetry").value(false));
        ai.reset();
        ai.configured(false);
        ask(f.owner(), wholeView(), "x").andExpect(jsonPath("$.answer").value(ReportAssistantService.NOT_CONFIGURED_MESSAGE)).andExpect(jsonPath("$.canRetry").value(false));
        assertThat(ai.calls()).isEmpty();

        assertThat(reportJson(f.owner(), today().minusDays(2), today().plusDays(1))).isEqualTo(reportBefore);   // the report itself is exactly as it was
    }

    @Test
    void theTimezoneMayBeLeftOut() throws Exception {
        Fixture f = fixture(SubscriptionPlan.HALF_YEARLY);
        mvc.perform(as(f.owner(), post(ASK).contentType(MediaType.APPLICATION_JSON).content("{\"suggestion\":\"ITEMS_GIVEN\"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.value").value(40)).andExpect(jsonPath("$.basis").value("All your handoffs"));
    }

    @Test
    void theRequestIsCheckedBeforeAnyAiRequestIsSpent() throws Exception {
        Fixture f = fixture(SubscriptionPlan.HALF_YEARLY);
        total("ITEMS_GIVEN", "ANY");

        mvc.perform(as(f.owner(), post(ASK).contentType(MediaType.APPLICATION_JSON).content("{\"timezone\":\"Mars/Olympus\",\"question\":\"How many?\"}")))
                .andExpect(status().isBadRequest());
        mvc.perform(as(f.owner(), post(ASK).contentType(MediaType.APPLICATION_JSON).content("{" + wholeView() + "}"))).andExpect(status().isBadRequest());   // no question at all
        ask(f.owner(), wholeView(), "q".repeat(301)).andExpect(status().isBadRequest());
        assertThat(ai.calls()).isEmpty();
    }

    // ------------------------------------------------------------------ who may use it

    @Test
    void monthlyAndQuarterlyCannotUseItWhileTheReportItselfStaysOpenToThem() throws Exception {
        for (SubscriptionPlan plan : List.of(SubscriptionPlan.MONTHLY, SubscriptionPlan.QUARTERLY)) {
            Fixture f = fixture(plan);
            total("ITEMS_GIVEN", "ANY");
            ask(f.owner(), wholeView(), "How many?").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("plan_required"))
                    .andExpect(jsonPath("$.detail").value("The AI Report Assistant is available on Half-Yearly and Yearly plans."));
            pick(f.owner(), wholeView(), "ITEMS_GIVEN").andExpect(status().isForbidden());
            mvc.perform(as(f.owner(), get(SUGGESTIONS))).andExpect(status().isForbidden());
            reportJson(f.owner(), today().minusDays(2), today().plusDays(1));   // the report is on every plan
        }
        assertThat(ai.calls()).isEmpty();
    }

    @Test
    void halfYearlyAndYearlyCanUseIt() throws Exception {
        for (SubscriptionPlan plan : List.of(SubscriptionPlan.HALF_YEARLY, SubscriptionPlan.YEARLY)) {
            Fixture f = fixture(plan);
            total("ITEMS_GIVEN", "ANY");
            ask(f.owner(), wholeView(), "How many?").andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("ANSWER"));
        }
        assertThat(ai.calls()).hasSize(2);
    }

    @Test
    void anAccountWithNoPlanOrNoSignInIsTurnedAway() throws Exception {
        ask(registerWithoutPlan(), wholeView(), "How many?").andExpect(status().isForbidden());
        mvc.perform(post(ASK).contentType(MediaType.APPLICATION_JSON).content("{" + wholeView() + ",\"question\":\"x\"}")).andExpect(status().isUnauthorized());
        mvc.perform(get(SUGGESTIONS)).andExpect(status().isUnauthorized());
        assertThat(ai.calls()).isEmpty();
    }

    @Test
    void theApiKeyIsNeverInAnythingTheBrowserReceives() throws Exception {
        Fixture f = fixture(SubscriptionPlan.YEARLY);
        total("ITEMS_GIVEN", "ANY");
        List<String> bodies = List.of(
                ask(f.owner(), wholeView(), "How many?").andReturn().getResponse().getContentAsString(),
                mvc.perform(as(f.owner(), get(SUGGESTIONS))).andReturn().getResponse().getContentAsString(),
                reportJson(f.owner(), today().minusDays(2), today().plusDays(1)));
        ai.fail(Reason.UNAVAILABLE);
        String failed = ask(f.owner(), wholeView(), "How many?").andReturn().getResponse().getContentAsString();
        for (String body : bodies) assertThat(body).doesNotContain(SECRET);
        assertThat(failed).doesNotContain(SECRET).doesNotContain("Exception").doesNotContain("google");
    }
}
