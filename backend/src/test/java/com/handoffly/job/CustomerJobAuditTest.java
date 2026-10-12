package com.handoffly.job;

import com.handoffly.testsupport.ApiTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Who changed a customer's job settings, and who ran a job: every successful change is recorded with the authenticated customer
 * (stable id, the name they had then, the kind of actor), the history of runs says who started each run, and nobody sees another
 * customer's entries.
 */
class CustomerJobAuditTest extends ApiTestBase {

    private static final String JOBS = "/api/v1/account/jobs";
    private static final JobType TYPE = JobType.RETURN_REMINDER;

    @Autowired
    private CustomerJobService jobService;
    /** The real repository, watched, so one test can make saving a change fail the way a database failure would. */
    @MockitoSpyBean
    private CustomerJobChangeRepository changeRepository;

    // ------------------------------------------------------------------ helpers

    private String json(Account who, String path) throws Exception {
        return mvc.perform(as(who, get(path))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String changes(Account who) throws Exception {
        return json(who, JOBS + "/changes");
    }

    private String history(Account who) throws Exception {
        return json(who, JOBS + "/history");
    }

    private void putSchedule(Account who, JobType type, String cron, String zone, int expected) throws Exception {
        mvc.perform(as(who, put(JOBS + "/" + type + "/schedule").contentType(MediaType.APPLICATION_JSON)
                .content("{\"cronExpression\":\"" + cron + "\",\"timezone\":\"" + zone + "\"}"))).andExpect(status().is(expected));
    }

    private void setEnabled(Account who, JobType type, boolean enabled, int expected) throws Exception {
        mvc.perform(as(who, put(JOBS + "/" + type + "/enabled").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":" + enabled + "}"))).andExpect(status().is(expected));
    }

    /** One field of one of the customer's jobs, as the Jobs page gets it. */
    private Object jobField(Account who, JobType type, String field) throws Exception {
        net.minidev.json.JSONArray found = JsonPath.read(json(who, JOBS), "$[?(@.type=='" + type + "')]");
        return ((Map<?, ?>) found.get(0)).get(field);
    }

    private String scheduleOf(Account who, JobType type) throws Exception {
        return jobField(who, type, "cronExpression") + " · " + jobField(who, type, "timezone");
    }

    private int changeRows(Account who) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM customer_job_change WHERE account_id = ?", Integer.class, who.id());
    }

    // ------------------------------------------------------------------ configuration changes

    @Test
    void aScheduleChangeIsRecordedWithTheCustomerWhoMadeItAndWhatItWas() throws Exception {
        Account a = register("Priya Sharma", null);
        String before = scheduleOf(a, TYPE);

        putSchedule(a, TYPE, "0 30 8 * * MON-FRI", "Asia/Kolkata", 200);

        String page = changes(a);
        assertThat((Integer) JsonPath.read(page, "$.totalElements")).isEqualTo(1);
        assertThat((String) JsonPath.read(page, "$.content[0].action")).isEqualTo("SCHEDULE_CHANGED");
        assertThat((String) JsonPath.read(page, "$.content[0].type")).isEqualTo("RETURN_REMINDER");
        assertThat((String) JsonPath.read(page, "$.content[0].title")).isEqualTo("Return Reminder");
        assertThat((String) JsonPath.read(page, "$.content[0].previousValue")).isEqualTo(before);
        assertThat((String) JsonPath.read(page, "$.content[0].newValue")).isEqualTo("0 30 8 * * MON-FRI · Asia/Kolkata");
        assertThat((String) JsonPath.read(page, "$.content[0].performedBy")).isEqualTo("Priya Sharma");
        assertThat((String) JsonPath.read(page, "$.content[0].changedAt")).isNotBlank();

        // Stored with the stable ids and the kind of actor, not just the name.
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM customer_job_change WHERE account_id = ?", a.id());
        assertThat(((Number) row.get("ACTOR_USER_ID")).longValue()).isEqualTo(a.id());
        assertThat(row.get("ACTOR_NAME")).isEqualTo("Priya Sharma");
        assertThat(row.get("ACTOR_TYPE")).isEqualTo("USER");
        assertThat(row.get("JOB_TYPE")).isEqualTo("RETURN_REMINDER");
    }

    @Test
    void changingOnlyTheTimezoneIsAScheduleChangeToo() throws Exception {
        Account a = register();
        String cron = (String) jobField(a, TYPE, "cronExpression");

        putSchedule(a, TYPE, cron, "Europe/London", 200);

        assertThat((String) JsonPath.read(changes(a), "$.content[0].newValue")).isEqualTo(cron + " · Europe/London");
    }

    @Test
    void pausingAndResumingAreRecordedNewestFirst() throws Exception {
        Account a = register("Priya Sharma", null);

        setEnabled(a, TYPE, true, 200);    // a new customer's jobs start paused: this resumes it
        setEnabled(a, TYPE, false, 200);

        String page = changes(a);
        assertThat((Integer) JsonPath.read(page, "$.totalElements")).isEqualTo(2);
        assertThat((String) JsonPath.read(page, "$.content[0].action")).isEqualTo("PAUSED");
        assertThat((String) JsonPath.read(page, "$.content[0].previousValue")).isEqualTo("Active");
        assertThat((String) JsonPath.read(page, "$.content[0].newValue")).isEqualTo("Paused");
        assertThat((String) JsonPath.read(page, "$.content[1].action")).isEqualTo("RESUMED");
        assertThat((String) JsonPath.read(page, "$.content[1].previousValue")).isEqualTo("Paused");
        assertThat((String) JsonPath.read(page, "$.content[1].newValue")).isEqualTo("Active");
        assertThat((String) JsonPath.read(page, "$.content[1].performedBy")).isEqualTo("Priya Sharma");
    }

    @Test
    void savingWhatIsAlreadySetIsNotAChange() throws Exception {
        Account a = register();
        String cron = (String) jobField(a, TYPE, "cronExpression");
        String zone = (String) jobField(a, TYPE, "timezone");

        putSchedule(a, TYPE, cron, zone, 200);     // the schedule it already has
        setEnabled(a, TYPE, false, 200);           // already paused
        setEnabled(a, TYPE, true, 200);            // a real change
        setEnabled(a, TYPE, true, 200);            // already active
        putSchedule(a, TYPE, "0 0 10 * * *", "UTC", 200);
        putSchedule(a, TYPE, "0 0 10 * * *", "UTC", 200);   // the same again

        assertThat(changeRows(a)).isEqualTo(2);   // only the resume and the one schedule change
    }

    @Test
    void listingJobsAndSettingThemUpRecordNothing() throws Exception {
        Account a = register();

        json(a, JOBS);
        json(a, JOBS);

        assertThat(changeRows(a)).isZero();
    }

    @Test
    void aRefusedScheduleIsNotRecordedAndChangesNothing() throws Exception {
        Account a = register();
        String before = scheduleOf(a, TYPE);

        putSchedule(a, TYPE, "not a cron", "UTC", 400);
        putSchedule(a, TYPE, "0 0 9 * * *", "Not/AZone", 400);
        putSchedule(a, TYPE, "* * * * * *", "UTC", 400);   // more often than once an hour

        assertThat(changeRows(a)).isZero();
        assertThat(scheduleOf(a, TYPE)).isEqualTo(before);
    }

    @Test
    void aChangeThatCannotBeRecordedDoesNotHappenAtAll() throws Exception {
        Account a = register();
        json(a, JOBS);   // set the jobs up first
        String before = scheduleOf(a, TYPE);
        doThrow(new DataIntegrityViolationException("simulated database failure")).when(changeRepository).save(any());
        try {
            setEnabled(a, TYPE, true, 500);
            putSchedule(a, TYPE, "0 0 10 * * *", "UTC", 500);
        } finally {
            reset(changeRepository);
        }

        assertThat(jobField(a, TYPE, "enabled")).isEqualTo(false);   // still paused
        assertThat(scheduleOf(a, TYPE)).isEqualTo(before);                                                        // still as it was
        assertThat(changeRows(a)).isZero();
    }

    @Test
    void theNameRecordedIsTheOneTheCustomerHadThen() throws Exception {
        Account a = register("Priya Sharma", null);
        setEnabled(a, TYPE, true, 200);

        jdbc.update("UPDATE app_user SET display_name = ? WHERE id = ?", "Priya Verma", a.id());
        setEnabled(a, TYPE, false, 200);

        String page = changes(a);
        assertThat((String) JsonPath.read(page, "$.content[0].performedBy")).isEqualTo("Priya Verma");   // the later change
        assertThat((String) JsonPath.read(page, "$.content[1].performedBy")).isEqualTo("Priya Sharma");   // not rewritten
    }

    @Test
    void anIdentitySuppliedByTheClientIsNeverUsed() throws Exception {
        Account a = register("Priya Sharma", null);
        Account other = register("Someone Else", null);

        mvc.perform(as(a, put(JOBS + "/" + TYPE + "/schedule?userId=" + other.id() + "&actorName=Mallory")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cronExpression\":\"0 0 10 * * *\",\"timezone\":\"UTC\",\"actorName\":\"Mallory\",\"actorUserId\":" + other.id()
                                + ",\"performedBy\":\"Mallory\"}")))
                .andExpect(status().isOk());

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM customer_job_change WHERE job_type = 'RETURN_REMINDER' AND account_id = ?", a.id());
        assertThat(((Number) row.get("ACTOR_USER_ID")).longValue()).isEqualTo(a.id());
        assertThat(row.get("ACTOR_NAME")).isEqualTo("Priya Sharma");
        assertThat(changeRows(other)).isZero();
    }

    // ------------------------------------------------------------------ who ran a job

    @Test
    void aRunPressedByHandSaysWhoPressedIt() throws Exception {
        Account a = register("Priya Sharma", null);

        mvc.perform(as(a, post(JOBS + "/" + TYPE + "/run"))).andExpect(status().isOk());
        mvc.perform(as(a, post(JOBS + "/run-all"))).andExpect(status().isOk());

        String page = history(a);
        assertThat((Integer) JsonPath.read(page, "$.totalElements")).isEqualTo(6);   // one by hand, then all five
        List<String> names = JsonPath.read(page, "$.content[*].performedBy");
        assertThat(names).containsOnly("Priya Sharma");
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT actor_user_id, actor_name, actor_type FROM customer_job_run WHERE user_id = ?", a.id());
        assertThat(rows).hasSize(6).allSatisfy(r -> {
            assertThat(((Number) r.get("ACTOR_USER_ID")).longValue()).isEqualTo(a.id());
            assertThat(r.get("ACTOR_NAME")).isEqualTo("Priya Sharma");
            assertThat(r.get("ACTOR_TYPE")).isEqualTo("USER");
        });
    }

    @Test
    void aRunTheSchedulerStartedIsSystemAndHasNoPerson() throws Exception {
        Account a = register("Priya Sharma", null);
        setEnabled(a, TYPE, true, 200);
        Instant due = Instant.parse((String) jobField(a, TYPE, "nextRunAt"));

        jobService.runDue(due.plusSeconds(5));

        String page = history(a);
        assertThat((Integer) JsonPath.read(page, "$.totalElements")).isEqualTo(1);
        assertThat((String) JsonPath.read(page, "$.content[0].trigger")).isEqualTo("SCHEDULED");
        assertThat((String) JsonPath.read(page, "$.content[0].performedBy")).isEqualTo("System");
        Map<String, Object> row = jdbc.queryForMap("SELECT actor_user_id, actor_name, actor_type FROM customer_job_run WHERE user_id = ?", a.id());
        assertThat(row.get("ACTOR_USER_ID")).isNull();
        assertThat(row.get("ACTOR_NAME")).isNull();
        assertThat(row.get("ACTOR_TYPE")).isNull();
    }

    @Test
    void aRunFromBeforeThePersonWasKeptIsNeverGivenAName() throws Exception {
        Account a = register("Priya Sharma", null);
        // Three runs as they were stored before this was kept: pressed by hand, started by the scheduler, and copied from the old "latest run".
        for (String trigger : new String[]{"RUN_NOW", "RUN_ALL_NOW", "SCHEDULED", null}) {
            jdbc.update("INSERT INTO customer_job_run (version, created_at, updated_at, user_id, job_type, trigger_kind, run_at, status) "
                            + "VALUES (0, ?, ?, ?, 'RETURN_REMINDER', ?, ?, 'NOTHING_TO_REPORT')",
                    Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), a.id(), trigger, Timestamp.from(Instant.now()));
        }

        List<String> byTrigger = JsonPath.read(history(a), "$.content[*].trigger");
        List<String> performedBy = JsonPath.read(history(a), "$.content[*].performedBy");
        // newest first: the copied one (no trigger), the scheduled one, the two pressed by hand
        assertThat(byTrigger).containsExactly(null, "SCHEDULED", "RUN_ALL_NOW", "RUN_NOW");
        assertThat(performedBy).containsExactly("Not recorded", "System", "Not recorded", "Not recorded");
    }

    // ------------------------------------------------------------------ account boundaries

    @Test
    void aCustomerSeesOnlyTheirOwnChangesAndRuns() throws Exception {
        Account a = register("Priya Sharma", null);
        Account b = register("Rahul Mehta", null);
        setEnabled(a, TYPE, true, 200);
        putSchedule(b, JobType.WEEKLY_SUMMARY, "0 0 7 * * MON", "UTC", 200);
        mvc.perform(as(a, post(JOBS + "/" + TYPE + "/run"))).andExpect(status().isOk());
        mvc.perform(as(b, post(JOBS + "/" + TYPE + "/run"))).andExpect(status().isOk());

        String mine = changes(a);
        assertThat((Integer) JsonPath.read(mine, "$.totalElements")).isEqualTo(1);
        assertThat((String) JsonPath.read(mine, "$.content[0].performedBy")).isEqualTo("Priya Sharma");
        String theirs = changes(b);
        assertThat((Integer) JsonPath.read(theirs, "$.totalElements")).isEqualTo(1);
        assertThat((String) JsonPath.read(theirs, "$.content[0].performedBy")).isEqualTo("Rahul Mehta");
        assertThat((String) JsonPath.read(theirs, "$.content[0].type")).isEqualTo("WEEKLY_SUMMARY");

        assertThat((List<String>) JsonPath.read(history(a), "$.content[*].performedBy")).containsExactly("Priya Sharma");
        assertThat((List<String>) JsonPath.read(history(b), "$.content[*].performedBy")).containsExactly("Rahul Mehta");

        // Nothing in the request can name someone else's account: the owner is always the signed-in customer.
        mvc.perform(as(a, get(JOBS + "/changes?userId=" + b.id() + "&accountId=" + b.id()))).andExpect(status().isOk());
        assertThat((String) JsonPath.read(json(a, JOBS + "/changes?userId=" + b.id() + "&accountId=" + b.id()), "$.content[0].performedBy"))
                .isEqualTo("Priya Sharma");
    }

    @Test
    void theChangesNeedSigningIn() throws Exception {
        mvc.perform(get(JOBS + "/changes")).andExpect(status().isUnauthorized());
    }

    @Test
    void changesArePagedNewestFirstAndAPageCannotBeAskedForInBulk() throws Exception {
        Account a = register();
        for (int i = 0; i < 5; i++) {
            setEnabled(a, TYPE, i % 2 == 0, 200);
        }

        String firstPage = json(a, JOBS + "/changes?size=2");
        assertThat((Integer) JsonPath.read(firstPage, "$.totalElements")).isEqualTo(5);
        assertThat((Integer) JsonPath.read(firstPage, "$.totalPages")).isEqualTo(3);
        assertThat((List<?>) JsonPath.read(firstPage, "$.content")).hasSize(2);
        assertThat((String) JsonPath.read(firstPage, "$.content[0].action")).isEqualTo("RESUMED");   // the fifth change, the newest
        assertThat((Integer) JsonPath.read(json(a, JOBS + "/changes?size=100000"), "$.size")).isEqualTo(100);
    }
}
