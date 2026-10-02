package com.handoffly.job;

import com.handoffly.notification.EmailMessage;
import com.handoffly.support.staff.SupportRole;
import com.handoffly.testsupport.ApiTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The support team's subscription-expiry reminder job: who may run it, which customers it reminds, that nobody is
 * reminded twice about the same period, and that it never touches a plan.
 */
class SubscriptionExpiryJobTest extends ApiTestBase {

    private static final String JOB = "/api/v1/support/jobs/subscription-expiry";
    private static final String SCHEDULE = "{\"cronExpression\":\"0 0 9 * * *\",\"timezone\":\"UTC\",\"windowDays\":7}";

    @Autowired
    private SubscriptionExpiryJobService service;

    // ------------------------------------------------------------------ helpers

    private void endsIn(Account customer, Duration fromNow) {
        jdbc.update("update app_user set plan_valid_until = ? where id = ?", Timestamp.from(Instant.now().plus(fromNow)), customer.id());
    }

    private String run(StaffAccount staff) throws Exception {
        return mvc.perform(as(staff, post(JOB + "/run"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private void enable(StaffAccount staff) throws Exception {
        mvc.perform(as(staff, put(JOB + "/enabled").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}")))
                .andExpect(status().isOk());
    }

    private List<EmailMessage> mailTo(Account customer) {
        return emailSender.messagesTo(customer.email());
    }

    // ------------------------------------------------------------------ who may use it

    @Test
    void onlyAdministratorsAndManagersMayUseTheJob() throws Exception {
        StaffAccount agent = registerStaff(SupportRole.TICKET_AGENT);
        StaffAccount manager = registerStaff(SupportRole.MANAGER);
        StaffAccount admin = registerStaff(SupportRole.ADMIN);
        Account customer = register();

        // Not signed in, or signed in as a customer: not a support identity at all.
        mvc.perform(get(JOB)).andExpect(status().isUnauthorized());
        mvc.perform(as(customer, get(JOB))).andExpect(status().isUnauthorized());
        mvc.perform(as(customer, post(JOB + "/run"))).andExpect(status().isUnauthorized());

        // A ticket agent is a support identity without the permission: every endpoint is forbidden.
        mvc.perform(as(agent, get(JOB))).andExpect(status().isForbidden());
        mvc.perform(as(agent, put(JOB + "/schedule").contentType(MediaType.APPLICATION_JSON).content(SCHEDULE))).andExpect(status().isForbidden());
        mvc.perform(as(agent, put(JOB + "/enabled").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}"))).andExpect(status().isForbidden());
        mvc.perform(as(agent, post(JOB + "/run"))).andExpect(status().isForbidden());

        mvc.perform(as(manager, get(JOB))).andExpect(status().isOk());
        mvc.perform(as(admin, get(JOB))).andExpect(status().isOk());
        mvc.perform(as(manager, post(JOB + "/run"))).andExpect(status().isOk());
        mvc.perform(as(admin, post(JOB + "/run"))).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ its settings

    @Test
    void theJobStartsSwitchedOffAndItsScheduleIsValidated() throws Exception {
        StaffAccount admin = registerStaff(SupportRole.ADMIN);
        mvc.perform(as(admin, get(JOB))).andExpect(status().isOk())
                .andExpect(jsonPath("$.windowDays").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.cronExpression").isNotEmpty());
        // Whatever an earlier test left, saving a bad schedule or window is refused and changes nothing.
        mvc.perform(as(admin, put(JOB + "/schedule").contentType(MediaType.APPLICATION_JSON).content(SCHEDULE))).andExpect(status().isOk());
        for (String bad : new String[]{
                "{\"cronExpression\":\"not a cron\",\"timezone\":\"UTC\",\"windowDays\":7}",
                "{\"cronExpression\":\"0 */5 * * * *\",\"timezone\":\"UTC\",\"windowDays\":7}",
                "{\"cronExpression\":\"0 0 9 * * *\",\"timezone\":\"Mars/Olympus\",\"windowDays\":7}",
                "{\"cronExpression\":\"0 0 9 * * *\",\"timezone\":\"UTC\",\"windowDays\":0}",
                "{\"cronExpression\":\"0 0 9 * * *\",\"timezone\":\"UTC\",\"windowDays\":91}",
                "{\"cronExpression\":\"0 0 9 * * *\",\"timezone\":\"UTC\"}"}) {
            mvc.perform(as(admin, put(JOB + "/schedule").contentType(MediaType.APPLICATION_JSON).content(bad)))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(as(admin, get(JOB))).andExpect(jsonPath("$.cronExpression").value("0 0 9 * * *"))
                .andExpect(jsonPath("$.windowDays").value(7));
    }

    @Test
    void pausingAndResumingKeepTheScheduleAndOnlyResumingSetsANextRun() throws Exception {
        StaffAccount manager = registerStaff(SupportRole.MANAGER);
        mvc.perform(as(manager, put(JOB + "/schedule").contentType(MediaType.APPLICATION_JSON)
                .content("{\"cronExpression\":\"0 30 6 * * MON-FRI\",\"timezone\":\"Asia/Kolkata\",\"windowDays\":10}"))).andExpect(status().isOk());
        mvc.perform(as(manager, put(JOB + "/enabled").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":true}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.nextRunAt").isNotEmpty()).andExpect(jsonPath("$.upcomingRuns[2]").isNotEmpty());
        mvc.perform(as(manager, put(JOB + "/enabled").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.nextRunAt").doesNotExist())
                .andExpect(jsonPath("$.cronExpression").value("0 30 6 * * MON-FRI"))
                .andExpect(jsonPath("$.timezone").value("Asia/Kolkata"))
                .andExpect(jsonPath("$.windowDays").value(10));
        mvc.perform(as(manager, put(JOB + "/schedule").contentType(MediaType.APPLICATION_JSON).content(SCHEDULE))).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ who is reminded

    @Test
    void onlyCustomersWhoseSubscriptionEndsWithinTheWindowAreRemindedAndOnlyOnce() throws Exception {
        StaffAccount admin = registerStaff(SupportRole.ADMIN);
        mvc.perform(as(admin, put(JOB + "/schedule").contentType(MediaType.APPLICATION_JSON).content(SCHEDULE))).andExpect(status().isOk());

        Account soon = register(com.handoffly.user.SubscriptionPlan.QUARTERLY);
        Account lastDay = register(com.handoffly.user.SubscriptionPlan.YEARLY);
        Account later = register(com.handoffly.user.SubscriptionPlan.YEARLY);
        Account ended = register(com.handoffly.user.SubscriptionPlan.QUARTERLY);
        Account noEnd = register(com.handoffly.user.SubscriptionPlan.HALF_YEARLY);
        Account switchedOff = register(com.handoffly.user.SubscriptionPlan.QUARTERLY);
        endsIn(soon, Duration.ofDays(3));
        endsIn(lastDay, Duration.ofDays(7).minusMinutes(5));
        endsIn(later, Duration.ofDays(20));
        endsIn(ended, Duration.ofDays(-1));
        endsIn(switchedOff, Duration.ofDays(2));
        jdbc.update("update app_user set enabled = false where id = ?", switchedOff.id());

        String result = run(admin);
        List<String> reminded = JsonPath.read(result, "$.accountCodes");
        assertThat(reminded).contains(soon.accountCode(), lastDay.accountCode())
                .doesNotContain(later.accountCode(), ended.accountCode(), noEnd.accountCode(), switchedOff.accountCode());
        assertThat((Integer) JsonPath.read(result, "$.reminded")).isEqualTo(reminded.size());
        assertThat((String) JsonPath.read(result, "$.status")).isEqualTo("SENT");

        // One email each, from the one shared template, with a bold heading and the plan's last day.
        assertThat(mailTo(soon)).hasSize(1);
        EmailMessage mail = mailTo(soon).get(0);
        assertThat(mail.subject()).contains("Subscription Expiring Soon").contains(soon.accountCode());
        assertThat(mail.textBody()).contains("SUBSCRIPTION EXPIRING SOON").contains("Quarterly");
        assertThat(mail.htmlBody()).contains("font-weight:bold").contains(">Subscription Expiring Soon</h1>");
        assertThat(mailTo(lastDay)).hasSize(1);
        for (Account other : List.of(later, ended, noEnd, switchedOff)) {
            assertThat(mailTo(other)).isEmpty();
        }

        // Running it again reminds nobody about the same period.
        String again = run(admin);
        assertThat((List<String>) JsonPath.read(again, "$.accountCodes")).doesNotContain(soon.accountCode(), lastDay.accountCode());
        assertThat(mailTo(soon)).hasSize(1);
        assertThat(mailTo(lastDay)).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from subscription_expiry_reminder where user_id = ?", Integer.class, soon.id()))
                .isEqualTo(1);
    }

    @Test
    void renewingMovesTheEndDateSoTheNextPeriodIsRemindedAbout() throws Exception {
        StaffAccount admin = registerStaff(SupportRole.ADMIN);
        mvc.perform(as(admin, put(JOB + "/schedule").contentType(MediaType.APPLICATION_JSON).content(SCHEDULE))).andExpect(status().isOk());
        Account customer = register(com.handoffly.user.SubscriptionPlan.QUARTERLY);
        endsIn(customer, Duration.ofDays(3));
        run(admin);
        assertThat(mailTo(customer)).hasSize(1);

        endsIn(customer, Duration.ofDays(5));   // renewed, then about to end again: a different end date
        run(admin);
        assertThat(mailTo(customer)).hasSize(2);
        run(admin);
        assertThat(mailTo(customer)).hasSize(2);
    }

    @Test
    void theWindowDecidesHowFarAheadCustomersAreReminded() throws Exception {
        StaffAccount admin = registerStaff(SupportRole.ADMIN);
        Account customer = register(com.handoffly.user.SubscriptionPlan.QUARTERLY);
        endsIn(customer, Duration.ofDays(12));

        mvc.perform(as(admin, put(JOB + "/schedule").contentType(MediaType.APPLICATION_JSON).content(SCHEDULE))).andExpect(status().isOk());
        run(admin);
        assertThat(mailTo(customer)).isEmpty();   // 12 days is outside a 7-day window

        mvc.perform(as(admin, put(JOB + "/schedule").contentType(MediaType.APPLICATION_JSON)
                .content("{\"cronExpression\":\"0 0 9 * * *\",\"timezone\":\"UTC\",\"windowDays\":14}"))).andExpect(status().isOk());
        run(admin);
        assertThat(mailTo(customer)).hasSize(1);
        mvc.perform(as(admin, put(JOB + "/schedule").contentType(MediaType.APPLICATION_JSON).content(SCHEDULE))).andExpect(status().isOk());
    }

    @Test
    void theJobNeverChangesAPlanOrItsEndDate() throws Exception {
        StaffAccount admin = registerStaff(SupportRole.ADMIN);
        mvc.perform(as(admin, put(JOB + "/schedule").contentType(MediaType.APPLICATION_JSON).content(SCHEDULE))).andExpect(status().isOk());
        Account customer = register(com.handoffly.user.SubscriptionPlan.YEARLY);
        endsIn(customer, Duration.ofDays(2));
        Integer historyBefore = jdbc.queryForObject("select count(*) from subscription_history where user_id = ?", Integer.class, customer.id());
        Object endBefore = jdbc.queryForObject("select plan_valid_until from app_user where id = ?", Timestamp.class, customer.id());

        run(admin);

        assertThat(mailTo(customer)).hasSize(1);
        assertThat(jdbc.queryForObject("select subscription_plan from app_user where id = ?", String.class, customer.id())).isEqualTo("YEARLY");
        assertThat((Object) jdbc.queryForObject("select plan_valid_until from app_user where id = ?", Timestamp.class, customer.id())).isEqualTo(endBefore);
        assertThat(jdbc.queryForObject("select count(*) from subscription_history where user_id = ?", Integer.class, customer.id()))
                .isEqualTo(historyBefore);
    }

    @Test
    void anEmailThatFailsIsReportedAndTriedAgainAtTheNextRun() throws Exception {
        StaffAccount admin = registerStaff(SupportRole.ADMIN);
        mvc.perform(as(admin, put(JOB + "/schedule").contentType(MediaType.APPLICATION_JSON).content(SCHEDULE))).andExpect(status().isOk());
        Account customer = register(com.handoffly.user.SubscriptionPlan.QUARTERLY);
        endsIn(customer, Duration.ofDays(3));

        emailSender.setFailing(true);
        try {
            mvc.perform(as(admin, post(JOB + "/run"))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("FAILED")).andExpect(jsonPath("$.failed").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                    .andExpect(jsonPath("$.accountCodes").value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem(customer.accountCode()))));
        } finally {
            emailSender.setFailing(false);
        }
        assertThat(mailTo(customer)).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from subscription_expiry_reminder where user_id = ?", Integer.class, customer.id()))
                .isZero();

        run(admin);   // the mailbox is back: now it goes out, once
        assertThat(mailTo(customer)).hasSize(1);
        mvc.perform(as(admin, get(JOB))).andExpect(jsonPath("$.lastStatus").value("SENT"));
    }

    // ------------------------------------------------------------------ running by hand and on schedule

    @Test
    void runningNowDoesNotMoveTheScheduleOrSwitchTheJobOn() throws Exception {
        StaffAccount admin = registerStaff(SupportRole.ADMIN);
        mvc.perform(as(admin, put(JOB + "/schedule").contentType(MediaType.APPLICATION_JSON).content(SCHEDULE))).andExpect(status().isOk());
        mvc.perform(as(admin, put(JOB + "/enabled").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))).andExpect(status().isOk());
        run(admin);
        mvc.perform(as(admin, get(JOB))).andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.nextRunAt").doesNotExist())
                .andExpect(jsonPath("$.lastRunAt").isNotEmpty());

        enable(admin);
        String next = JsonPath.read(mvc.perform(as(admin, get(JOB))).andReturn().getResponse().getContentAsString(), "$.nextRunAt");
        run(admin);
        mvc.perform(as(admin, get(JOB))).andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.nextRunAt").value(next));
        mvc.perform(as(admin, put(JOB + "/enabled").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))).andExpect(status().isOk());
    }

    @Test
    void theSchedulerRunsTheJobOnlyWhenItIsOnAndDueAndOnlyOnce() throws Exception {
        StaffAccount admin = registerStaff(SupportRole.ADMIN);
        mvc.perform(as(admin, put(JOB + "/schedule").contentType(MediaType.APPLICATION_JSON).content(SCHEDULE))).andExpect(status().isOk());
        Account customer = register(com.handoffly.user.SubscriptionPlan.QUARTERLY);
        endsIn(customer, Duration.ofDays(3));

        // Switched off: it never runs by itself, however late it gets.
        mvc.perform(as(admin, put(JOB + "/enabled").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))).andExpect(status().isOk());
        assertThat(service.runDue(Instant.now().plus(Duration.ofDays(3)))).isFalse();
        assertThat(mailTo(customer)).isEmpty();

        enable(admin);
        Instant due = Instant.parse(JsonPath.read(mvc.perform(as(admin, get(JOB))).andReturn().getResponse().getContentAsString(), "$.nextRunAt"));
        assertThat(service.runDue(due.minusSeconds(60))).isFalse();   // not yet
        assertThat(mailTo(customer)).isEmpty();

        assertThat(service.runDue(due.plusSeconds(5))).isTrue();
        assertThat(mailTo(customer)).hasSize(1);
        assertThat(service.runDue(due.plusSeconds(5))).isFalse();   // claimed: its next run is a day on
        assertThat(mailTo(customer)).hasSize(1);
        String after = mvc.perform(as(admin, get(JOB))).andReturn().getResponse().getContentAsString();
        assertThat(Instant.parse(JsonPath.read(after, "$.nextRunAt"))).isAfter(due.plusSeconds(5));
        assertThat((String) JsonPath.read(after, "$.lastStatus")).isEqualTo("SENT");

        mvc.perform(as(admin, put(JOB + "/enabled").contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))).andExpect(status().isOk());
    }
}
