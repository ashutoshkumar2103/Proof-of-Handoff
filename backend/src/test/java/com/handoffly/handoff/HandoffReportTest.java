package com.handoffly.handoff;

import com.handoffly.testsupport.ApiTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The handoff report: one customer's handoffs created in a period, with totals, a filtered and sorted table, and a CSV.
 * It must agree with the handoff page figure for figure, see only the signed-in customer's own handoffs, follow the
 * subscription gate, and change nothing.
 */
class HandoffReportTest extends ApiTestBase {

    private static final String REPORT = "/api/v1/reports/handoffs";
    private static final String EXPORT = REPORT + "/export";
    private static final String PAST = "2020-01-01T00:00:00Z";
    private static final String FUTURE = "2099-01-01T00:00:00Z";

    // ------------------------------------------------------------------ fixtures

    private long draft(Account owner, String title) throws Exception {
        String safeTitle = title.replace("\\", "\\\\").replace("\"", "\\\"");
        String body = """
                {"title":"%s","senderName":"Sender","recipientName":"Recipient","recipientEmail":"recipient@example.test",
                 "items":[{"name":"Chairs","quantity":10,"unit":"pcs"}]}""".formatted(safeTitle);
        String created = mvc.perform(as(owner, post("/api/v1/handoffs").contentType(MediaType.APPLICATION_JSON).content(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(created, "$.id")).longValue();
    }

    /** Sent, but the recipient has not answered yet. */
    private long awaiting(Account owner, String title) throws Exception {
        long id = draft(owner, title);
        mvc.perform(as(owner, post("/api/v1/handoffs/" + id + "/submit"))).andExpect(status().isOk());
        return id;
    }

    private String detail(Account owner, long id) throws Exception {
        return mvc.perform(as(owner, get("/api/v1/handoffs/" + id))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private void record(Account owner, long id, int quantity, String condition) throws Exception {
        int itemId = JsonPath.read(detail(owner, id), "$.items[0].id");
        mvc.perform(as(owner, post("/api/v1/handoffs/" + id + "/returns").contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":[{\"itemId\":" + itemId + ",\"quantity\":" + quantity + ",\"condition\":\"" + condition + "\"}]}")))
                .andExpect(status().isCreated());
    }

    /** Backdates a handoff. The instant is bound the way the driver binds every other one, so it is read back as the same moment whatever zone the JVM is in. */
    private void createdAt(long handoffId, Instant at) {
        jdbc.update("UPDATE handoff SET created_at = ? WHERE id = ?", java.sql.Timestamp.from(at), handoffId);
    }

    private static Instant utc(int month, int day, int hour, int minute, int second) {
        return java.time.ZonedDateTime.of(2026, month, day, hour, minute, second, 0, ZoneOffset.UTC).toInstant();
    }

    /** The fixture most tests share: six handoffs in October 2026 in every state that matters to a report. */
    private record October(long draft, long awaiting, long partial, long fully, long closed, long missing) {}

    private October october(Account a) throws Exception {
        long draft = draft(a, "Q draft");
        long awaiting = awaiting(a, "Q awaiting");
        long partial = activeHandoff(a, "Q partial", PAST);      // 4 of 10 back, and past its return date
        record(a, partial, 4, "GOOD");
        long fully = activeHandoff(a, "Q fully", FUTURE);        // all 10 back, not closed yet
        record(a, fully, 10, "GOOD");
        long closed = activeHandoff(a, "Q closed", FUTURE);
        record(a, closed, 10, "GOOD");
        mvc.perform(as(a, post("/api/v1/handoffs/" + closed + "/close"))).andExpect(status().isOk());
        long missing = activeHandoff(a, "Q missing", FUTURE);    // 4 reported missing
        record(a, missing, 4, "MISSING");
        long day = 3;
        for (long id : new long[]{draft, awaiting, partial, fully, closed, missing}) {
            createdAt(id, utc(10, (int) day, 9, 30, 0));
            day += 4;
        }
        return new October(draft, awaiting, partial, fully, closed, missing);
    }

    // ------------------------------------------------------------------ requests

    private ResultActions report(Account who, String from, String to, String... extra) throws Exception {
        return mvc.perform(as(who, withParams(get(REPORT), from, to, extra)));
    }

    private ResultActions export(Account who, String from, String to, String... extra) throws Exception {
        return mvc.perform(as(who, withParams(get(EXPORT), from, to, extra)));
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder withParams(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, String from, String to, String... extra) {
        if (from != null) request.param("from", from);
        if (to != null) request.param("to", to);
        for (int i = 0; i + 1 < extra.length; i += 2) request.param(extra[i], extra[i + 1]);
        return request;
    }

    private String json(ResultActions r) throws Exception {
        return r.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static double number(String json, String path) {
        return ((Number) JsonPath.read(json, path)).doubleValue();
    }

    private static List<String> titles(String json) {
        return JsonPath.read(json, "$.handoffs.content[*].title");
    }

    private String codeOf(Account owner, long id) throws Exception {
        return JsonPath.read(detail(owner, id), "$.publicCode");
    }

    private String csv(ResultActions r) throws Exception {
        MvcResult res = r.andExpect(status().isOk()).andReturn();
        return res.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ totals

    @Test
    void theSummaryCountsEveryHandoffCreatedInThePeriodAndNothingElse() throws Exception {
        Account a = register();
        october(a);
        Account other = register();
        october(other);   // another customer's, same dates: none of it may count

        String json = json(report(a, "2026-10-01", "2026-10-31"));
        assertThat(number(json, "$.summary.created")).isEqualTo(6);
        assertThat(number(json, "$.summary.closed")).isEqualTo(1);
        assertThat(number(json, "$.summary.open")).isEqualTo(4);       // awaiting, partial, fully returned (not closed yet), missing
        assertThat(number(json, "$.summary.overdue")).isEqualTo(1);    // the partial one, past its return date
        assertThat(number(json, "$.summary.itemsGiven")).isEqualTo(50);      // five were sent; the draft gave nothing
        assertThat(number(json, "$.summary.itemsReturned")).isEqualTo(24);   // 4 + 10 + 10; the 4 reported missing are not "returned"
        assertThat(number(json, "$.summary.itemsMissing")).isEqualTo(4);
        // The rest of the 50 given: 10 not accepted yet + 6 still with the recipient (partial) + 6 not recovered (the missing one: 10 - 4).
        assertThat(number(json, "$.summary.itemsStillOut")).isEqualTo(22);
        assertThat(number(json, "$.summary.itemsRejected")).isZero();
        assertThat(number(json, "$.summary.itemsCancelled")).isZero();
        assertThat(number(json, "$.handoffs.totalElements")).isEqualTo(6);
    }

    @Test
    void theItemFiguresAlwaysAddUpToTheItemsGivenSoNoDifferenceHasToBeHuntedFor() throws Exception {
        Account a = register();
        // Everything that can happen to what was given: back, back and closed, partly back, reported missing, refused by the
        // recipient, cancelled, and never sent at all.
        long closed = activeHandoff(a, "R closed", FUTURE);
        record(a, closed, 10, "GOOD");
        mvc.perform(as(a, post("/api/v1/handoffs/" + closed + "/close"))).andExpect(status().isOk());
        long partial = activeHandoff(a, "R partial", FUTURE);
        record(a, partial, 4, "GOOD");
        long missing = activeHandoff(a, "R missing", FUTURE);
        record(a, missing, 3, "MISSING");
        long rejected = awaiting(a, "R rejected");
        mvc.perform(post("/api/v1/r/" + emailSender.extractLastToken() + "/reject").contentType(MediaType.APPLICATION_JSON)
                .content("{\"acknowledgementName\":\"Recipient\",\"reason\":\"Not what we ordered\"}")).andExpect(status().isOk());
        long cancelled = awaiting(a, "R cancelled");
        mvc.perform(as(a, post("/api/v1/handoffs/" + cancelled + "/cancel"))).andExpect(status().isOk());
        long awaitingStill = awaiting(a, "R awaiting");
        draft(a, "R draft");
        String json = json(report(a, "2000-01-01", "2099-12-31", "size", "50"));   // wide, so every handoff above is inside it

        assertThat((String) JsonPath.read(detail(a, rejected), "$.status")).isEqualTo("REJECTED");
        assertThat((String) JsonPath.read(detail(a, cancelled), "$.status")).isEqualTo("CANCELLED");
        assertThat(number(json, "$.summary.created")).isEqualTo(7);
        assertThat(number(json, "$.summary.itemsGiven")).isEqualTo(60);     // six were sent; the draft gave nothing
        assertThat(number(json, "$.summary.itemsReturned")).isEqualTo(14);  // 10 + 4
        assertThat(number(json, "$.summary.itemsMissing")).isEqualTo(3);
        assertThat(number(json, "$.summary.itemsRejected")).isEqualTo(10);  // all of what the recipient refused
        assertThat(number(json, "$.summary.itemsCancelled")).isEqualTo(10);
        assertThat(number(json, "$.summary.itemsStillOut")).isEqualTo(23);  // 6 (partial) + 7 (missing one: 10 - 3) + 10 (awaiting)

        double parts = number(json, "$.summary.itemsReturned") + number(json, "$.summary.itemsMissing")
                + number(json, "$.summary.itemsStillOut") + number(json, "$.summary.itemsRejected") + number(json, "$.summary.itemsCancelled");
        assertThat(parts).isEqualTo(number(json, "$.summary.itemsGiven"));

        // And the same split, worked out independently from each handoff's own page.
        double remainingOnPages = 0;
        double rejectedOnPage = number(detail(a, rejected), "$.totalRemaining");
        double cancelledOnPage = number(detail(a, cancelled), "$.totalRemaining");
        for (long id : new long[]{closed, partial, missing, awaitingStill}) remainingOnPages += number(detail(a, id), "$.totalRemaining");
        assertThat(number(json, "$.summary.itemsRejected")).isEqualTo(rejectedOnPage);
        assertThat(number(json, "$.summary.itemsCancelled")).isEqualTo(cancelledOnPage);
        assertThat(number(json, "$.summary.itemsStillOut")).isEqualTo(remainingOnPages);

        // The CSV says the same.
        assertThat(csv(export(a, "2000-01-01", "2099-12-31"))).contains("Items given,60", "Items returned,14", "Items missing,3",
                "Items still out,23", "Items on rejected handoffs,10", "Items on cancelled handoffs,10");
    }

    @Test
    void everyRowAndTotalAgreesWithTheHandoffPageFigureForFigure() throws Exception {
        Account a = register();
        October ids = october(a);
        // The awkward one: 4 missing, then 3 back (still 4 missing), then 5 more back (only 2 can still be missing).
        long awkward = activeHandoff(a, "Q netting", FUTURE);
        record(a, awkward, 4, "MISSING");
        record(a, awkward, 3, "GOOD");
        createdAt(awkward, utc(10, 28, 9, 0, 0));
        String afterThree = json(report(a, "2026-10-01", "2026-10-31", "sort", "reference,asc"));
        assertThat(rowFor(afterThree, awkward, "totalMissing")).isEqualTo(4);
        record(a, awkward, 5, "GOOD");

        String json = json(report(a, "2026-10-01", "2026-10-31", "size", "50"));
        BigDecimal given = BigDecimal.ZERO;
        BigDecimal returned = BigDecimal.ZERO;
        BigDecimal missing = BigDecimal.ZERO;
        List<Integer> rowIds = JsonPath.read(json, "$.handoffs.content[*].id");
        assertThat(rowIds).hasSize(7);
        for (int id : rowIds) {
            String page = detail(a, id);
            for (String field : new String[]{"totalOutgoing", "totalReturned", "totalMissing", "totalRemaining"}) {
                assertThat(rowFor(json, id, field)).as("%s of handoff %d", field, id).isEqualTo(number(page, "$." + field));
            }
            if (id != ids.draft()) {   // the draft has given nothing, so it is in no total
                given = given.add(BigDecimal.valueOf(number(page, "$.totalOutgoing")));
                returned = returned.add(BigDecimal.valueOf(number(page, "$.totalReturned")));
                missing = missing.add(BigDecimal.valueOf(number(page, "$.totalMissing")));
            }
        }
        assertThat(rowFor(json, awkward, "totalMissing")).isEqualTo(2);
        assertThat(number(json, "$.summary.itemsGiven")).isEqualTo(given.doubleValue());
        assertThat(number(json, "$.summary.itemsReturned")).isEqualTo(returned.doubleValue());
        assertThat(number(json, "$.summary.itemsMissing")).isEqualTo(missing.doubleValue());
    }

    private static double rowFor(String json, long handoffId, String field) {
        List<Number> value = JsonPath.read(json, "$.handoffs.content[?(@.id==" + handoffId + ")]." + field);
        return value.get(0).doubleValue();
    }

    @Test
    void anEmptyPeriodIsZeroEverywhereAndSaysSo() throws Exception {
        Account a = register();
        october(a);
        String json = json(report(a, "2030-01-01", "2030-01-31"));
        for (String field : new String[]{"created", "closed", "open", "overdue", "itemsGiven", "itemsReturned", "itemsMissing",
                "itemsStillOut", "itemsRejected", "itemsCancelled"}) {
            assertThat(number(json, "$.summary." + field)).as(field).isZero();
        }
        assertThat(titles(json)).isEmpty();
        assertThat(number(json, "$.handoffs.totalElements")).isZero();

        String file = csv(export(a, "2030-01-01", "2030-01-31"));
        assertThat(file).contains("Handoffs created,0").contains("Reference,Title,Recipient");
        assertThat(file).doesNotContain("Q ");

        assertThat(number(json(report(register(), "2026-10-01", "2026-10-31")), "$.summary.created")).isZero();   // an account with nothing
    }

    // ------------------------------------------------------------------ dates

    @Test
    void thePeriodIsWholeCalendarDaysInTheNamedZoneBothEndsIncluded() throws Exception {
        Account a = register();
        long sepLast = draft(a, "Sep 30 23:59:59 UTC");
        long octFirst = draft(a, "Oct 1 00:00:00 UTC");
        long octLast = draft(a, "Oct 31 23:59:59 UTC");
        long novFirst = draft(a, "Nov 1 00:00:00 UTC");
        createdAt(sepLast, utc(9, 30, 23, 59, 59));
        createdAt(octFirst, utc(10, 1, 0, 0, 0));
        createdAt(octLast, utc(10, 31, 23, 59, 59));
        createdAt(novFirst, utc(11, 1, 0, 0, 0));

        // No zone named means UTC, and the response says so.
        String utc = json(report(a, "2026-10-01", "2026-10-31", "sort", "created,asc"));
        assertThat(titles(utc)).containsExactly("Oct 1 00:00:00 UTC", "Oct 31 23:59:59 UTC");
        assertThat((String) JsonPath.read(utc, "$.period.timezone")).isEqualTo("UTC");
        assertThat((String) JsonPath.read(utc, "$.period.from")).isEqualTo("2026-10-01");
        assertThat((String) JsonPath.read(utc, "$.period.to")).isEqualTo("2026-10-31");

        // The same days in India (UTC+5:30) start five and a half hours earlier.
        assertThat(titles(json(report(a, "2026-10-01", "2026-10-31", "timezone", "Asia/Kolkata", "sort", "created,asc"))))
                .containsExactly("Sep 30 23:59:59 UTC", "Oct 1 00:00:00 UTC");
        // And in California (UTC-7 then) seven hours later.
        assertThat(titles(json(report(a, "2026-10-01", "2026-10-31", "timezone", "America/Los_Angeles", "sort", "created,asc"))))
                .containsExactly("Oct 31 23:59:59 UTC", "Nov 1 00:00:00 UTC");

        // One day is a valid period, and its last second is still inside it.
        assertThat(titles(json(report(a, "2026-10-31", "2026-10-31")))).containsExactly("Oct 31 23:59:59 UTC");
        assertThat(titles(json(report(a, "2026-11-01", "2026-11-01")))).containsExactly("Nov 1 00:00:00 UTC");
        assertThat(titles(json(report(a, "2026-09-30", "2026-11-01", "size", "10")))).hasSize(4);
    }

    @Test
    void anInvalidPeriodIsRefusedWithAClearMessage() throws Exception {
        Account a = register();

        for (boolean forExport : new boolean[]{false, true}) {
            ResultActions backwards = forExport ? export(a, "2026-10-31", "2026-10-01") : report(a, "2026-10-31", "2026-10-01");
            backwards.andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value("The start date must not be after the end date."));
            for (String[] bad : new String[][]{{"not-a-date", "2026-10-01"}, {"2026-13-45", "2026-10-01"}, {"2026-10-01", "31/10/2026"},
                    {"2026-10-01", null}, {null, "2026-10-01"}, {null, null}}) {
                ResultActions r = forExport ? export(a, bad[0], bad[1]) : report(a, bad[0], bad[1]);
                r.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("invalid_parameter"));
            }
            ResultActions zone = forExport ? export(a, "2026-10-01", "2026-10-31", "timezone", "Mars/Olympus")
                    : report(a, "2026-10-01", "2026-10-31", "timezone", "Mars/Olympus");
            zone.andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("not recognised")));
        }
        // Dates no one means (or too large to count a day past) are refused, not a server error.
        report(a, "2026-10-01", "+999999999-12-31").andExpect(status().isBadRequest());
        report(a, "-999999999-01-01", "2026-10-01").andExpect(status().isBadRequest());
        report(a, "1969-12-31", "2026-10-01").andExpect(status().isBadRequest());
        export(a, "2026-10-01", "+999999999-12-31").andExpect(status().isBadRequest());
        report(a, "1970-01-01", "9998-12-31").andExpect(status().isOk());   // the widest period that is accepted
    }

    // ------------------------------------------------------------------ status filter, sorting, paging

    @Test
    void theStatusFilterNarrowsTheTableButNeverTheTotals() throws Exception {
        Account a = register();
        october(a);
        String all = json(report(a, "2026-10-01", "2026-10-31", "size", "50"));

        String closed = json(report(a, "2026-10-01", "2026-10-31", "status", "CLOSED"));
        assertThat(titles(closed)).containsExactly("Q closed");
        assertThat(number(closed, "$.handoffs.totalElements")).isEqualTo(1);
        assertThat((Object) JsonPath.read(closed, "$.summary")).isEqualTo(JsonPath.read(all, "$.summary"));

        assertThat(titles(json(report(a, "2026-10-01", "2026-10-31", "status", "DRAFT", "status", "AWAITING_RECIPIENT", "sort", "created,asc"))))
                .containsExactly("Q draft", "Q awaiting");
        assertThat(titles(json(report(a, "2026-10-01", "2026-10-31", "status", "FULLY_RETURNED")))).containsExactly("Q fully");
        assertThat(titles(json(report(a, "2026-10-01", "2026-10-31", "status", "CANCELLED")))).isEmpty();

        // "Overdue" is derived (past its return date while items are out), exactly as the badge on the Dashboard is.
        assertThat(titles(json(report(a, "2026-10-01", "2026-10-31", "overdue", "true")))).containsExactly("Q partial");
        assertThat(titles(json(report(a, "2026-10-01", "2026-10-31", "overdue", "true", "status", "PARTIALLY_RETURNED")))).containsExactly("Q partial");
        assertThat(titles(json(report(a, "2026-10-01", "2026-10-31", "overdue", "true", "status", "CLOSED")))).isEmpty();

        report(a, "2026-10-01", "2026-10-31", "status", "SHIPPED").andExpect(status().isBadRequest());
    }

    @Test
    void theTableSortsByEveryColumnWithTheUnknownAlwaysLastAndPagesCorrectly() throws Exception {
        Account a = register();
        october(a);
        String p = "2026-10-01";
        String q = "2026-10-31";

        assertThat(titles(json(report(a, p, q, "sort", "title,asc", "size", "50"))))
                .containsExactly("Q awaiting", "Q closed", "Q draft", "Q fully", "Q missing", "Q partial");
        assertThat(titles(json(report(a, p, q, "sort", "title,desc", "size", "50")))).first().isEqualTo("Q partial");
        assertThat(titles(json(report(a, p, q, "sort", "created,desc", "size", "50")))).first().isEqualTo("Q missing");
        assertThat(titles(json(report(a, p, q, "sort", "reference,asc", "size", "50")))).first().isEqualTo("Q draft");
        // The draft has given nothing and has no return date, so it is last whichever way the column is sorted.
        assertThat(titles(json(report(a, p, q, "sort", "given,desc", "size", "50")))).last().isEqualTo("Q draft");
        assertThat(titles(json(report(a, p, q, "sort", "given,asc", "size", "50")))).last().isEqualTo("Q draft");
        for (String column : new String[]{"expectedReturn", "returned", "missing", "recipient", "status"}) {
            assertThat(titles(json(report(a, p, q, "sort", column + ",asc", "size", "50")))).as(column).hasSize(6);
        }
        assertThat(titles(json(report(a, p, q, "sort", "expectedReturn,asc", "size", "50"))).subList(0, 2))
                .containsExactly("Q partial", "Q fully");   // 2020 before 2099; then the ones with no date, in reference order
        assertThat(titles(json(report(a, p, q, "sort", "status,asc", "size", "50"))).get(0)).isEqualTo("Q draft");   // lifecycle order

        report(a, p, q, "sort", "password,asc").andExpect(status().isBadRequest());

        String second = json(report(a, p, q, "sort", "title,asc", "size", "4", "page", "1"));
        assertThat(titles(second)).containsExactly("Q missing", "Q partial");
        assertThat(number(second, "$.handoffs.totalElements")).isEqualTo(6);
        assertThat(number(second, "$.handoffs.totalPages")).isEqualTo(2);
        assertThat((Boolean) JsonPath.read(second, "$.handoffs.last")).isTrue();
        assertThat(titles(json(report(a, p, q, "size", "4", "page", "9")))).isEmpty();   // past the end: empty, not an error
    }

    // ------------------------------------------------------------------ whose data

    @Test
    void aCustomerSeesOnlyTheirOwnHandoffsInTheReportAndTheCsv() throws Exception {
        Account a = register();
        Account b = register();
        draft(a, "Only A one");
        draft(a, "Only A two");
        long bHandoff = activeHandoff(b, "Only B", FUTURE);
        record(b, bHandoff, 3, "GOOD");

        String forA = json(report(a, "2000-01-01", "2099-12-31"));
        assertThat(titles(forA)).containsExactlyInAnyOrder("Only A one", "Only A two");
        assertThat(number(forA, "$.summary.created")).isEqualTo(2);
        assertThat(number(forA, "$.summary.itemsReturned")).isZero();

        String forB = json(report(b, "2000-01-01", "2099-12-31"));
        assertThat(titles(forB)).containsExactly("Only B");
        assertThat(number(forB, "$.summary.itemsReturned")).isEqualTo(3);

        // Nothing in the request can change whose report it is: there is no parameter for it, and these are ignored.
        String tampered = json(report(a, "2000-01-01", "2099-12-31", "ownerId", String.valueOf(b.id()), "userId", String.valueOf(b.id()),
                "accountCode", b.accountCode(), "handoffId", String.valueOf(bHandoff)));
        assertThat(titles(tampered)).containsExactlyInAnyOrder("Only A one", "Only A two");

        String fileForA = csv(export(a, "2000-01-01", "2099-12-31", "ownerId", String.valueOf(b.id())));
        assertThat(fileForA).contains("Only A one", "Only A two").doesNotContain("Only B");
        assertThat(csv(export(b, "2000-01-01", "2099-12-31"))).contains("Only B").doesNotContain("Only A");
    }

    // ------------------------------------------------------------------ CSV

    @Test
    void theCsvCarriesThePeriodTheFilterTheSummaryAndEveryMatchingRow() throws Exception {
        Account a = register();
        October ids = october(a);
        String draft = codeOf(a, ids.draft());
        String awaiting = codeOf(a, ids.awaiting());
        String partial = codeOf(a, ids.partial());
        String fully = codeOf(a, ids.fully());
        String closed = codeOf(a, ids.closed());
        String missing = codeOf(a, ids.missing());

        MvcResult res = export(a, "2026-10-01", "2026-10-31").andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Disposition", org.hamcrest.Matchers.containsString("handoff-report-2026-10-01-to-2026-10-31.csv")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                        .string("Content-Type", org.hamcrest.Matchers.startsWith("text/csv")))
                .andReturn();
        String file = res.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(file).startsWith("﻿");
        List<String> lines = List.of(file.substring(1).split("\r\n", -1));
        assertThat(lines.subList(0, 5)).containsExactly("HandOffly handoff report", "Handoffs created from,2026-10-01",
                "Handoffs created to (included),2026-10-31", "Time zone,UTC", "Showing,All statuses");
        assertThat(lines.get(5)).startsWith("Generated (UTC),");
        assertThat(lines).contains("Handoffs created,6", "Handoffs closed,1", "Open handoffs (now),4", "Overdue handoffs (now),1",
                "Items given,50", "Items returned,24", "Items missing,4",
                "Reference,Title,Recipient,Created,Expected return,Status,Overdue,Given,Returned,Missing");

        // Oldest first by default: the draft (blank quantities, no return date), then the others in the order they were made.
        int header = lines.indexOf("Reference,Title,Recipient,Created,Expected return,Status,Overdue,Given,Returned,Missing");
        List<String> rows = lines.subList(header + 1, lines.size()).stream().filter(l -> !l.isEmpty()).toList();
        assertThat(rows).containsExactly(
                draft + ",Q draft,Recipient,2026-10-03,,Draft,No,,,",
                awaiting + ",Q awaiting,Recipient,2026-10-07,,Awaiting recipient,No,10,0,0",
                partial + ",Q partial,Recipient Q partial,2026-10-11,2020-01-01,Partially returned,Yes,10,4,0",
                fully + ",Q fully,Recipient Q fully,2026-10-15,2099-01-01,Fully returned,No,10,10,0",
                closed + ",Q closed,Recipient Q closed,2026-10-19,2099-01-01,Closed,No,10,10,0",
                missing + ",Q missing,Recipient Q missing,2026-10-23,2099-01-01,Partially returned,No,10,0,4");

        // The filter is named, the rows follow it, and the summary still describes the whole period.
        String closedOnly = csv(export(a, "2026-10-01", "2026-10-31", "status", "CLOSED"));
        assertThat(closedOnly).contains("Showing,Closed", "Handoffs created,6", "Q closed").doesNotContain("Q draft").doesNotContain("Q missing");
        assertThat(csv(export(a, "2026-10-01", "2026-10-31", "overdue", "true"))).contains("Showing,Overdue handoffs only", "Q partial")
                .doesNotContain("Q closed");
        assertThat(csv(export(a, "2026-10-01", "2026-10-31", "status", "DRAFT", "status", "CLOSED")))
                .contains("Showing,Draft or Closed", "Q draft", "Q closed").doesNotContain("Q awaiting");

        // Dates are the days in the report's zone, and the zone is stated.
        String kolkata = csv(export(a, "2026-10-01", "2026-10-31", "timezone", "Asia/Kolkata"));
        assertThat(kolkata).contains("Time zone,Asia/Kolkata", "Q draft,Recipient,2026-10-03,");
        assertThat(csv(export(a, "2026-10-01", "2026-10-31", "sort", "title,desc"))).containsSubsequence("Q partial", "Q missing", "Q draft");
    }

    @Test
    void textFromACustomerCannotRunAsAFormulaAndCommasAndQuotesStayInTheirCell() throws Exception {
        Account a = register();
        long formula = draft(a, "=HYPERLINK(\"http://evil.example\",\"click\")");
        long plus = draft(a, "+1+1");
        long awkward = draft(a, "Chairs, tables and \"more\"");
        for (long id : new long[]{formula, plus, awkward}) createdAt(id, utc(10, 5, 9, 0, 0));

        String file = csv(export(a, "2026-10-01", "2026-10-31"));
        assertThat(file).contains("\"'=HYPERLINK(\"\"http://evil.example\"\",\"\"click\"\")\"");   // apostrophe first, then quoted
        assertThat(file).contains(",'+1+1,").doesNotContain(",+1+1,");
        assertThat(file).contains("\"Chairs, tables and \"\"more\"\"\"");
    }

    // ------------------------------------------------------------------ subscription

    @Test
    void anyActivePlanMayUseReports() throws Exception {
        for (com.handoffly.user.SubscriptionPlan plan : com.handoffly.user.SubscriptionPlan.values()) {
            Account a = register(plan);
            report(a, "2026-10-01", "2026-10-31").andExpect(status().isOk());
            export(a, "2026-10-01", "2026-10-31").andExpect(status().isOk());
        }
    }

    @Test
    void anAccountWithNoPlanAndOneWhoseSubscriptionEndedAreTurnedAwayAndGetAccessBackWhenRenewed() throws Exception {
        Account unpaid = registerWithoutPlan();
        report(unpaid, "2026-10-01", "2026-10-31").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("no_active_subscription"));
        export(unpaid, "2026-10-01", "2026-10-31").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("no_active_subscription"));

        Account lapsed = register();
        draft(lapsed, "Made while active");
        jdbc.update("UPDATE app_user SET plan_valid_until = TIMESTAMPADD(DAY, -1, CURRENT_TIMESTAMP) WHERE id = ?", lapsed.id());
        report(lapsed, "2000-01-01", "2099-12-31").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("subscription_expired"));
        export(lapsed, "2000-01-01", "2099-12-31").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("subscription_expired"));
        // The gate is asked before the request is looked at, so a bad request is not answered differently from a good one.
        report(lapsed, "2026-10-31", "2026-10-01").andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("subscription_expired"));

        jdbc.update("UPDATE app_user SET plan_valid_until = NULL WHERE id = ?", lapsed.id());
        assertThat(titles(json(report(lapsed, "2000-01-01", "2099-12-31")))).containsExactly("Made while active");
    }

    @Test
    void reportsNeedASignedInCustomer() throws Exception {
        mvc.perform(get(REPORT).param("from", "2026-10-01").param("to", "2026-10-31")).andExpect(status().isUnauthorized());
        mvc.perform(get(EXPORT).param("from", "2026-10-01").param("to", "2026-10-31")).andExpect(status().isUnauthorized());
        mvc.perform(get(REPORT).param("from", "2026-10-01").param("to", "2026-10-31")
                .header("Authorization", "Bearer not-a-token")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ read-only

    @Test
    void aReportChangesNothingAndOffersNoWayToChangeAnything() throws Exception {
        Account a = register();
        October ids = october(a);
        List<java.util.Map<String, Object>> before =
                jdbc.queryForList("SELECT id, status, updated_at, version FROM handoff WHERE owner_user_id = ? ORDER BY id", a.id());
        long events = jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class);
        long returnLines = jdbc.queryForObject("SELECT COUNT(*) FROM return_line", Long.class);

        json(report(a, "2026-10-01", "2026-10-31"));
        csv(export(a, "2026-10-01", "2026-10-31"));

        assertThat(jdbc.queryForList("SELECT id, status, updated_at, version FROM handoff WHERE owner_user_id = ? ORDER BY id", a.id()))
                .isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class)).isEqualTo(events);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM return_line", Long.class)).isEqualTo(returnLines);

        // Nothing but reading is offered.
        for (String path : new String[]{REPORT, EXPORT, REPORT + "/" + ids.closed()}) {
            mvc.perform(as(a, post(path).contentType(MediaType.APPLICATION_JSON).content("{}"))).andExpect(status().is4xxClientError());
            mvc.perform(as(a, put(path).contentType(MediaType.APPLICATION_JSON).content("{}"))).andExpect(status().is4xxClientError());
            mvc.perform(as(a, delete(path))).andExpect(status().is4xxClientError());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM handoff WHERE owner_user_id = ?", Long.class, a.id())).isEqualTo(6);
    }

    // ------------------------------------------------------------------ size limit

    @Test
    void aPeriodTooLargeToReadAtOnceIsRefusedAndOneAtTheLimitIsNot() throws Exception {
        Account a = register();
        String insert = "INSERT INTO handoff (version, created_at, updated_at, public_code, owner_user_id, title, sender_name, "
                + "recipient_name, recipient_email, status) SELECT 0, TIMESTAMP '2026-10-10 10:00:00', TIMESTAMP '2026-10-10 10:00:00', "
                + "'BULK-' || X, CAST(? AS BIGINT), 'Bulk', 'S', 'R', 'r@example.test', 'DRAFT' FROM SYSTEM_RANGE(%d, %d)";
        try {
            jdbc.update(insert.formatted(1, HandoffReportService.MAX_HANDOFFS), a.id());
            String atLimit = json(report(a, "2026-10-01", "2026-10-31"));
            assertThat(number(atLimit, "$.summary.created")).isEqualTo(HandoffReportService.MAX_HANDOFFS);
            assertThat(titles(atLimit)).hasSize(20);   // one page, not ten thousand rows

            jdbc.update(insert.formatted(HandoffReportService.MAX_HANDOFFS + 1, HandoffReportService.MAX_HANDOFFS + 1), a.id());
            report(a, "2026-10-01", "2026-10-31").andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Choose a shorter period")));
            export(a, "2026-10-01", "2026-10-31").andExpect(status().isBadRequest());
            // A shorter period of the same account is fine.
            assertThat(number(json(report(a, "2026-09-01", "2026-09-30")), "$.summary.created")).isZero();
        } finally {
            jdbc.update("DELETE FROM handoff WHERE owner_user_id = ?", a.id());
        }
    }
}
