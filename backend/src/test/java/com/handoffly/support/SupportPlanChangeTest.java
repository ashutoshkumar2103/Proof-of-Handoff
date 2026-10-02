package com.handoffly.support;

import com.handoffly.support.staff.SupportRole;
import com.handoffly.testsupport.ApiTestBase;
import com.handoffly.user.SubscriptionPlan;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.CrudRepository;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Support changes a customer's plan from the support portal, explicitly and on the record. What the
 * customer may use follows from the plan alone, so entitlements update automatically — there is no
 * way to switch individual features on or off.
 */
class SupportPlanChangeTest extends ApiTestBase {

    private ResultActions changePlan(Bearer who, Account customer, String from, String to, String reason) throws Exception {
        String reasonJson = reason == null ? "" : ",\"reason\":\"" + reason + "\"";
        return changePlanRaw(who, customer, "{\"fromPlan\":\"" + from + "\",\"toPlan\":\"" + to + "\"" + reasonJson + "}");
    }

    private ResultActions changePlanRaw(Bearer who, Account customer, String json) throws Exception {
        return mvc.perform(as(who, put("/api/v1/support/customers/" + customer.accountCode() + "/plan")
                .contentType(MediaType.APPLICATION_JSON).content(json)));
    }

    private ResultActions profile(Bearer staff, Account customer) throws Exception {
        return mvc.perform(as(staff, get("/api/v1/support/customers/" + customer.accountCode())));
    }

    private long auditCount(Account customer) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM support_audit_event WHERE customer_id = ?", Long.class, customer.id());
    }

    /** What the customer's own app is told, and what the backend really lets them do, for a plan. */
    private void assertCustomerSees(Account customer, String plan, boolean contactSupport, boolean ticket, boolean call)
            throws Exception {
        ResultActions me = mvc.perform(as(customer, get("/api/v1/auth/me"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value(plan))
                .andExpect(jsonPath("$.support.contactSupport").value(contactSupport))
                .andExpect(jsonPath("$.support.ticket").value(ticket))
                .andExpect(jsonPath("$.support.call").value(call));
        if (call) {
            me.andExpect(jsonPath("$.support.supportPhone").value(SUPPORT_PHONE));
        } else {
            me.andExpect(jsonPath("$.support.supportPhone").doesNotExist());
        }
        mvc.perform(as(customer, get("/api/v1/tickets")))
                .andExpect(ticket ? status().isOk() : status().isForbidden());
    }

    @Test
    void aPlanChangeMovesTheCustomerAndTheirEntitlementsFollowAutomatically() throws Exception {
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        Account customer = register();   // new accounts start on MONTHLY
        assertCustomerSees(customer, "MONTHLY", false, false, false);

        changePlan(staff, customer, "MONTHLY", "QUARTERLY", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.plan").value("QUARTERLY"))
                .andExpect(jsonPath("$.entitlements.contactSupport").value(true))
                .andExpect(jsonPath("$.entitlements.message").value(true))
                .andExpect(jsonPath("$.entitlements.ticket").value(false))
                .andExpect(jsonPath("$.entitlements.call").value(false))
                .andExpect(jsonPath("$.entitlements.priority").value("NORMAL"));
        assertCustomerSees(customer, "QUARTERLY", true, false, false);   // assisted: messages, but no tickets or call

        changePlan(staff, customer, "QUARTERLY", "HALF_YEARLY", "Customer upgraded after payment.")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.plan").value("HALF_YEARLY"))
                .andExpect(jsonPath("$.entitlements.contactSupport").value(true))
                .andExpect(jsonPath("$.entitlements.ticket").value(true))
                .andExpect(jsonPath("$.entitlements.call").value(false));
        assertCustomerSees(customer, "HALF_YEARLY", true, true, false);

        changePlan(staff, customer, "HALF_YEARLY", "YEARLY", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.plan").value("YEARLY"))
                .andExpect(jsonPath("$.entitlements.call").value(true));
        assertCustomerSees(customer, "YEARLY", true, true, true);

        changePlan(staff, customer, "YEARLY", "MONTHLY", "Subscription ended.")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.plan").value("MONTHLY"))
                .andExpect(jsonPath("$.entitlements.contactSupport").value(false))
                .andExpect(jsonPath("$.entitlements.ticket").value(false))
                .andExpect(jsonPath("$.entitlements.call").value(false));
        assertCustomerSees(customer, "MONTHLY", false, false, false);
    }

    @Test
    void aTicketOpenedWhileEntitledSurvivesButIsClosedToTheCustomerOnceTheyDropToMonthly() throws Exception {
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        Account customer = register(SubscriptionPlan.HALF_YEARLY);
        String ticket = createTicket(customer, "Opened while on a plan with tickets");

        changePlan(staff, customer, "HALF_YEARLY", "MONTHLY", null).andExpect(status().isOk());
        mvc.perform(as(customer, get("/api/v1/tickets/" + ticket))).andExpect(status().isForbidden());   // enforced by the backend
        mvc.perform(as(staff, get("/api/v1/support/tickets/" + ticket))).andExpect(status().isOk());    // support still has it
    }

    @Test
    void everyPlanChangeIsRecordedWithWhoWhatWhenAndWhy() throws Exception {
        StaffAccount first = registerStaff(SupportRole.MANAGER);
        StaffAccount second = registerStaff(SupportRole.ADMIN);
        Account customer = register();

        changePlan(first, customer, "MONTHLY", "YEARLY", "Customer upgraded after payment.").andExpect(status().isOk());
        changePlan(second, customer, "YEARLY", "HALF_YEARLY", "  ").andExpect(status().isOk());   // a blank reason is no reason

        profile(second, customer).andExpect(status().isOk())
                .andExpect(jsonPath("$.recentChanges.length()").value(2))
                // newest first
                .andExpect(jsonPath("$.recentChanges[0].type").value("PLAN_CHANGED"))
                .andExpect(jsonPath("$.recentChanges[0].previousValue").value("YEARLY"))
                .andExpect(jsonPath("$.recentChanges[0].newValue").value("HALF_YEARLY"))
                .andExpect(jsonPath("$.recentChanges[0].staffCode").value(second.staffCode()))
                .andExpect(jsonPath("$.recentChanges[0].reason").doesNotExist())
                .andExpect(jsonPath("$.recentChanges[1].type").value("PLAN_CHANGED"))
                .andExpect(jsonPath("$.recentChanges[1].previousValue").value("MONTHLY"))
                .andExpect(jsonPath("$.recentChanges[1].newValue").value("YEARLY"))
                .andExpect(jsonPath("$.recentChanges[1].staffCode").value(first.staffCode()))
                .andExpect(jsonPath("$.recentChanges[1].staffName").value("Support Agent"))
                .andExpect(jsonPath("$.recentChanges[1].reason").value("Customer upgraded after payment."))
                .andExpect(jsonPath("$.recentChanges[1].at").exists());
        assertThat(auditCount(customer)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT staff_id FROM support_audit_event WHERE customer_id = ? AND new_value = 'YEARLY'",
                Long.class, customer.id())).isEqualTo(first.id());
    }

    @Test
    void aStaleOrPointlessChangeIsRefusedAndLeavesNoTrace() throws Exception {
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        Account customer = register(SubscriptionPlan.HALF_YEARLY);

        // The staff member's view was out of date (they think the customer is MONTHLY).
        changePlan(staff, customer, "MONTHLY", "YEARLY", null).andExpect(status().isConflict());
        // "Changing" to the plan the customer is already on.
        changePlan(staff, customer, "HALF_YEARLY", "HALF_YEARLY", null).andExpect(status().isConflict());

        assertThat(users.findById(customer.id()).orElseThrow().getSubscriptionPlan()).isEqualTo(SubscriptionPlan.HALF_YEARLY);
        assertThat(auditCount(customer)).isZero();
    }

    @Test
    void malformedPlanChangesAreRefused() throws Exception {
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        Account customer = register();

        changePlanRaw(staff, customer, "{}").andExpect(status().isBadRequest());
        changePlanRaw(staff, customer, "{\"toPlan\":\"YEARLY\"}").andExpect(status().isBadRequest());   // must name what it is now
        changePlanRaw(staff, customer, "{\"fromPlan\":\"MONTHLY\"}").andExpect(status().isBadRequest());
        changePlanRaw(staff, customer, "{\"fromPlan\":\"MONTHLY\",\"toPlan\":\"WEEKLY\"}").andExpect(status().isBadRequest());
        changePlanRaw(staff, customer, "{\"fromPlan\":\"MONTHLY\",\"toPlan\":\"YEARLY\",\"reason\":\"" + "r".repeat(501) + "\"}")
                .andExpect(status().isBadRequest());
        changePlanRaw(staff, customer, "not json").andExpect(status().isBadRequest());
        // Entitlements cannot be set individually — such fields are ignored and nothing changes.
        changePlanRaw(staff, customer, "{\"fromPlan\":\"MONTHLY\",\"toPlan\":\"MONTHLY\",\"ticket\":true,\"call\":true}")
                .andExpect(status().isConflict());

        mvc.perform(as(staff, put("/api/v1/support/customers/CUS-999999/plan").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromPlan\":\"MONTHLY\",\"toPlan\":\"YEARLY\"}"))).andExpect(status().isNotFound());
        assertThat(users.findById(customer.id()).orElseThrow().getSubscriptionPlan()).isEqualTo(SubscriptionPlan.MONTHLY);
        assertThat(auditCount(customer)).isZero();
    }

    @Test
    void prefixChangesAreRecordedToo_andSayingTheSamePrefixAgainIsNotAChange() throws Exception {
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        Account customer = register();

        setPrefix(staff, customer, "AV");
        setPrefix(staff, customer, "AV");   // no change, no record
        setPrefix(staff, customer, "RK");

        profile(staff, customer).andExpect(status().isOk())
                .andExpect(jsonPath("$.recentChanges.length()").value(2))
                .andExpect(jsonPath("$.recentChanges[0].type").value("HANDOFF_PREFIX_CHANGED"))
                .andExpect(jsonPath("$.recentChanges[0].previousValue").value("AV"))
                .andExpect(jsonPath("$.recentChanges[0].newValue").value("RK"))
                .andExpect(jsonPath("$.recentChanges[1].previousValue").value("HO"))
                .andExpect(jsonPath("$.recentChanges[1].newValue").value("AV"))
                .andExpect(jsonPath("$.recentChanges[1].staffCode").value(staff.staffCode()));
    }

    @Test
    void theAuditTrailHasNoWayToBeEditedOrDeleted() throws Exception {
        // Not through the API: nothing is mapped to change or remove a record.
        StaffAccount staff = registerStaff(SupportRole.ADMIN);
        Account customer = register();
        changePlan(staff, customer, "MONTHLY", "YEARLY", null).andExpect(status().isOk());
        for (String path : List.of("/api/v1/support/audit", "/api/v1/support/audit/1",
                "/api/v1/support/customers/" + customer.accountCode() + "/audit",
                "/api/v1/support/customers/" + customer.accountCode() + "/changes")) {
            for (HttpMethod method : List.of(HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE, HttpMethod.POST)) {
                int result = mvc.perform(as(staff, request(method, path).contentType(MediaType.APPLICATION_JSON).content("{}")))
                        .andReturn().getResponse().getStatus();
                assertThat(result).as(method + " " + path).isIn(404, 405);
            }
        }
        assertThat(auditCount(customer)).isEqualTo(1);

        // Not through the application code either: the entity is immutable and its repository only adds and reads.
        assertThat(SupportAuditEvent.class.isAnnotationPresent(org.hibernate.annotations.Immutable.class)).isTrue();
        assertThat(Arrays.stream(SupportAuditEvent.class.getMethods()).map(Method::getName))
                .noneMatch(name -> name.startsWith("set"));
        assertThat(CrudRepository.class.isAssignableFrom(SupportAuditEventRepository.class)).isFalse();
        assertThat(Arrays.stream(SupportAuditEventRepository.class.getMethods()).map(Method::getName))
                .noneMatch(name -> name.startsWith("delete") || name.startsWith("update") || name.startsWith("remove"));
    }

    @Test
    void customersCannotChangeTheirOwnPlanAndCustomerTokensDoNotReachSupport() throws Exception {
        Account customer = register();

        changePlan(customer, customer, "MONTHLY", "YEARLY", null).andExpect(status().isUnauthorized());
        mvc.perform(as(customer, request(HttpMethod.PUT, "/api/v1/auth/me").contentType(MediaType.APPLICATION_JSON)
                .content("{\"plan\":\"YEARLY\"}"))).andExpect(status().is4xxClientError());
        assertThat(users.findById(customer.id()).orElseThrow().getSubscriptionPlan()).isEqualTo(SubscriptionPlan.MONTHLY);
        assertThat(auditCount(customer)).isZero();
    }

    @Test
    void aPlanChangeLeavesTheCustomersHandoffsAndNumberingAlone() throws Exception {
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        Account customer = register();
        String before = createHandoff(customer);
        long handoffId = ((Number) JsonPath.read(before, "$.id")).longValue();

        changePlan(staff, customer, "MONTHLY", "YEARLY", null).andExpect(status().isOk())
                .andExpect(jsonPath("$.nextHandoffReference").value("HO-2"))
                .andExpect(jsonPath("$.customer.handoffPrefix").value("HO"));

        mvc.perform(as(customer, get("/api/v1/handoffs/" + handoffId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.publicCode").value("HO-1")).andExpect(jsonPath("$.status").value("DRAFT"));
        assertThat(JsonPath.<String>read(createHandoff(customer), "$.publicCode")).isEqualTo("HO-2");
    }

    @Test
    void theAuditShowsWhoIsWhoByStaffIdNotByEmail() throws Exception {
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        Account customer = register();
        changePlan(staff, customer, "MONTHLY", "HALF_YEARLY", null).andExpect(status().isOk());

        String json = profile(staff, customer).andReturn().getResponse().getContentAsString();
        assertThat(json).contains("STAFF-").doesNotContain(staff.email());
        profile(staff, customer).andExpect(jsonPath("$.recentChanges[0].staffCode", matchesPattern("STAFF-\\d{2,}")));
    }
}
