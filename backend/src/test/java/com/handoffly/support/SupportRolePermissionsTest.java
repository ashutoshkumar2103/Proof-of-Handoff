package com.handoffly.support;

import com.handoffly.support.staff.SupportRole;
import com.handoffly.testsupport.ApiTestBase;
import com.handoffly.user.SubscriptionPlan;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The role hierarchy, endpoint by endpoint: ADMIN ⊃ MANAGER ⊃ TICKET_AGENT. Every endpoint is tried with every
 * role; a role without the permission gets 403 (it IS signed in, it just may not), a role with it gets past
 * authorization (whatever the request then does), and a customer or anonymous caller never gets in.
 */
class SupportRolePermissionsTest extends ApiTestBase {

    private static final SupportRole[] ROLES = {SupportRole.ADMIN, SupportRole.MANAGER, SupportRole.TICKET_AGENT};
    private static final List<SupportRole> ALL = List.of(SupportRole.ADMIN, SupportRole.MANAGER, SupportRole.TICKET_AGENT);
    private static final List<SupportRole> CUSTOMER_ADMINISTRATION = List.of(SupportRole.ADMIN, SupportRole.MANAGER);
    private static final List<SupportRole> ADMIN_ONLY = List.of(SupportRole.ADMIN);

    private record Endpoint(String name, HttpMethod method, String path, String body, List<SupportRole> allowed) {}

    /** Requests chosen so that, once allowed in, they end in 2xx or a harmless 4xx — never a real change that the next role trips on. */
    private List<Endpoint> endpoints(Account customer, String ticket) {
        String c = "/api/v1/support/customers/" + customer.accountCode();
        String t = "/api/v1/support/tickets/" + ticket;
        return List.of(
                new Endpoint("dashboard", HttpMethod.GET, "/api/v1/support/dashboard", null, ALL),
                new Endpoint("who am I", HttpMethod.GET, "/api/v1/support/auth/me", null, ALL),
                new Endpoint("ticket list", HttpMethod.GET, "/api/v1/support/tickets", null, ALL),
                new Endpoint("ticket detail", HttpMethod.GET, t, null, ALL),
                new Endpoint("ticket status", HttpMethod.PUT, t + "/status", "{\"status\":\"IN_PROGRESS\"}", ALL),
                new Endpoint("ticket reply", HttpMethod.POST, t + "/messages", "{\"body\":\"Looking into it.\"}", ALL),
                new Endpoint("ticket attachment", HttpMethod.GET, t + "/attachments/1/content", null, ALL),
                new Endpoint("customer search", HttpMethod.GET, "/api/v1/support/customers", null, CUSTOMER_ADMINISTRATION),
                new Endpoint("customer profile", HttpMethod.GET, c, null, CUSTOMER_ADMINISTRATION),
                new Endpoint("change prefix", HttpMethod.PUT, c + "/prefix", "{\"prefix\":\"AV\"}", CUSTOMER_ADMINISTRATION),
                // a no-op change: refused with 409 once the caller is allowed in
                new Endpoint("change plan", HttpMethod.PUT, c + "/plan",
                        "{\"fromPlan\":\"HALF_YEARLY\",\"toPlan\":\"HALF_YEARLY\"}", CUSTOMER_ADMINISTRATION),
                new Endpoint("staff list", HttpMethod.GET, "/api/v1/support/staff", null, ADMIN_ONLY),
                // 400: password too short
                new Endpoint("create staff", HttpMethod.POST, "/api/v1/support/staff",
                        "{\"name\":\"X\",\"email\":\"x@example.test\",\"password\":\"short\",\"role\":\"MANAGER\"}", ADMIN_ONLY),
                // 404: no such staff member
                new Endpoint("change staff role", HttpMethod.PUT, "/api/v1/support/staff/STAFF-999999/role",
                        "{\"fromRole\":\"MANAGER\",\"toRole\":\"TICKET_AGENT\"}", ADMIN_ONLY),
                new Endpoint("change staff active", HttpMethod.PUT, "/api/v1/support/staff/STAFF-999999/active",
                        "{\"active\":false}", ADMIN_ONLY),
                new Endpoint("audit trail", HttpMethod.GET, "/api/v1/support/audit", null, ADMIN_ONLY));
    }

    private int send(Endpoint e, Bearer caller) throws Exception {
        MockHttpServletRequestBuilder request = request(e.method(), e.path());
        if (e.body() != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(e.body());
        }
        return mvc.perform(caller == null ? request : as(caller, request)).andReturn().getResponse().getStatus();
    }

    @Test
    void everyRoleCanDoExactlyWhatItsPermissionsAllow() throws Exception {
        Account customer = register(SubscriptionPlan.HALF_YEARLY);
        String ticket = createTicket(customer, "Role matrix");
        List<Endpoint> endpoints = endpoints(customer, ticket);

        for (SupportRole role : ROLES) {
            StaffAccount staff = registerStaff(role);
            for (Endpoint e : endpoints) {
                int status = send(e, staff);
                String what = role + " → " + e.name();
                if (e.allowed().contains(role)) {
                    assertThat(status).as(what + " should get past authorization").isNotIn(401, 403);
                } else {
                    assertThat(status).as(what + " should be refused").isEqualTo(403);
                }
            }
        }
    }

    @Test
    void customersAndAnonymousCallersNeverGetIn() throws Exception {
        Account customer = register(SubscriptionPlan.YEARLY);
        String ticket = createTicket(customer, "Not for customers");
        for (Endpoint e : endpoints(customer, ticket)) {
            assertThat(send(e, null)).as("anonymous → " + e.name()).isEqualTo(401);
            assertThat(send(e, customer)).as("customer → " + e.name()).isEqualTo(401);
        }
    }

    @Test
    void theRolesPermissionsAreReportedToThePortalSoItShowsOnlyWhatIsAllowed() throws Exception {
        mvc.perform(as(registerStaff(SupportRole.ADMIN), get("/api/v1/support/auth/me")))
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andExpect(jsonPath("$.permissions", containsInAnyOrder("VIEW_DASHBOARD", "WORK_TICKETS", "VIEW_CUSTOMERS",
                        "MANAGE_CUSTOMERS", "MANAGE_STAFF", "VIEW_AUDIT")));
        mvc.perform(as(registerStaff(SupportRole.MANAGER), get("/api/v1/support/auth/me")))
                .andExpect(jsonPath("$.role").value("MANAGER"))
                .andExpect(jsonPath("$.permissions", containsInAnyOrder("VIEW_DASHBOARD", "WORK_TICKETS", "VIEW_CUSTOMERS",
                        "MANAGE_CUSTOMERS")));
        mvc.perform(as(registerStaff(SupportRole.TICKET_AGENT), get("/api/v1/support/auth/me")))
                .andExpect(jsonPath("$.role").value("TICKET_AGENT"))
                .andExpect(jsonPath("$.permissions", containsInAnyOrder("VIEW_DASHBOARD", "WORK_TICKETS")));
    }

    @Test
    void ticketAgentsSeeTicketMetricsOnlyWhileManagersAndAdminsAlsoSeeCustomerOnes() throws Exception {
        Account yearly = register(SubscriptionPlan.YEARLY);
        createTicket(yearly, "Priority customer ticket");

        for (SupportRole role : List.of(SupportRole.ADMIN, SupportRole.MANAGER)) {
            mvc.perform(as(registerStaff(role), get("/api/v1/support/dashboard")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.open").isNumber())
                    .andExpect(jsonPath("$.priorityCustomers").isNumber());
        }
        mvc.perform(as(registerStaff(SupportRole.TICKET_AGENT), get("/api/v1/support/dashboard")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.open").isNumber())
                .andExpect(jsonPath("$.inProgress").isNumber())
                .andExpect(jsonPath("$.waitingForCustomer").isNumber())
                .andExpect(jsonPath("$.priorityCustomers").doesNotExist());   // a customer metric: not for ticket agents
    }

    @Test
    void aTicketAgentGetsTheCustomerDetailsATicketNeedsAndNothingMore() throws Exception {
        Account customer = register("Ticket Customer", SubscriptionPlan.YEARLY);
        String ticket = createTicket(customer, "Agent privacy");
        createHandoff(customer);   // an unrelated handoff the agent must never see
        StaffAccount agent = registerStaff(SupportRole.TICKET_AGENT);

        String detail = mvc.perform(as(agent, get("/api/v1/support/tickets/" + ticket)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticket.accountCode").value(customer.accountCode()))
                .andExpect(jsonPath("$.ticket.customerName").value("Ticket Customer"))
                .andExpect(jsonPath("$.ticket.customerEmail").value(customer.email()))
                .andExpect(jsonPath("$.ticket.customerPhone").value(CUSTOMER_PHONE))
                .andExpect(jsonPath("$.ticket.plan").value("YEARLY"))
                .andExpect(jsonPath("$.ticket.handoffReference").value("AV-3"))
                .andReturn().getResponse().getContentAsString();
        assertThat(detail).doesNotContain("passwordHash", "password_hash", "$2a$", "Laptop handout", "hire@example.test", "SN-1");

        // No customer administration or customer records at all; and customer handoffs stay out of reach.
        mvc.perform(as(agent, get("/api/v1/support/customers/" + customer.accountCode()))).andExpect(status().isForbidden());
        mvc.perform(as(agent, get("/api/v1/support/customers"))).andExpect(status().isForbidden());
        mvc.perform(as(agent, get("/api/v1/handoffs"))).andExpect(status().isUnauthorized());
    }

    @Test
    void ticketAgentsCannotChangePlansOrPrefixesButManagersAndAdminsCan() throws Exception {
        Account customer = register();
        StaffAccount agent = registerStaff(SupportRole.TICKET_AGENT);
        String prefix = "/api/v1/support/customers/" + customer.accountCode() + "/prefix";
        String plan = "/api/v1/support/customers/" + customer.accountCode() + "/plan";

        mvc.perform(as(agent, request(HttpMethod.PUT, prefix).contentType(MediaType.APPLICATION_JSON).content("{\"prefix\":\"ZZ\"}")))
                .andExpect(status().isForbidden());
        mvc.perform(as(agent, request(HttpMethod.PUT, plan).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fromPlan\":\"MONTHLY\",\"toPlan\":\"YEARLY\"}"))).andExpect(status().isForbidden());
        assertThat(users.findById(customer.id()).orElseThrow().getHandoffPrefix()).isEqualTo("HO");
        assertThat(users.findById(customer.id()).orElseThrow().getSubscriptionPlan()).isEqualTo(SubscriptionPlan.MONTHLY);

        mvc.perform(as(registerStaff(SupportRole.MANAGER), request(HttpMethod.PUT, prefix)
                .contentType(MediaType.APPLICATION_JSON).content("{\"prefix\":\"MG\"}"))).andExpect(status().isOk());
        mvc.perform(as(registerStaff(SupportRole.ADMIN), request(HttpMethod.PUT, plan)
                .contentType(MediaType.APPLICATION_JSON).content("{\"fromPlan\":\"MONTHLY\",\"toPlan\":\"YEARLY\"}")))
                .andExpect(status().isOk());
        assertThat(users.findById(customer.id()).orElseThrow().getHandoffPrefix()).isEqualTo("MG");
        assertThat(users.findById(customer.id()).orElseThrow().getSubscriptionPlan()).isEqualTo(SubscriptionPlan.YEARLY);
    }

    @Test
    void ticketAgentsWorkTicketsEndToEnd() throws Exception {
        Account customer = register(SubscriptionPlan.HALF_YEARLY);
        String ticket = createTicket(customer, "Agent works it");
        StaffAccount agent = registerStaff(SupportRole.TICKET_AGENT);
        String base = "/api/v1/support/tickets/" + ticket;

        mvc.perform(as(agent, get("/api/v1/support/tickets").param("status", "OPEN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[*].ticketCode", hasItem(ticket)));
        mvc.perform(as(agent, request(HttpMethod.POST, base + "/messages").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"body\":\"Hello from the agent\"}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.messages[0].author").value("SUPPORT"))
                .andExpect(jsonPath("$.messages[0].authorName").value("Support Agent"));
        mvc.perform(as(agent, request(HttpMethod.PUT, base + "/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESOLVED\"}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ticket.status").value("RESOLVED"));
        assertThat(emailSender.getLastMessage().to()).isEqualTo(customer.email());
    }
}
