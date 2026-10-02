package com.handoffly.job;

import com.handoffly.handoff.HandoffActivityService;
import com.handoffly.notification.EmailMessage;
import com.handoffly.testsupport.ApiTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The customer's four jobs as the app uses them: set up, scheduled, run, and the email each run sends. */
class CustomerJobTest extends ApiTestBase {

    private static final String JOBS = "/api/v1/account/jobs";

    @Autowired
    private CustomerJobService jobService;
    @Autowired
    private HandoffActivityService activity;

    // ------------------------------------------------------------------ helpers

    private static String daysFromToday(int days) {
        return LocalDate.now(ZoneOffset.UTC).plusDays(days).atTime(12, 0).toInstant(ZoneOffset.UTC).toString();
    }

    private String json(Account who, String path) throws Exception {
        return mvc.perform(as(who, get(path))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String codeOf(Account owner, long handoffId) throws Exception {
        return JsonPath.read(json(owner, "/api/v1/handoffs/" + handoffId), "$.publicCode");
    }

    /** One job as the list shows it. */
    private net.minidev.json.JSONArray job(Account who, JobType type) throws Exception {
        return JsonPath.read(json(who, JOBS), "$[?(@.type=='" + type + "')]");
    }

    private String run(Account who, JobType type) throws Exception {
        return mvc.perform(as(who, post(JOBS + "/" + type + "/run"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private void enable(Account who, JobType type) throws Exception {
        mvc.perform(as(who, put(JOBS + "/" + type + "/enabled").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}"))).andExpect(status().isOk());
    }

    private void schedule(Account who, JobType type, String cron, String zone) throws Exception {
        mvc.perform(as(who, put(JOBS + "/" + type + "/schedule").contentType(MediaType.APPLICATION_JSON)
                .content("{\"cronExpression\":\"" + cron + "\",\"timezone\":\"" + zone + "\"}"))).andExpect(status().isOk());
    }

    /** A handoff with 10 chairs out, one of them reported missing... all ten: returns {id, itemId}. */
    private long[] activeWithMissing(Account owner, String title) throws Exception {
        long id = activeHandoff(owner, title, null);
        int itemId = JsonPath.read(json(owner, "/api/v1/handoffs/" + id), "$.items[0].id");
        mvc.perform(as(owner, post("/api/v1/handoffs/" + id + "/returns").contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":[{\"itemId\":" + itemId + ",\"quantity\":4,\"condition\":\"MISSING\"}]}")))
                .andExpect(status().isCreated());
        return new long[]{id, itemId};
    }

    /** Takes back the rest in good condition, has the recipient confirm what is missing, and closes with a reason. */
    private void confirmMissingAndForceClose(Account owner, long id) throws Exception {
        int itemId = JsonPath.read(json(owner, "/api/v1/handoffs/" + id), "$.items[0].id");
        mvc.perform(as(owner, post("/api/v1/handoffs/" + id + "/returns").contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":[{\"itemId\":" + itemId + ",\"quantity\":6,\"condition\":\"GOOD\"}]}")))
                .andExpect(status().isCreated());
        mvc.perform(as(owner, post("/api/v1/handoffs/" + id + "/request-missing-confirmation"))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/r/" + emailSender.extractLastToken() + "/confirm-missing")
                .contentType(MediaType.APPLICATION_JSON).content("{\"acknowledgementName\":\"Recipient\"}")).andExpect(status().isOk());
        mvc.perform(as(owner, post("/api/v1/handoffs/" + id + "/close").contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Items written off as lost.\"}"))).andExpect(status().isOk());
    }

    /** The job emails this customer has received (the handoff notices to recipients go elsewhere). */
    private List<EmailMessage> mailTo(Account who) {
        return emailSender.messagesTo(who.email());
    }

    // ------------------------------------------------------------------ setting up

    @Test
    void aCustomerGetsFourJobsSwitchedOffWithDefaultSchedules() throws Exception {
        Account a = register();
        mvc.perform(as(a, get(JOBS))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[*].type").value(org.hamcrest.Matchers.containsInAnyOrder(
                        "RETURN_REMINDER", "OVERDUE_REMINDER", "MISSING_ITEM_REMINDER", "WEEKLY_SUMMARY")))
                .andExpect(jsonPath("$[*].enabled").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(false))))
                .andExpect(jsonPath("$[0].timezone").value("UTC"));
        // Looking again does not set up a second set.
        mvc.perform(as(a, get(JOBS))).andExpect(jsonPath("$.length()").value(4));
        assertThat(jdbc.queryForObject("select count(*) from customer_job where user_id = ?", Integer.class, a.id())).isEqualTo(4);
    }

    @Test
    void jobsNeedACustomerSignIn() throws Exception {
        StaffAccount staff = registerStaff(com.handoffly.support.staff.SupportRole.ADMIN);
        mvc.perform(get(JOBS)).andExpect(status().isUnauthorized());
        mvc.perform(post(JOBS + "/run-all")).andExpect(status().isUnauthorized());
        mvc.perform(as(staff, get(JOBS))).andExpect(status().isUnauthorized());   // a staff token is not a customer token
        mvc.perform(as(staff, post(JOBS + "/run-all"))).andExpect(status().isUnauthorized());
    }

    @Test
    void anInvalidScheduleIsRefusedAndNothingIsSaved() throws Exception {
        Account a = register();
        json(a, JOBS);
        for (String cron : new String[]{"not a cron", "* * * * *", "0 0 25 * * *", "0 0 9 * * * && echo hi", "* * * * * *", "0 */5 * * * *"}) {
            mvc.perform(as(a, put(JOBS + "/RETURN_REMINDER/schedule").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"cronExpression\":\"" + cron + "\",\"timezone\":\"UTC\"}")))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(as(a, put(JOBS + "/RETURN_REMINDER/schedule").contentType(MediaType.APPLICATION_JSON)
                .content("{\"cronExpression\":\"0 0 9 * * *\",\"timezone\":\"Mars/Olympus\"}"))).andExpect(status().isBadRequest());
        mvc.perform(as(a, put(JOBS + "/RETURN_REMINDER/schedule").contentType(MediaType.APPLICATION_JSON)
                .content("{\"cronExpression\":\"\",\"timezone\":\"UTC\"}"))).andExpect(status().isBadRequest());
        // An unknown kind of job is not found rather than created.
        mvc.perform(as(a, put(JOBS + "/NO_SUCH_JOB/enabled").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}")))
                .andExpect(status().is4xxClientError());

        assertThat(job(a, JobType.RETURN_REMINDER).toString()).contains("\"cronExpression\":\"0 0 9 * * *\"");
        assertThat(jdbc.queryForObject("select cron_expression from customer_job where user_id = ? and job_type = 'RETURN_REMINDER'",
                String.class, a.id())).isEqualTo("0 0 9 * * *");
    }

    @Test
    void aValidScheduleIsSavedWithItsNextRunsAndNothingIsSent() throws Exception {
        Account a = register();
        enable(a, JobType.WEEKLY_SUMMARY);
        schedule(a, JobType.WEEKLY_SUMMARY, "0 30 8 * * MON-FRI", "Asia/Kolkata");
        mvc.perform(as(a, get(JOBS))).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type=='WEEKLY_SUMMARY')].cronExpression").value("0 30 8 * * MON-FRI"))
                .andExpect(jsonPath("$[?(@.type=='WEEKLY_SUMMARY')].timezone").value("Asia/Kolkata"))
                .andExpect(jsonPath("$[?(@.type=='WEEKLY_SUMMARY')].upcomingRuns[0]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.type=='WEEKLY_SUMMARY')].upcomingRuns[2]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.type=='WEEKLY_SUMMARY')].lastRunAt").value(org.hamcrest.Matchers.contains((Object) null)));
        assertThat(mailTo(a)).isEmpty();
    }

    @Test
    void jobsOfOneCustomerAreNeverReadOrChangedThroughAnother() throws Exception {
        Account a = register();
        Account b = register();
        enable(a, JobType.RETURN_REMINDER);
        schedule(a, JobType.RETURN_REMINDER, "0 0 7 * * *", "Europe/London");
        mvc.perform(as(b, get(JOBS))).andExpect(jsonPath("$[?(@.type=='RETURN_REMINDER')].enabled").value(false))
                .andExpect(jsonPath("$[?(@.type=='RETURN_REMINDER')].cronExpression").value("0 0 9 * * *"))
                .andExpect(jsonPath("$[?(@.type=='RETURN_REMINDER')].timezone").value("UTC"));
        assertThat(jdbc.queryForObject("select count(*) from customer_job where user_id = ?", Integer.class, b.id())).isEqualTo(4);
    }

    @Test
    void oneCustomerCannotHoldTwoOfTheSameJob() throws Exception {
        Account a = register();
        json(a, JOBS);
        assertThatThrownBy(() -> jdbc.update("insert into customer_job (version, created_at, updated_at, user_id, job_type, enabled, "
                + "cron_expression, timezone) values (0, current_timestamp, current_timestamp, ?, 'RETURN_REMINDER', false, "
                + "'0 0 9 * * *', 'UTC')", a.id())).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ------------------------------------------------------------------ running by hand

    @Test
    void runningNowChangesNeitherTheScheduleNorWhetherTheJobIsOn() throws Exception {
        Account a = register();
        long due = activeHandoff(a, "Due tomorrow", daysFromToday(1));
        enable(a, JobType.RETURN_REMINDER);
        schedule(a, JobType.RETURN_REMINDER, "0 15 6 * * *", "Asia/Kolkata");
        String before = job(a, JobType.RETURN_REMINDER).toString();
        Object nextBefore = JsonPath.read(before, "$[0].nextRunAt");

        mvc.perform(as(a, post(JOBS + "/RETURN_REMINDER/run"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"));

        String after = job(a, JobType.RETURN_REMINDER).toString();
        assertThat((Object) JsonPath.read(after, "$[0].nextRunAt")).isEqualTo(nextBefore);
        assertThat((Object) JsonPath.read(after, "$[0].cronExpression")).isEqualTo("0 15 6 * * *");
        assertThat((Object) JsonPath.read(after, "$[0].timezone")).isEqualTo("Asia/Kolkata");
        assertThat((Object) JsonPath.read(after, "$[0].enabled")).isEqualTo(true);
        assertThat((Object) JsonPath.read(after, "$[0].lastStatus")).isEqualTo("SENT");
        assertThat((Object) JsonPath.read(after, "$[0].lastRunAt")).isNotNull();
        assertThat(codeOf(a, due)).isNotBlank();

        // A job that is switched off can still be run by hand, and stays off.
        mvc.perform(as(a, post(JOBS + "/OVERDUE_REMINDER/run"))).andExpect(status().isOk());
        mvc.perform(as(a, get(JOBS))).andExpect(jsonPath("$[?(@.type=='OVERDUE_REMINDER')].enabled").value(false))
                .andExpect(jsonPath("$[?(@.type=='OVERDUE_REMINDER')].nextRunAt").value(org.hamcrest.Matchers.contains((Object) null)));
    }

    @Test
    void runAllNowRunsTheFourJobsOfThatCustomerOnly() throws Exception {
        Account a = register();
        Account b = register();
        long soon = activeHandoff(a, "A due soon", daysFromToday(1));
        long late = activeHandoff(a, "A overdue", Instant.now().minus(3, ChronoUnit.DAYS).toString());
        long[] missing = activeWithMissing(a, "A missing");
        activeHandoff(b, "B overdue", Instant.now().minus(3, ChronoUnit.DAYS).toString());
        json(b, JOBS);
        String soonCode = codeOf(a, soon);
        String lateCode = codeOf(a, late);
        String missingCode = codeOf(a, missing[0]);

        String result = mvc.perform(as(a, post(JOBS + "/run-all"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(result, "$[*].type")).containsExactlyInAnyOrder(
                "RETURN_REMINDER", "OVERDUE_REMINDER", "MISSING_ITEM_REMINDER", "WEEKLY_SUMMARY");

        // One email per job, all to A, none of them mentioning B's handoff.
        List<EmailMessage> sent = mailTo(a);
        assertThat(sent).hasSize(4);
        assertThat(sent).allSatisfy(m -> assertThat(m.textBody()).doesNotContain("B overdue"));   // public codes are per customer, so titles tell them apart
        assertThat(sent.get(0).textBody()).contains(soonCode).doesNotContain(lateCode);
        assertThat(sent.get(1).textBody()).contains(lateCode);
        assertThat(sent.get(2).textBody()).contains(missingCode);
        assertThat(mailTo(b)).isEmpty();

        // B's jobs did not run, and A's schedules were not moved.
        mvc.perform(as(b, get(JOBS))).andExpect(jsonPath("$[*].lastRunAt").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.nullValue())));
        mvc.perform(as(a, get(JOBS))).andExpect(jsonPath("$[*].enabled").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is(false))))
                .andExpect(jsonPath("$[*].lastRunAt").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.notNullValue())));
    }

    // ------------------------------------------------------------------ what each job reports

    @Test
    void theReturnReminderCoversTomorrowAndTheDayAfterOnly() throws Exception {
        Account a = register();
        String today = codeOf(a, activeHandoff(a, "Today", daysFromToday(0)));
        String tomorrow = codeOf(a, activeHandoff(a, "Tomorrow", daysFromToday(1)));
        String dayAfter = codeOf(a, activeHandoff(a, "Day after", daysFromToday(2)));
        String third = codeOf(a, activeHandoff(a, "Third day", daysFromToday(3)));
        String past = codeOf(a, activeHandoff(a, "Past", Instant.now().minus(3, ChronoUnit.DAYS).toString()));
        String none = codeOf(a, activeHandoff(a, "No date", null));

        mvc.perform(as(a, post(JOBS + "/RETURN_REMINDER/run"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.handoffs.length()").value(2));

        assertThat(mailTo(a)).hasSize(1);
        String body = mailTo(a).get(0).textBody();
        assertThat(body).contains(tomorrow, dayAfter).doesNotContain(today, third, past, none);
    }

    @Test
    void theOverdueReminderListsOverdueHandoffsAndNotUpcomingOnes() throws Exception {
        Account a = register();
        String past = codeOf(a, activeHandoff(a, "Long gone", Instant.now().minus(5, ChronoUnit.DAYS).toString()));
        String tomorrow = codeOf(a, activeHandoff(a, "Tomorrow", daysFromToday(1)));

        mvc.perform(as(a, post(JOBS + "/OVERDUE_REMINDER/run"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"));
        assertThat(mailTo(a)).hasSize(1);
        assertThat(mailTo(a).get(0).textBody()).contains(past).doesNotContain(tomorrow);

        // Nothing to report: no email, and the run says so.
        Account empty = register();
        mvc.perform(as(empty, post(JOBS + "/OVERDUE_REMINDER/run"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOTHING_TO_REPORT"))
                .andExpect(jsonPath("$.handoffs.length()").value(0));
        assertThat(mailTo(empty)).isEmpty();
    }

    @Test
    void theMissingItemReminderLeavesOutHandoffsThatWereForceClosed() throws Exception {
        Account a = register();
        long[] open = activeWithMissing(a, "Still missing");
        long[] closed = activeWithMissing(a, "Written off");
        confirmMissingAndForceClose(a, closed[0]);
        String openCode = codeOf(a, open[0]);
        String closedCode = codeOf(a, closed[0]);

        mvc.perform(as(a, post(JOBS + "/MISSING_ITEM_REMINDER/run"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"));
        List<EmailMessage> sent = mailTo(a);
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).textBody()).contains(openCode).doesNotContain(closedCode);

        // Once that one is written off too, there is nothing left to remind about.
        confirmMissingAndForceClose(a, open[0]);
        mvc.perform(as(a, post(JOBS + "/MISSING_ITEM_REMINDER/run"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOTHING_TO_REPORT"));
        assertThat(mailTo(a)).hasSize(1);
    }

    @Test
    void theWeeklySummaryReportsThatCustomersOwnFigures() throws Exception {
        Account a = register();
        Account b = register();
        activeHandoff(a, "One", null);
        activeHandoff(a, "Two", null);
        activeHandoff(b, "Only", null);

        mvc.perform(as(a, post(JOBS + "/WEEKLY_SUMMARY/run"))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SENT"));
        mvc.perform(as(b, post(JOBS + "/WEEKLY_SUMMARY/run"))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SENT"));

        assertThat(mailTo(a)).hasSize(1);
        assertThat(mailTo(a).get(0).textBody()).contains("Handoffs created: 2").contains("Items given: 20");
        assertThat(mailTo(b)).hasSize(1);
        assertThat(mailTo(b).get(0).textBody()).contains("Handoffs created: 1").contains("Items given: 10");

        Account quiet = register();
        mvc.perform(as(quiet, post(JOBS + "/WEEKLY_SUMMARY/run"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NOTHING_TO_REPORT"));
        assertThat(mailTo(quiet)).isEmpty();
    }

    // ------------------------------------------------------------------ the exact rules, proved against fixed dates

    /** A handoff taken as far as {@code stage}: DRAFT, AWAITING, CANCELLED, ACTIVE, PARTIAL, RETURNED or CLOSED. */
    private String handoffAt(Account owner, String stage, String dueAt) throws Exception {
        String title = "Stage " + stage;
        if (stage.equals("DRAFT") || stage.equals("AWAITING") || stage.equals("CANCELLED")) {
            String body = """
                    {"title":"%s","senderName":"Sender","recipientName":"Recipient","recipientEmail":"recipient@example.test",
                     %s "items":[{"name":"Chairs","quantity":10,"unit":"pcs"}]}"""
                    .formatted(title, dueAt == null ? "" : "\"dueAt\":\"" + dueAt + "\",");
            long id = ((Number) JsonPath.read(mvc.perform(as(owner, post("/api/v1/handoffs").contentType(MediaType.APPLICATION_JSON)
                    .content(body))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id")).longValue();
            if (!stage.equals("DRAFT")) {
                mvc.perform(as(owner, post("/api/v1/handoffs/" + id + "/submit"))).andExpect(status().isOk());
            }
            if (stage.equals("CANCELLED")) {
                mvc.perform(as(owner, post("/api/v1/handoffs/" + id + "/cancel"))).andExpect(status().isOk());
            }
            return codeOf(owner, id);
        }
        long id = activeHandoff(owner, title, dueAt);
        int itemId = JsonPath.read(json(owner, "/api/v1/handoffs/" + id), "$.items[0].id");
        int back = stage.equals("PARTIAL") ? 4 : stage.equals("RETURNED") || stage.equals("CLOSED") ? 10 : 0;
        if (back > 0) {
            mvc.perform(as(owner, post("/api/v1/handoffs/" + id + "/returns").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"lines\":[{\"itemId\":" + itemId + ",\"quantity\":" + back + ",\"condition\":\"GOOD\"}]}")))
                    .andExpect(status().isCreated());
        }
        if (stage.equals("CLOSED")) {
            mvc.perform(as(owner, post("/api/v1/handoffs/" + id + "/close"))).andExpect(status().isOk());
        }
        return codeOf(owner, id);
    }

    private static Instant utc(int month, int day, int hour, int minute) {
        return java.time.ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, ZoneOffset.UTC).toInstant();
    }

    private List<String> codes(List<HandoffActivityService.ReminderLine> lines) {
        return lines.stream().map(HandoffActivityService.ReminderLine::reference).toList();
    }

    @Test
    void onTheSecondOfOctoberTheReturnReminderCoversTheThirdAndFourthOnly() throws Exception {
        Account a = register();
        Instant now = utc(10, 2, 8, 0);   // "today" is 2 October
        String sep30 = codeOf(a, activeHandoff(a, "Sep 30", utc(9, 30, 12, 0).toString()));
        String oct1 = codeOf(a, activeHandoff(a, "Oct 1", utc(10, 1, 12, 0).toString()));
        String oct2 = codeOf(a, activeHandoff(a, "Oct 2", utc(10, 2, 20, 0).toString()));
        String oct3 = codeOf(a, activeHandoff(a, "Oct 3", utc(10, 3, 12, 0).toString()));
        String oct4 = codeOf(a, activeHandoff(a, "Oct 4", utc(10, 4, 23, 30).toString()));
        String oct5 = codeOf(a, activeHandoff(a, "Oct 5", utc(10, 5, 0, 30).toString()));

        assertThat(codes(activity.returnsDueSoon(a.id(), now, ZoneOffset.UTC, 2))).containsExactly(oct3, oct4)
                .doesNotContain(sep30, oct1, oct2, oct5);

        // "Calendar day" means the customer's own: in India the evening of the 2nd UTC is already the 3rd, and the
        // 4th at 23:30 UTC is already the 5th.
        assertThat(codes(activity.returnsDueSoon(a.id(), now, java.time.ZoneId.of("Asia/Kolkata"), 2)))
                .containsExactly(oct2, oct3);
    }

    @Test
    void onlyHandoffsWithItemsStillOutAreInTheReturnAndOverdueReminders() throws Exception {
        Account a = register();
        String tomorrow = utc(10, 3, 12, 0).toString();
        String longAgo = Instant.now().minus(5, ChronoUnit.DAYS).toString();
        for (String due : new String[]{tomorrow, longAgo}) {
            for (String stage : new String[]{"DRAFT", "AWAITING", "CANCELLED", "RETURNED", "CLOSED"}) {
                handoffAt(a, stage, due);   // none of these may ever be reminded about
            }
        }
        String activeSoon = handoffAt(a, "ACTIVE", tomorrow);
        String partialSoon = handoffAt(a, "PARTIAL", tomorrow);
        String activeLate = handoffAt(a, "ACTIVE", longAgo);
        String partialLate = handoffAt(a, "PARTIAL", longAgo);

        assertThat(codes(activity.returnsDueSoon(a.id(), utc(10, 2, 8, 0), ZoneOffset.UTC, 2))).containsExactly(activeSoon, partialSoon);
        assertThat(codes(activity.overdue(a.id()))).containsExactly(activeLate, partialLate);
        // A handoff that is overdue is never in the return reminder, and one that is not yet due is never overdue.
        assertThat(codes(activity.overdue(a.id()))).doesNotContain(activeSoon, partialSoon);
        assertThat(codes(activity.returnsDueSoon(a.id(), Instant.now(), ZoneOffset.UTC, 2))).doesNotContain(activeLate, partialLate);
    }

    @Test
    void theWeeklySummaryCountsThatCustomersOwnHandoffsItemsAndReferencesCorrectly() throws Exception {
        Account a = register();
        Account b = register();
        String h1 = handoffAt(a, "PARTIAL", null);        // 10 given, 4 back, still open
        String h2 = handoffAt(a, "CLOSED", null);         // 10 given, 10 back, closed
        long missingId = activeHandoff(a, "Stage MISSING", null);   // 10 given, 4 reported missing, still open
        int itemId = JsonPath.read(json(a, "/api/v1/handoffs/" + missingId), "$.items[0].id");
        mvc.perform(as(a, post("/api/v1/handoffs/" + missingId + "/returns").contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":[{\"itemId\":" + itemId + ",\"quantity\":4,\"condition\":\"MISSING\"}]}"))).andExpect(status().isCreated());
        String h3 = codeOf(a, missingId);
        String h4 = handoffAt(a, "DRAFT", null);          // created only: nothing given, not open
        handoffAt(b, "PARTIAL", null);                    // another customer's: none of it may count

        mvc.perform(as(a, post(JOBS + "/WEEKLY_SUMMARY/run"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"));

        assertThat(mailTo(a)).hasSize(1);
        String body = mailTo(a).get(0).textBody();
        assertThat(body).contains("Handoffs created: 4 (" + h1 + ", " + h2 + ", " + h3 + ", " + h4 + ")");
        assertThat(body).contains("Handoffs closed: 1 (" + h2 + ")");
        assertThat(body).contains("Handoffs still open: 2 (" + h1 + ", " + h3 + ")");
        assertThat(body).contains("Items given: 30");          // the draft gave nothing
        assertThat(body).contains("Items returned: 14");       // 4 + 10; the 4 reported missing are not "returned"
        assertThat(body).contains("Items missing now: 4");
        assertThat(mailTo(b)).isEmpty();
    }

    // ------------------------------------------------------------------ the email

    @Test
    void aRunSendsOneConsolidatedEmailWithABoldHeading() throws Exception {
        Account a = register();
        String one = codeOf(a, activeHandoff(a, "First", daysFromToday(1)));
        String two = codeOf(a, activeHandoff(a, "Second", daysFromToday(2)));
        String three = codeOf(a, activeHandoff(a, "Third", daysFromToday(1)));

        mvc.perform(as(a, post(JOBS + "/RETURN_REMINDER/run"))).andExpect(status().isOk());

        assertThat(mailTo(a)).hasSize(1);   // three handoffs, ONE email
        EmailMessage mail = mailTo(a).get(0);
        assertThat(mail.subject()).contains("Return Reminder").contains(a.accountCode());
        assertThat(mail.textBody()).contains("RETURN REMINDER").contains(one, two, three);
        assertThat(mail.htmlBody()).contains("<h1").contains("font-weight:bold").contains(">Return Reminder</h1>")
                .contains(one, two, three);
    }

    @Test
    void anEmailThatCannotBeSentIsRecordedAsFailedAndTheScheduleIsKept() throws Exception {
        Account a = register();
        activeHandoff(a, "Due tomorrow", daysFromToday(1));
        enable(a, JobType.RETURN_REMINDER);
        Object next = JsonPath.read(job(a, JobType.RETURN_REMINDER).toString(), "$[0].nextRunAt");
        emailSender.setFailing(true);
        try {
            mvc.perform(as(a, post(JOBS + "/RETURN_REMINDER/run"))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("FAILED"));
        } finally {
            emailSender.setFailing(false);
        }
        String after = job(a, JobType.RETURN_REMINDER).toString();
        assertThat((Object) JsonPath.read(after, "$[0].lastStatus")).isEqualTo("FAILED");
        assertThat((Object) JsonPath.read(after, "$[0].lastDetail")).isNotNull();
        assertThat((Object) JsonPath.read(after, "$[0].nextRunAt")).isEqualTo(next);
        assertThat(mailTo(a)).isEmpty();
    }

    // ------------------------------------------------------------------ the scheduler

    @Test
    void theSchedulerRunsOnlyEnabledJobsThatAreDueAndOnlyOnce() throws Exception {
        Account a = register();
        Account b = register();
        activeHandoff(a, "A overdue", Instant.now().minus(3, ChronoUnit.DAYS).toString());
        activeHandoff(b, "B overdue", Instant.now().minus(3, ChronoUnit.DAYS).toString());
        activeHandoff(a, "A tomorrow", daysFromToday(1));   // would be in A's return reminder, which is off
        enable(a, JobType.OVERDUE_REMINDER);
        enable(b, JobType.OVERDUE_REMINDER);
        Instant due = Instant.parse(JsonPath.read(job(a, JobType.OVERDUE_REMINDER).toString(), "$[0].nextRunAt"));

        // Before its time nothing of A's runs.
        jobService.runDue(due.minusSeconds(60));
        assertThat(mailTo(a)).isEmpty();

        jobService.runDue(due.plusSeconds(5));
        assertThat(mailTo(a)).hasSize(1);   // the overdue job only; the switched-off return reminder did not run
        assertThat(mailTo(a).get(0).textBody()).contains("A overdue").doesNotContain("B overdue");
        assertThat(mailTo(b)).hasSize(1);
        assertThat(mailTo(b).get(0).textBody()).contains("B overdue").doesNotContain("A overdue");

        // The same moment again claims nothing: it already ran, and its next run is a day on.
        jobService.runDue(due.plusSeconds(5));
        assertThat(mailTo(a)).hasSize(1);
        assertThat(mailTo(b)).hasSize(1);
        String after = job(a, JobType.OVERDUE_REMINDER).toString();
        assertThat(Instant.parse(JsonPath.read(after, "$[0].nextRunAt"))).isAfter(due.plusSeconds(5));
        assertThat((Object) JsonPath.read(after, "$[0].lastStatus")).isEqualTo("SENT");
        assertThat((Object) JsonPath.read(job(a, JobType.RETURN_REMINDER).toString(), "$[0].lastRunAt")).isNull();
    }

    @Test
    void aDisabledAccountGetsNoScheduledEmail() throws Exception {
        Account a = register();
        activeHandoff(a, "Overdue", Instant.now().minus(3, ChronoUnit.DAYS).toString());
        enable(a, JobType.OVERDUE_REMINDER);
        Instant due = Instant.parse(JsonPath.read(job(a, JobType.OVERDUE_REMINDER).toString(), "$[0].nextRunAt"));
        jdbc.update("update app_user set enabled = false where id = ?", a.id());

        jobService.runDue(due.plusSeconds(5));

        assertThat(mailTo(a)).isEmpty();
    }
}
