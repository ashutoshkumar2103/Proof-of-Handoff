package com.handoffly.user;

import com.handoffly.support.staff.SupportRole;
import com.handoffly.testsupport.ApiTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A subscription has a plan, a start and an end, and a status that follows from them; every change is appended
 * to a history. A lapsed subscription stops granting the plan's support extras everywhere and stops the customer
 * starting NEW handoffs, sending a draft and using HandoffCheck, but never touches the handoffs they already have, nor
 * the plan on record.
 */
class SubscriptionLifecycleTest extends ApiTestBase {

    private static String inDays(int days) {
        return LocalDate.now(ZoneOffset.UTC).plusDays(days).toString();
    }

    private ResultActions changePlan(StaffAccount staff, Account customer, String from, String to, String validUntil, String reason)
            throws Exception {
        String body = "{" + (from == null ? "" : "\"fromPlan\":\"" + from + "\",") + "\"toPlan\":\"" + to + "\""
                + (validUntil == null ? "" : ",\"validUntil\":\"" + validUntil + "\"")
                + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}";
        return mvc.perform(as(staff, put("/api/v1/support/customers/" + customer.accountCode() + "/plan")
                .contentType(MediaType.APPLICATION_JSON).content(body)));
    }

    private ResultActions profile(StaffAccount staff, Account customer) throws Exception {
        return mvc.perform(as(staff, get("/api/v1/support/customers/" + customer.accountCode())));
    }

    private ResultActions me(Account customer) throws Exception {
        return mvc.perform(as(customer, get("/api/v1/auth/me")));
    }

    private void lapse(Account customer) {
        jdbc.update("UPDATE app_user SET plan_valid_until = TIMESTAMPADD(DAY, -1, CURRENT_TIMESTAMP) WHERE id = ?", customer.id());
    }

    private ResultActions pay(String plan) throws Exception {
        return mvc.perform(post("/api/v1/public/payments/demo").contentType(MediaType.APPLICATION_JSON)
                .content("{\"plan\":\"" + plan + "\",\"cardNumber\":\"4242 4242 4242 4242\",\"expiry\":\"12/99\",\"cvc\":\"123\"}"));
    }

    private void payAndApply(Account customer, String plan) throws Exception {
        String token = JsonPath.read(pay(plan).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.token");
        mvc.perform(as(customer, post("/api/v1/payments/redeem").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"))).andExpect(status().isOk());
    }

    private Instant instantAt(Account customer, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM app_user WHERE id = ?", java.sql.Timestamp.class, customer.id()).toInstant();
    }

    // ------------------------------------------------------------------ the lifecycle

    @Test
    void anAccountOnAPlanWithNoEndDateIsActive() throws Exception {
        Account customer = register();   // activated as test setup, the way every account was before end dates existed
        me(customer).andExpect(status().isOk())
                .andExpect(jsonPath("$.subscription.plan").value("MONTHLY"))
                .andExpect(jsonPath("$.subscription.status").value("ACTIVE"))
                .andExpect(jsonPath("$.subscription.startedAt").isNotEmpty())
                .andExpect(jsonPath("$.subscription.validUntil").doesNotExist());
    }

    @Test
    void supportMovesACustomerToAPlanForAPeriodAndItIsRecorded() throws Exception {
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        Account customer = register();
        String lastDay = inDays(30);

        changePlan(staff, customer, "MONTHLY", "YEARLY", lastDay, "Paid by bank transfer").andExpect(status().isOk())
                .andExpect(jsonPath("$.subscription.plan").value("YEARLY"))
                .andExpect(jsonPath("$.subscription.status").value("ACTIVE"))
                .andExpect(jsonPath("$.subscription.validUntil").value(LocalDate.parse(lastDay).plusDays(1) + "T00:00:00Z"))
                .andExpect(jsonPath("$.entitlements.call").value(true))
                .andExpect(jsonPath("$.subscriptionHistory.length()").value(1))
                .andExpect(jsonPath("$.subscriptionHistory[0].previousPlan").value("MONTHLY"))
                .andExpect(jsonPath("$.subscriptionHistory[0].newPlan").value("YEARLY"))
                .andExpect(jsonPath("$.subscriptionHistory[0].source").value("STAFF"))
                .andExpect(jsonPath("$.subscriptionHistory[0].staffCode").value(staff.staffCode()))
                .andExpect(jsonPath("$.subscriptionHistory[0].reason").value("Paid by bank transfer"))
                .andExpect(jsonPath("$.subscriptionHistory[0].startsAt").isNotEmpty())
                .andExpect(jsonPath("$.recentChanges[0].type").value("PLAN_CHANGED"));
        me(customer).andExpect(jsonPath("$.subscription.plan").value("YEARLY"))
                .andExpect(jsonPath("$.subscription.status").value("ACTIVE")).andExpect(jsonPath("$.support.call").value(true));

        // No last day named: the plan lasts its own duration from now, so a normal activation needs no date at all.
        changePlan(staff, customer, "YEARLY", "QUARTERLY", null, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.subscription.status").value("ACTIVE"))
                .andExpect(jsonPath("$.subscriptionHistory.length()").value(2))
                .andExpect(jsonPath("$.subscriptionHistory[0].newPlan").value("QUARTERLY"));   // newest first
        Instant start = instantAt(customer, "plan_started_at");
        assertThat(instantAt(customer, "plan_valid_until")).isEqualTo(SubscriptionPlan.QUARTERLY.validUntil(start));
        assertThat(jdbc.queryForObject("SELECT valid_until FROM subscription_history WHERE user_id = ? ORDER BY id DESC LIMIT 1",
                java.sql.Timestamp.class, customer.id()).toInstant()).isEqualTo(SubscriptionPlan.QUARTERLY.validUntil(start));
    }

    @Test
    void theLastDayCannotBeInThePastOrMalformed() throws Exception {
        StaffAccount staff = registerStaff(SupportRole.ADMIN);
        Account customer = register();
        changePlan(staff, customer, "MONTHLY", "YEARLY", inDays(-1), null).andExpect(status().isBadRequest());
        changePlan(staff, customer, "MONTHLY", "YEARLY", "not-a-date", null).andExpect(status().isBadRequest());
        changePlan(staff, customer, "MONTHLY", "YEARLY", inDays(0), null).andExpect(status().isOk());   // today is fine
        me(customer).andExpect(jsonPath("$.subscription.plan").value("YEARLY"));
    }

    @Test
    void extendingThePlanYouHaveChangesOnlyTheEndKeepsTheStartAndIsAuditedSeparately() throws Exception {
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        Account customer = register();
        changePlan(staff, customer, "MONTHLY", "HALF_YEARLY", inDays(10), null).andExpect(status().isOk());
        Instant startedAt = instantAt(customer, "plan_started_at");

        changePlan(staff, customer, "HALF_YEARLY", "HALF_YEARLY", inDays(10), null).andExpect(status().isConflict());   // nothing changes
        changePlan(staff, customer, "HALF_YEARLY", "HALF_YEARLY", inDays(200), "Renewed").andExpect(status().isOk())
                .andExpect(jsonPath("$.subscription.plan").value("HALF_YEARLY"))
                .andExpect(jsonPath("$.subscriptionHistory.length()").value(2))
                .andExpect(jsonPath("$.subscriptionHistory[0].previousPlan").value("HALF_YEARLY"))
                .andExpect(jsonPath("$.subscriptionHistory[0].newPlan").value("HALF_YEARLY"))
                .andExpect(jsonPath("$.recentChanges[0].type").value("SUBSCRIPTION_PERIOD_CHANGED"))
                .andExpect(jsonPath("$.recentChanges[0].previousValue").value(inDays(10)))
                .andExpect(jsonPath("$.recentChanges[0].newValue").value(inDays(200)));
        assertThat(instantAt(customer, "plan_started_at")).isEqualTo(startedAt);
    }

    // ------------------------------------------------------------------ a lapsed subscription

    @Test
    void aLapsedSubscriptionIsInactiveAndStopsGrantingTheSupportExtrasEverywhere() throws Exception {
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        Account customer = register(SubscriptionPlan.YEARLY);
        String ticket = createTicket(customer, "Opened while active");
        mvc.perform(as(customer, get("/api/v1/tickets"))).andExpect(status().isOk());
        assertThat(JsonPath.<String>read(mvc.perform(as(staff, get("/api/v1/support/tickets/" + ticket))).andReturn().getResponse()
                .getContentAsString(), "$.ticket.priority")).isEqualTo("HIGHEST");

        lapse(customer);

        // The customer's own view: the plan is still theirs, but nothing it includes applies until it is renewed.
        me(customer).andExpect(jsonPath("$.plan").value("YEARLY"))
                .andExpect(jsonPath("$.subscription.plan").value("YEARLY"))
                .andExpect(jsonPath("$.subscription.status").value("INACTIVE"))
                .andExpect(jsonPath("$.support.contactSupport").value(false))
                .andExpect(jsonPath("$.support.ticket").value(false))
                .andExpect(jsonPath("$.support.call").value(false))
                .andExpect(jsonPath("$.support.supportPhone").doesNotExist())
                .andExpect(jsonPath("$.support.priority").value("NORMAL"));
        // ...and the backend refuses regardless of what any screen shows.
        mvc.perform(as(customer, get("/api/v1/tickets"))).andExpect(status().isForbidden());
        mvc.perform(as(customer, get("/api/v1/tickets/" + ticket))).andExpect(status().isForbidden());
        mvc.perform(as(customer, post("/api/v1/tickets").contentType(MediaType.APPLICATION_JSON)
                .content("{\"subject\":\"s\",\"category\":\"GENERAL\",\"description\":\"d\"}"))).andExpect(status().isForbidden());
        mvc.perform(as(customer, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/support-messages")
                .param("subject", "s").param("message", "m"))).andExpect(status().isForbidden());

        // Support sees the lapse, the entitlements it no longer grants, and the ticket at normal priority.
        profile(staff, customer).andExpect(jsonPath("$.subscription.status").value("INACTIVE"))
                .andExpect(jsonPath("$.entitlements.ticket").value(false))
                .andExpect(jsonPath("$.entitlements.priority").value("NORMAL"))
                .andExpect(jsonPath("$.customer.subscriptionStatus").value("INACTIVE"));
        mvc.perform(as(staff, get("/api/v1/support/tickets/" + ticket))).andExpect(jsonPath("$.ticket.priority").value("NORMAL"))
                .andExpect(jsonPath("$.ticket.plan").value("YEARLY"));
        // Starting a NEW handoff needs an active subscription (see the tests below); what exists stays usable.
        tryCreate(customer, ONE_ITEM).andExpect(status().isForbidden());
        mvc.perform(as(customer, get("/api/v1/handoffs"))).andExpect(status().isOk());
    }

    @Test
    void aLapsedCustomerDoesNotCountAsAPriorityCustomerOrMatchThePriorityFilter() throws Exception {
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        Account active = register(SubscriptionPlan.YEARLY);
        Account lapsed = register(SubscriptionPlan.YEARLY);
        String activeTicket = createTicket(active, "Active");
        String lapsedTicket = createTicket(lapsed, "Lapsed");
        lapse(lapsed);

        String listed = mvc.perform(as(staff, get("/api/v1/support/tickets").param("priorityOnly", "true"))).andReturn().getResponse().getContentAsString();
        List<String> codes = JsonPath.read(listed, "$.content[*].ticketCode");
        assertThat(codes).contains(activeTicket).doesNotContain(lapsedTicket);
        // Renewed, they count again.
        jdbc.update("UPDATE app_user SET plan_valid_until = NULL WHERE id = ?", lapsed.id());
        String again = mvc.perform(as(staff, get("/api/v1/support/tickets").param("priorityOnly", "true"))).andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(again, "$.content[*].ticketCode")).contains(activeTicket, lapsedTicket);
    }

    @Test
    void renewingBySupportBringsTheExtrasBack() throws Exception {
        StaffAccount staff = registerStaff(SupportRole.ADMIN);
        Account customer = register(SubscriptionPlan.HALF_YEARLY);
        lapse(customer);
        mvc.perform(as(customer, get("/api/v1/tickets"))).andExpect(status().isForbidden());

        changePlan(staff, customer, "HALF_YEARLY", "HALF_YEARLY", inDays(180), "Renewal paid").andExpect(status().isOk())
                .andExpect(jsonPath("$.subscription.status").value("ACTIVE"));
        mvc.perform(as(customer, get("/api/v1/tickets"))).andExpect(status().isOk());
        me(customer).andExpect(jsonPath("$.support.ticket").value(true));
    }

    // ------------------------------------------------------------------ starting a handoff needs an active subscription

    private static final String ENDED_MESSAGE =
            "Your subscription has ended. Please subscribe to any of our plans to continue without any interruption.";

    /** POST /handoffs, the one route every way of starting a handoff ends in (New, Duplicate, import all save here). */
    private ResultActions tryCreate(Account customer, String itemsJson) throws Exception {
        String body = "{\"title\":\"Fresh start\",\"senderName\":\"Sender\",\"recipientName\":\"R\",\"recipientEmail\":\"r@example.test\","
                + "\"items\":" + itemsJson + "}";
        return mvc.perform(as(customer, post("/api/v1/handoffs").contentType(MediaType.APPLICATION_JSON).content(body)));
    }

    private static final String ONE_ITEM = "[{\"name\":\"Chairs\",\"quantity\":10,\"unit\":\"pcs\"}]";

    private ResultActions refusedAsEnded(ResultActions result) throws Exception {
        return result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("subscription_expired"))
                .andExpect(jsonPath("$.detail").value(ENDED_MESSAGE));
    }

    private int handoffCount(Account customer) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM handoff WHERE owner_user_id = ?", Integer.class, customer.id());
    }

    /** A new draft made through the API (the customer must be active); returns its id. */
    private long newDraft(Account customer) throws Exception {
        return ((Number) JsonPath.read(createHandoff(customer), "$.id")).longValue();
    }

    /** Sending a draft to its recipient: the only way a draft becomes an outgoing handoff. */
    private ResultActions trySend(Account customer, long handoffId) throws Exception {
        return mvc.perform(as(customer, post("/api/v1/handoffs/" + handoffId + "/submit")));
    }

    @Test
    void anActiveSubscriptionCanStartAndSendAHandoffEveryWay() throws Exception {
        Account noEnd = register();                                   // a plan with no end date is active
        Account endsLater = register(SubscriptionPlan.YEARLY);
        jdbc.update("UPDATE app_user SET plan_valid_until = TIMESTAMPADD(HOUR, 1, CURRENT_TIMESTAMP) WHERE id = ?", endsLater.id());

        for (Account customer : List.of(noEnd, endsLater)) {
            // New handoff, and a plain draft through the API.
            tryCreate(customer, ONE_ITEM).andExpect(status().isCreated());
            long original = ((Number) JsonPath.read(createHandoff(customer), "$.id")).longValue();
            // Duplicate: the template is offered, and the copy saves through the ordinary create.
            mvc.perform(as(customer, get("/api/v1/handoffs/" + original + "/template"))).andExpect(status().isOk());
            tryCreate(customer, "[{\"name\":\"Laptop\",\"quantity\":1}]").andExpect(status().isCreated());
            // Import: the rows are read for review, and saving them is the same create.
            mvc.perform(as(customer, multipart("/api/v1/handoff-check/import-items")
                    .file(new MockMultipartFile("file", "items.csv", "text/csv", "Item,Qty\nTables,4\n".getBytes()))))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.lines.length()").value(1));
            tryCreate(customer, "[{\"name\":\"Tables\",\"quantity\":4}]").andExpect(status().isCreated());
            assertThat(handoffCount(customer)).isEqualTo(4);
            // A draft made earlier goes out to its recipient.
            trySend(customer, original).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("AWAITING_RECIPIENT"));
        }
    }

    @Test
    void anExpiredSubscriptionCannotStartAHandoffByAnyRouteAndNothingIsCreated() throws Exception {
        Account expired = register();
        Account other = register();   // someone else's active subscription is not affected
        long original = ((Number) JsonPath.read(createHandoff(expired), "$.id")).longValue();   // made while active
        lapse(expired);
        int before = handoffCount(expired);
        long sequence = jdbc.queryForObject("SELECT handoff_sequence FROM app_user WHERE id = ?", Long.class, expired.id());

        // New handoff / a plain draft, called straight at the API.
        refusedAsEnded(tryCreate(expired, ONE_ITEM));
        refusedAsEnded(tryCreate(expired, "[]"));
        // Duplicate: refused when the copy is offered, and again if the create is called directly.
        refusedAsEnded(mvc.perform(as(expired, get("/api/v1/handoffs/" + original + "/template"))));
        refusedAsEnded(tryCreate(expired, "[{\"name\":\"Copy of old\",\"quantity\":1}]"));
        // Import-assisted: the import (a HandoffCheck feature) is refused, and so is saving anything it would have read.
        refusedAsEnded(mvc.perform(as(expired, multipart("/api/v1/handoff-check/import-items")
                .file(new MockMultipartFile("file", "items.csv", "text/csv", "Item,Qty\nTables,4\n".getBytes())))));
        refusedAsEnded(tryCreate(expired, "[{\"name\":\"Tables\",\"quantity\":4}]"));

        assertThat(handoffCount(expired)).isEqualTo(before);   // no handoff, and no reference number was used up
        assertThat(jdbc.queryForObject("SELECT handoff_sequence FROM app_user WHERE id = ?", Long.class, expired.id())).isEqualTo(sequence);
        tryCreate(other, ONE_ITEM).andExpect(status().isCreated());
        // The same refusal for a signed-out caller is still a plain 401, not the subscription message.
        mvc.perform(post("/api/v1/handoffs").contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
    }

    @Test
    void anExpiredSubscriptionKeepsEveryHandoffAndEverythingYouCanDoWithThem() throws Exception {
        Account customer = register();
        long active = activeHandoff(customer, "Already out", null);
        int itemId = JsonPath.read(mvc.perform(as(customer, get("/api/v1/handoffs/" + active))).andReturn().getResponse().getContentAsString(),
                "$.items[0].id");
        mvc.perform(as(customer, post("/api/v1/handoffs/" + active + "/returns").contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":[{\"itemId\":" + itemId + ",\"quantity\":4,\"condition\":\"GOOD\"}]}"))).andExpect(status().isCreated());
        long draft = ((Number) JsonPath.read(createHandoff(customer), "$.id")).longValue();
        long waiting = ((Number) JsonPath.read(createHandoff(customer), "$.id")).longValue();
        mvc.perform(as(customer, post("/api/v1/handoffs/" + waiting + "/submit"))).andExpect(status().isOk());
        String recipientToken = emailSender.extractLastToken();

        lapse(customer);

        // Looking at what exists.
        mvc.perform(as(customer, get("/api/v1/handoffs/" + active))).andExpect(status().isOk());
        mvc.perform(as(customer, get("/api/v1/handoffs"))).andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(3));
        mvc.perform(as(customer, get("/api/v1/handoffs/dashboard"))).andExpect(status().isOk());
        mvc.perform(as(customer, get("/api/v1/handoffs/" + active + "/events"))).andExpect(status().isOk());
        mvc.perform(as(customer, get("/api/v1/handoffs/" + active + "/attachments"))).andExpect(status().isOk());
        mvc.perform(as(customer, get("/api/v1/handoffs/" + active + "/pdf"))).andExpect(status().isOk());
        // Resending the link of a handoff that is already out is not starting anything, so it is not gated.
        mvc.perform(as(customer, post("/api/v1/handoffs/" + waiting + "/resend-link"))).andExpect(status().isOk());
        recipientToken = emailSender.extractLastToken();   // the resend issued a fresh link
        // The recipient's own link still works, whatever happened to the sender's subscription.
        mvc.perform(get("/api/v1/r/" + recipientToken)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/r/" + recipientToken + "/accept").contentType(MediaType.APPLICATION_JSON)
                .content("{\"acknowledgementName\":\"Recipient\"}")).andExpect(status().isOk());
        // Working an existing handoff through to the end: partial and final returns, then closing.
        mvc.perform(as(customer, post("/api/v1/handoffs/" + active + "/returns").contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":[{\"itemId\":" + itemId + ",\"quantity\":6,\"condition\":\"GOOD\"}]}"))).andExpect(status().isCreated());
        mvc.perform(as(customer, post("/api/v1/handoffs/" + active + "/close"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
        // An existing draft is still there to look at (sending it is the gated step: see the next test).
        mvc.perform(as(customer, get("/api/v1/handoffs/" + draft))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));
        // Recording a return by hand is a different matter from HandoffCheck's file import, and was done above.
        // Their account, plan on record and subscription display all still work.
        me(customer).andExpect(status().isOk()).andExpect(jsonPath("$.subscription.status").value("INACTIVE"));
    }

    // ------------------------------------------------------------------ HandoffCheck needs an active subscription too

    private static final String COMPARE_BODY =
            "{\"referenceLines\":[{\"name\":\"A\",\"quantity\":1}],\"targetLines\":[{\"name\":\"A\",\"quantity\":1}]}";

    private static MockMultipartFile csv(String part, String fileName) {
        return new MockMultipartFile(part, fileName, "text/csv", "Item,Qty\nChairs,10\n".getBytes());
    }

    /** Calls every HandoffCheck endpoint once as this customer, in a fixed order (each call runs as it is listed). */
    private List<ResultActions> everyHandoffCheckEndpoint(Account customer, long handoffId) throws Exception {
        return List.of(
                mvc.perform(as(customer, post("/api/v1/handoff-check").contentType(MediaType.APPLICATION_JSON).content(COMPARE_BODY))),
                mvc.perform(as(customer, multipart("/api/v1/handoff-check/extract").file(csv("file", "a.csv")))),
                mvc.perform(as(customer, post("/api/v1/handoff-check/export").param("format", "CSV")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comparison\":" + COMPARE_BODY + "}"))),
                mvc.perform(as(customer, multipart("/api/v1/handoff-check/compare-files")
                        .file(csv("fileA", "a.csv")).file(csv("fileB", "b.csv")))),
                mvc.perform(as(customer, multipart("/api/v1/handoff-check/import-items").file(csv("file", "items.csv")))),
                mvc.perform(as(customer, multipart("/api/v1/handoff-check/return-import").file(csv("file", "returned.csv"))
                        .param("handoffId", String.valueOf(handoffId)))));
    }

    @Test
    void anExpiredSubscriptionClosesHandoffCheckInEveryWayAndRenewingOpensItAgain() throws Exception {
        StaffAccount manager = registerStaff(SupportRole.MANAGER);
        Account customer = register(SubscriptionPlan.YEARLY);   // a plan that includes HandoffCheck, so only the expiry is in question
        long out = activeHandoff(customer, "Out with a recipient", null);   // something a returns file can be matched to

        // Active: every HandoffCheck endpoint works.
        for (ResultActions result : everyHandoffCheckEndpoint(customer, out)) {
            result.andExpect(status().isOk());
        }

        lapse(customer);

        // Expired: every one of them is refused with the subscription answer...
        for (ResultActions result : everyHandoffCheckEndpoint(customer, out)) {
            refusedAsEnded(result);
        }
        // ...before the request is even read: a body that would be a 400 for an active customer gets the subscription answer.
        mvc.perform(as(customer, post("/api/v1/handoff-check").contentType(MediaType.APPLICATION_JSON).content("{}")))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("subscription_expired"));
        refusedAsEnded(mvc.perform(as(customer, post("/api/v1/handoff-check/export").param("format", "PDF")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))));
        // Signed out is still a plain 401, not the subscription message.
        mvc.perform(post("/api/v1/handoff-check").contentType(MediaType.APPLICATION_JSON).content(COMPARE_BODY))
                .andExpect(status().isUnauthorized());
        // The handoff itself, and recording its return by hand, are not HandoffCheck and keep working.
        int itemId = JsonPath.read(mvc.perform(as(customer, get("/api/v1/handoffs/" + out))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.items[0].id");
        mvc.perform(as(customer, post("/api/v1/handoffs/" + out + "/returns").contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":[{\"itemId\":" + itemId + ",\"quantity\":3,\"condition\":\"GOOD\"}]}"))).andExpect(status().isCreated());

        // Renewed: HandoffCheck is back, with no other step.
        changePlan(manager, customer, "YEARLY", "YEARLY", inDays(30), "Renewal paid").andExpect(status().isOk());
        for (ResultActions result : everyHandoffCheckEndpoint(customer, out)) {
            result.andExpect(status().isOk());
        }
    }

    // ------------------------------------------------------------------ HandoffCheck is a feature of some plans

    /** How many of everyHandoffCheckEndpoint's calls are the HandoffCheck tool; the last two are New handoff's and Returns' file imports. */
    private static final int TOOL_ENDPOINTS = 4;

    private ResultActions refusedByPlan(ResultActions result) throws Exception {
        return result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("plan_required"))
                .andExpect(jsonPath("$.detail").value("HandoffCheck is available on Half-Yearly and Yearly plans."));
    }

    private List<ResultActions> theTool(Account customer, long handoffId) throws Exception {
        return everyHandoffCheckEndpoint(customer, handoffId).subList(0, TOOL_ENDPOINTS);
    }

    @Test
    void theHandoffCheckToolIsAFeatureOfHalfYearlyAndYearlyWhileTheFileImportsOfNewHandoffAndReturnsStayOnEveryPlan() throws Exception {
        record Case(SubscriptionPlan plan, boolean handoffCheck, boolean tickets, boolean call) {}
        for (Case c : List.of(new Case(SubscriptionPlan.MONTHLY, false, false, false),
                new Case(SubscriptionPlan.QUARTERLY, false, false, false),
                new Case(SubscriptionPlan.HALF_YEARLY, true, true, false),
                new Case(SubscriptionPlan.YEARLY, true, true, true))) {
            Account customer = register(c.plan());
            long out = activeHandoff(customer, "Out with a recipient", null);

            List<ResultActions> calls = everyHandoffCheckEndpoint(customer, out);
            for (int i = 0; i < calls.size(); i++) {
                if (i < TOOL_ENDPOINTS && !c.handoffCheck()) {
                    refusedByPlan(calls.get(i));                 // compare, read a file, export, compare two files
                } else {
                    calls.get(i).andExpect(status().isOk());     // the tool on a plan with it; the two imports on every plan
                }
            }
            // The account says so, as its own flag: the support entitlements are what they always were for the plan.
            me(customer).andExpect(jsonPath("$.handoffCheck").value(c.handoffCheck()))
                    .andExpect(jsonPath("$.support.ticket").value(c.tickets()))
                    .andExpect(jsonPath("$.support.call").value(c.call()));
        }
        // The message names exactly the plans that include it.
        assertThat(UserService.HANDOFFCHECK_PLAN_MESSAGE).contains("Half-Yearly", "Yearly").doesNotContain("Monthly", "Quarterly");
    }

    @Test
    void changingThePlanChangesHandoffCheckAccessAtOnceWithoutSigningInAgain() throws Exception {
        StaffAccount manager = registerStaff(SupportRole.MANAGER);
        StaffAccount admin = registerStaff(SupportRole.ADMIN);
        Account customer = register();                              // Monthly; this one sign-in is used throughout
        long out = activeHandoff(customer, "Out with a recipient", null);
        for (ResultActions result : theTool(customer, out)) refusedByPlan(result);

        changePlan(manager, customer, "MONTHLY", "HALF_YEARLY", inDays(180), "Upgraded").andExpect(status().isOk());
        me(customer).andExpect(jsonPath("$.handoffCheck").value(true));
        for (ResultActions result : theTool(customer, out)) result.andExpect(status().isOk());

        changePlan(admin, customer, "HALF_YEARLY", "MONTHLY", null, "Downgraded").andExpect(status().isOk());
        me(customer).andExpect(jsonPath("$.handoffCheck").value(false));
        for (ResultActions result : theTool(customer, out)) refusedByPlan(result);

        Account quarterly = register(SubscriptionPlan.QUARTERLY);
        long outQuarterly = activeHandoff(quarterly, "Out with a recipient", null);
        for (ResultActions result : theTool(quarterly, outQuarterly)) refusedByPlan(result);
        changePlan(manager, quarterly, "QUARTERLY", "YEARLY", inDays(365), "Upgraded").andExpect(status().isOk());
        for (ResultActions result : theTool(quarterly, outQuarterly)) result.andExpect(status().isOk());
    }

    @Test
    void anEndedSubscriptionIsAnsweredAsEndedWhateverThePlanAndTheAccountStopsOfferingHandoffCheck() throws Exception {
        for (SubscriptionPlan plan : List.of(SubscriptionPlan.MONTHLY, SubscriptionPlan.YEARLY)) {
            Account customer = register(plan);
            long out = activeHandoff(customer, "Out with a recipient", null);
            lapse(customer);
            for (ResultActions result : everyHandoffCheckEndpoint(customer, out)) refusedAsEnded(result);   // ended, not "not in your plan"
            me(customer).andExpect(jsonPath("$.plan").value(plan.name()))
                    .andExpect(jsonPath("$.subscription.status").value("INACTIVE"))
                    .andExpect(jsonPath("$.handoffCheck").value(false));
        }
    }

    @Test
    void aDraftMadeWhileActiveCanBeSeenButNotSentOnceTheSubscriptionHasEnded() throws Exception {
        Account customer = register();
        long draft = newDraft(customer);                                               // made while active
        long empty = ((Number) JsonPath.read(mvc.perform(as(customer, post("/api/v1/handoffs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"No items yet\",\"senderName\":\"S\",\"recipientName\":\"R\",\"recipientEmail\":\"r@example.test\"}")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id")).longValue();
        long sent = newDraft(customer);
        trySend(customer, sent).andExpect(status().isOk());                            // already out before the subscription ended
        int mailBefore = emailSender.getMessages().size();

        lapse(customer);

        // The draft is still theirs to look at...
        mvc.perform(as(customer, get("/api/v1/handoffs/" + draft))).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));
        // ...but it cannot be turned into an outgoing handoff, however it is asked for.
        refusedAsEnded(trySend(customer, draft));
        refusedAsEnded(trySend(customer, empty));   // the subscription is what is wrong, whatever else the draft lacks
        // Only the draft-to-outgoing step is gated: a handoff that is already out keeps its ordinary answer.
        trySend(customer, sent).andExpect(status().isConflict());

        // Nothing happened to the draft: still a draft, no link, no email, no "submitted" event, no outgoing date.
        mvc.perform(as(customer, get("/api/v1/handoffs/" + draft))).andExpect(jsonPath("$.status").value("DRAFT"));
        assertThat(emailSender.getMessages()).hasSize(mailBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recipient_link WHERE handoff_id = ?", Integer.class, draft)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE handoff_id = ? AND event_type = 'OUTGOING_SUBMITTED'",
                Integer.class, draft)).isZero();
        assertThat(jdbc.queryForObject("SELECT outgoing_at FROM handoff WHERE id = ?", java.sql.Timestamp.class, draft)).isNull();
    }

    @Test
    void expiryIsJudgedAtTheMomentOfEachRequestSoNothingRememberedFromEarlierCanLetOneThrough() throws Exception {
        Account customer = register(SubscriptionPlan.YEARLY);   // signed in once: this token is used throughout, as a page left open would
        Instant endsAt = Instant.now().plusSeconds(3);
        jdbc.update("UPDATE app_user SET plan_valid_until = ? WHERE id = ?", java.sql.Timestamp.from(endsAt), customer.id());

        // Still running: the account says so, and a handoff can be started, a draft sent and HandoffCheck used.
        me(customer).andExpect(jsonPath("$.subscription.status").value("ACTIVE"));
        long toSend = newDraft(customer);
        tryCreate(customer, ONE_ITEM).andExpect(status().isCreated());
        mvc.perform(as(customer, post("/api/v1/handoff-check").contentType(MediaType.APPLICATION_JSON).content(COMPARE_BODY)))
                .andExpect(status().isOk());
        assertThat(Instant.now()).as("the test must finish its 'before' steps while the plan is still running").isBefore(endsAt);

        // Nobody changes anything: time simply passes. Whatever a screen last saw, the next request is judged on its own.
        Thread.sleep(Duration.between(Instant.now(), endsAt).toMillis() + 300);
        int before = handoffCount(customer);
        me(customer).andExpect(jsonPath("$.subscription.status").value("INACTIVE"));
        refusedAsEnded(tryCreate(customer, ONE_ITEM));
        refusedAsEnded(trySend(customer, toSend));
        refusedAsEnded(mvc.perform(as(customer, post("/api/v1/handoff-check").contentType(MediaType.APPLICATION_JSON).content(COMPARE_BODY))));

        assertThat(handoffCount(customer)).isEqualTo(before);   // nothing was persisted
        mvc.perform(as(customer, get("/api/v1/handoffs/" + toSend))).andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void renewingBringsBackStartingHandoffsWithoutAnyManualStep() throws Exception {
        StaffAccount manager = registerStaff(SupportRole.MANAGER);
        StaffAccount admin = registerStaff(SupportRole.ADMIN);
        Account customer = register(SubscriptionPlan.QUARTERLY);
        long draft = newDraft(customer);

        lapse(customer);
        refusedAsEnded(tryCreate(customer, ONE_ITEM));
        refusedAsEnded(trySend(customer, draft));
        me(customer).andExpect(jsonPath("$.subscription.status").value("INACTIVE"));
        changePlan(manager, customer, "QUARTERLY", "QUARTERLY", inDays(90), "Renewal paid").andExpect(status().isOk());   // a manager renews
        me(customer).andExpect(jsonPath("$.subscription.status").value("ACTIVE"));   // what a page asking again would see
        tryCreate(customer, ONE_ITEM).andExpect(status().isCreated());
        trySend(customer, draft).andExpect(status().isOk());   // the same draft now goes out

        lapse(customer);
        refusedAsEnded(tryCreate(customer, ONE_ITEM));
        changePlan(admin, customer, "QUARTERLY", "YEARLY", inDays(365), "Upgraded").andExpect(status().isOk());   // an admin changes it
        tryCreate(customer, ONE_ITEM).andExpect(status().isCreated());

        lapse(customer);
        refusedAsEnded(tryCreate(customer, ONE_ITEM));
        payAndApply(customer, "MONTHLY");   // or the customer pays for a plan themselves
        tryCreate(customer, ONE_ITEM).andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------ payments

    @Test
    void aPaymentBuysOneBillingPeriodAndIsRecordedInTheHistory() throws Exception {
        Account customer = register();
        payAndApply(customer, "QUARTERLY");

        me(customer).andExpect(jsonPath("$.subscription.plan").value("QUARTERLY"))
                .andExpect(jsonPath("$.subscription.status").value("ACTIVE"));
        assertThat(instantAt(customer, "plan_valid_until"))
                .isEqualTo(SubscriptionPlan.QUARTERLY.validUntil(instantAt(customer, "plan_started_at")));   // 90 days
        Map<String, Object> row = jdbc.queryForMap("SELECT source, previous_plan, new_plan, staff_id FROM subscription_history WHERE user_id = ?", customer.id());
        assertThat(row.get("source")).isEqualTo("PAYMENT");
        assertThat(row.get("previous_plan")).isEqualTo("MONTHLY");
        assertThat(row.get("new_plan")).isEqualTo("QUARTERLY");
        assertThat(row.get("staff_id")).isNull();
    }

    @Test
    void renewingThePlanYouStillHaveExtendsFromWhereItRunsOutButANewPlanStartsNow() throws Exception {
        Account customer = register();
        payAndApply(customer, "QUARTERLY");
        Instant firstEnd = instantAt(customer, "plan_valid_until");
        Instant firstStart = instantAt(customer, "plan_started_at");

        payAndApply(customer, "QUARTERLY");   // renewal: carries on from the first end
        Instant renewedEnd = instantAt(customer, "plan_valid_until");
        assertThat(renewedEnd).isEqualTo(SubscriptionPlan.QUARTERLY.validUntil(firstEnd));   // 90 more days, from where it ran out
        assertThat(instantAt(customer, "plan_started_at")).isEqualTo(firstStart);

        payAndApply(customer, "YEARLY");      // a different plan: a fresh period from now
        Instant yearlyEnd = instantAt(customer, "plan_valid_until");
        assertThat(yearlyEnd).isEqualTo(SubscriptionPlan.YEARLY.validUntil(instantAt(customer, "plan_started_at")));   // 365 days
        assertThat(instantAt(customer, "plan_started_at")).isAfter(firstStart.minusSeconds(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM subscription_history WHERE user_id = ?", Long.class, customer.id())).isEqualTo(3L);
    }

    @Test
    void renewingAfterItLapsedStartsAFreshPeriodFromNow() throws Exception {
        Account customer = register();
        payAndApply(customer, "HALF_YEARLY");
        lapse(customer);
        payAndApply(customer, "HALF_YEARLY");
        assertThat(instantAt(customer, "plan_valid_until"))
                .isEqualTo(SubscriptionPlan.HALF_YEARLY.validUntil(instantAt(customer, "plan_started_at")));   // six calendar months from now
        me(customer).andExpect(jsonPath("$.subscription.status").value("ACTIVE"));
    }

    @Test
    void aFirstPaymentActivatesAnAccountThatHadNoPlanWithItsStartAndTheCalculatedEndForEveryPlan() throws Exception {
        for (SubscriptionPlan plan : SubscriptionPlan.values()) {
            Account customer = registerWithoutPlan();   // signed up from the Login page, nothing paid yet
            refusedAsNoPlan(tryCreate(customer, ONE_ITEM));

            Instant before = Instant.now();
            payAndApply(customer, plan.name());       // chose the plan on the pricing page, paid, then signed in
            Instant after = Instant.now();

            me(customer).andExpect(jsonPath("$.subscription.plan").value(plan.name()))
                    .andExpect(jsonPath("$.subscription.status").value("ACTIVE"));
            Instant start = instantAt(customer, "plan_started_at");
            assertThat(start).isBetween(before.minusSeconds(1), after.plusSeconds(1));   // when it was really activated
            assertThat(instantAt(customer, "plan_valid_until")).isEqualTo(plan.validUntil(start));
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT source, previous_plan, new_plan, staff_id FROM subscription_history WHERE user_id = ?", customer.id());
            assertThat(row.get("source")).isEqualTo("PAYMENT");
            assertThat(row.get("previous_plan")).isNull();   // it replaced nothing: there was no plan
            assertThat(row.get("new_plan")).isEqualTo(plan.name());
            assertThat(jdbc.queryForObject("SELECT plan_before FROM payment WHERE redeemed_by_user_id = ?",
                    String.class, customer.id())).isNull();
            tryCreate(customer, ONE_ITEM).andExpect(status().isCreated());   // the core product works at once
        }
    }

    // ------------------------------------------------------------------ an account that never had a plan

    private static final String NO_PLAN_MESSAGE =
            "No active plan is associated with this account. Please contact our support team to activate your account.";

    private ResultActions refusedAsNoPlan(ResultActions result) throws Exception {
        return result.andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("no_active_subscription"))
                .andExpect(jsonPath("$.detail").value(NO_PLAN_MESSAGE));
    }

    @Test
    void aNewAccountHasNoPlanAndNothingIsActiveNotEvenMonthly() throws Exception {
        Account customer = registerWithoutPlan();   // exactly what the Login page's Create Account does
        me(customer).andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").isEmpty())
                .andExpect(jsonPath("$.subscription.plan").isEmpty())
                .andExpect(jsonPath("$.subscription.status").value("INACTIVE"))
                .andExpect(jsonPath("$.subscription.startedAt").isEmpty())
                .andExpect(jsonPath("$.subscription.validUntil").isEmpty())
                .andExpect(jsonPath("$.handoffCheck").value(false))
                // The only support there is: the way to ask for the activation.
                .andExpect(jsonPath("$.support.contactSupport").value(true))
                .andExpect(jsonPath("$.support.ticket").value(true))
                .andExpect(jsonPath("$.support.message").value(false))
                .andExpect(jsonPath("$.support.call").value(false))
                .andExpect(jsonPath("$.support.priority").value("NORMAL"));

        // Nothing on record pretends that a plan was chosen or paid for.
        assertThat(jdbc.queryForObject("SELECT subscription_plan FROM app_user WHERE id = ?", String.class, customer.id())).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM subscription_history WHERE user_id = ?", Long.class, customer.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment WHERE redeemed_by_user_id = ?", Long.class, customer.id())).isZero();

        // They can sign in, and signing in again shows the same account.
        me(login(customer.email())).andExpect(jsonPath("$.subscription.plan").isEmpty())
                .andExpect(jsonPath("$.subscription.status").value("INACTIVE"));
    }

    @Test
    void anAccountWithNoPlanCannotStartOrSendAHandoffOrUseHandoffCheckAndNothingIsCreated() throws Exception {
        Account customer = registerWithoutPlan();
        long sequence = jdbc.queryForObject("SELECT handoff_sequence FROM app_user WHERE id = ?", Long.class, customer.id());

        refusedAsNoPlan(tryCreate(customer, ONE_ITEM));                                      // New handoff, New draft, import-assisted: all save here
        refusedAsNoPlan(mvc.perform(as(customer, get("/api/v1/handoffs/1/template"))));       // Duplicate
        for (ResultActions call : everyHandoffCheckEndpoint(customer, 1)) {                   // HandoffCheck and both file imports
            refusedAsNoPlan(call);
        }

        assertThat(handoffCount(customer)).isZero();
        assertThat(jdbc.queryForObject("SELECT handoff_sequence FROM app_user WHERE id = ?", Long.class, customer.id())).isEqualTo(sequence);
    }

    @Test
    void sendingADraftIsRefusedForAnAccountWithNoPlanToo() throws Exception {
        Account customer = register();
        long draft = newDraft(customer);
        // An account that never had a plan has no drafts, so give this one a draft the only way possible: made while it had a plan.
        jdbc.update("UPDATE app_user SET subscription_plan = NULL, plan_started_at = NULL, plan_valid_until = NULL WHERE id = ?", customer.id());

        refusedAsNoPlan(trySend(customer, draft));
        assertThat(jdbc.queryForObject("SELECT status FROM handoff WHERE id = ?", String.class, draft)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recipient_link WHERE handoff_id = ?", Long.class, draft)).isZero();
    }

    @Test
    void anAccountWithNoPlanCanStillSignInAndSeeAnEmptyDashboardAndItsOwnDetails() throws Exception {
        Account customer = registerWithoutPlan();
        mvc.perform(as(customer, get("/api/v1/handoffs/dashboard"))).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(as(customer, get("/api/v1/handoffs"))).andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(0));
        me(customer).andExpect(jsonPath("$.accountCode").value(customer.accountCode()));
    }

    @Test
    void anAccountWithNoPlanCanAskSupportToActivateItAndGetNothingMoreThanThat() throws Exception {
        StaffAccount agent = registerStaff(SupportRole.TICKET_AGENT);
        Account customer = registerWithoutPlan();

        String ticket = createTicket(customer, "Please activate my account");
        mvc.perform(as(customer, get("/api/v1/tickets"))).andExpect(status().isOk()).andExpect(jsonPath("$.content.length()").value(1));
        mvc.perform(as(customer, get("/api/v1/tickets/" + ticket))).andExpect(status().isOk());

        // The desk sees whose it is: no plan, normal priority, and the usual notice says so too.
        mvc.perform(as(agent, get("/api/v1/support/tickets/" + ticket))).andExpect(status().isOk())
                .andExpect(jsonPath("$.ticket.plan").isEmpty())
                .andExpect(jsonPath("$.ticket.priority").value("NORMAL"));
        assertThat(emailSender.messagesTo(SUPPORT_MAILBOX).getLast().textBody()).contains("Plan:      NONE (NORMAL priority)");

        // No plain-message form (a Quarterly feature) and no phone number: the help is a ticket, nothing else.
        mvc.perform(as(customer, multipart("/api/v1/support-messages").param("subject", "Hello").param("message", "Please help")))
                .andExpect(status().isForbidden());
        me(customer).andExpect(jsonPath("$.support.supportPhone").isEmpty());
    }

    @Test
    void thePlanLessAccountsSupportEndsWhenAPlanIsActivatedAndThenTheOrdinaryRulesApply() throws Exception {
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        Account customer = registerWithoutPlan();
        createTicket(customer, "Please activate my account");

        changePlan(staff, customer, null, "MONTHLY", null, null).andExpect(status().isOk());
        // Monthly has no in-app support at all: neither tickets nor messages, exactly as for any Monthly customer.
        mvc.perform(as(customer, get("/api/v1/tickets"))).andExpect(status().isForbidden());
        mvc.perform(as(customer, post("/api/v1/tickets").contentType(MediaType.APPLICATION_JSON)
                .content("{\"subject\":\"Again\",\"category\":\"GENERAL\",\"description\":\"Hello\"}"))).andExpect(status().isForbidden());
        mvc.perform(as(customer, multipart("/api/v1/support-messages").param("subject", "Hello").param("message", "Please help")))
                .andExpect(status().isForbidden());
        me(customer).andExpect(jsonPath("$.support.contactSupport").value(false)).andExpect(jsonPath("$.support.ticket").value(false));
    }

    @Test
    void supportActivatesAnAccountWithNoPlanAndNeedsNoEndDate() throws Exception {
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        Account customer = registerWithoutPlan();

        // The portal shows exactly what it is: no plan, nothing active, no history.
        profile(staff, customer).andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.plan").isEmpty())
                .andExpect(jsonPath("$.subscription.plan").isEmpty())
                .andExpect(jsonPath("$.subscription.status").value("INACTIVE"))
                .andExpect(jsonPath("$.entitlements.ticket").value(true))
                .andExpect(jsonPath("$.subscriptionHistory.length()").value(0));

        // A view that thinks the customer is on a plan is stale, as for any change; nothing happens.
        changePlan(staff, customer, "MONTHLY", "QUARTERLY", null, null).andExpect(status().isConflict());
        assertThat(users.findById(customer.id()).orElseThrow().hasPlan()).isFalse();

        Instant before = Instant.now();
        changePlan(staff, customer, null, "HALF_YEARLY", null, "Paid at the desk").andExpect(status().isOk())
                .andExpect(jsonPath("$.subscription.plan").value("HALF_YEARLY"))
                .andExpect(jsonPath("$.subscription.status").value("ACTIVE"))
                .andExpect(jsonPath("$.entitlements.ticket").value(true))
                .andExpect(jsonPath("$.subscriptionHistory.length()").value(1))
                .andExpect(jsonPath("$.subscriptionHistory[0].previousPlan").isEmpty())
                .andExpect(jsonPath("$.subscriptionHistory[0].newPlan").value("HALF_YEARLY"))
                .andExpect(jsonPath("$.subscriptionHistory[0].source").value("STAFF"))
                .andExpect(jsonPath("$.subscriptionHistory[0].staffCode").value(staff.staffCode()))
                .andExpect(jsonPath("$.recentChanges[0].type").value("PLAN_CHANGED"))
                .andExpect(jsonPath("$.recentChanges[0].previousValue").isEmpty())
                .andExpect(jsonPath("$.recentChanges[0].newValue").value("HALF_YEARLY"));

        // Started when support did it, ends six calendar months later, and nobody typed a date.
        Instant start = instantAt(customer, "plan_started_at");
        assertThat(start).isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
        assertThat(instantAt(customer, "plan_valid_until")).isEqualTo(SubscriptionPlan.HALF_YEARLY.validUntil(start));
        tryCreate(customer, ONE_ITEM).andExpect(status().isCreated());
    }

    @Test
    void supportCanStillNameTheLastDayWhenActivatingAnAccountWithNoPlan() throws Exception {
        StaffAccount staff = registerStaff(SupportRole.ADMIN);
        Account customer = registerWithoutPlan();
        String lastDay = inDays(10);
        changePlan(staff, customer, null, "YEARLY", lastDay, null).andExpect(status().isOk())
                .andExpect(jsonPath("$.subscription.validUntil").value(LocalDate.parse(lastDay).plusDays(1) + "T00:00:00Z"));
    }

    @Test
    void namingNoPlanIsHowAStaffMemberSaysTheCustomerHasNoneAndItNeverAppliesToACustomerWhoHasOne() throws Exception {
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        Account withPlan = register(SubscriptionPlan.YEARLY);
        changePlan(staff, withPlan, null, "MONTHLY", null, null).andExpect(status().isConflict());   // a missing view is a stale view
        assertThat(users.findById(withPlan.id()).orElseThrow().getSubscriptionPlan()).isEqualTo(SubscriptionPlan.YEARLY);

        Account withoutPlan = registerWithoutPlan();
        // Keeping 'the same plan' is meaningless when there is none; and the plan on the customer must be named exactly.
        changePlan(staff, withoutPlan, "YEARLY", "YEARLY", inDays(5), null).andExpect(status().isConflict());
    }

    @Test
    void aPlanThatRanOutIsNotNoPlanAndKeepsItsOwnMessageAndSupportRules() throws Exception {
        Account customer = register(SubscriptionPlan.YEARLY);
        lapse(customer);
        me(customer).andExpect(jsonPath("$.subscription.plan").value("YEARLY"))
                .andExpect(jsonPath("$.subscription.status").value("INACTIVE"));
        refusedAsEnded(tryCreate(customer, ONE_ITEM));
        // The activation help is for accounts that never had a plan; one whose plan ran out gets what it always did.
        mvc.perform(as(customer, get("/api/v1/tickets"))).andExpect(status().isForbidden());
        me(customer).andExpect(jsonPath("$.support.ticket").value(false)).andExpect(jsonPath("$.support.contactSupport").value(false));
    }

    // ------------------------------------------------------------------ who may see and change it

    @Test
    void theHistoryIsForStaffWhoMayViewCustomersAndOnlyPlanManagersMayChangeIt() throws Exception {
        Account customer = register();
        StaffAccount admin = registerStaff(SupportRole.ADMIN);
        StaffAccount manager = registerStaff(SupportRole.MANAGER);
        StaffAccount agent = registerStaff(SupportRole.TICKET_AGENT);

        changePlan(admin, customer, "MONTHLY", "QUARTERLY", inDays(90), null).andExpect(status().isOk());
        changePlan(manager, customer, "QUARTERLY", "YEARLY", inDays(365), null).andExpect(status().isOk());
        profile(admin, customer).andExpect(jsonPath("$.subscriptionHistory.length()").value(2));
        profile(manager, customer).andExpect(jsonPath("$.subscriptionHistory.length()").value(2));

        profile(agent, customer).andExpect(status().isForbidden());                                   // no customer records at all
        changePlan(agent, customer, "YEARLY", "MONTHLY", null, null).andExpect(status().isForbidden());   // no plan administration
        // A customer cannot see or edit it, and neither can anyone without a staff login.
        mvc.perform(as(customer, put("/api/v1/support/customers/" + customer.accountCode() + "/plan")
                .contentType(MediaType.APPLICATION_JSON).content("{\"fromPlan\":\"YEARLY\",\"toPlan\":\"MONTHLY\"}")))
                .andExpect(status().isUnauthorized());
        me(customer).andExpect(jsonPath("$.subscription.plan").value("YEARLY"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM subscription_history WHERE user_id = ?", Long.class, customer.id())).isEqualTo(2L);
    }
}
