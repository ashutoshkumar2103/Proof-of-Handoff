package com.handoffly.support;

import com.handoffly.support.staff.SupportRole;
import com.handoffly.testsupport.ApiTestBase;
import com.handoffly.user.SubscriptionPlan;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Customers and support staff are separate identities with separate logins and tokens. This covers the
 * support login, that neither kind of credential works on the other's API, and who may use the support API.
 */
class SupportAuthorizationTest extends ApiTestBase {

    private record Call(HttpMethod method, String path, String body) {}

    private ResultActions perform(Call call, Bearer caller) throws Exception {
        MockHttpServletRequestBuilder request = request(call.method(), call.path());
        if (call.body() != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(call.body());
        }
        return mvc.perform(caller == null ? request : as(caller, request));
    }

    private int statusOf(Call call, Bearer caller) throws Exception {
        return perform(call, caller).andReturn().getResponse().getStatus();
    }

    private ResultActions staffLogin(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/support/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private ResultActions customerLogin(String email, String password) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    // ------------------------------------------------------------------ the support login

    @Test
    void staffSignInThroughTheirOwnLoginAndGetAStaffIdentityNotACustomerOne() throws Exception {
        StaffAccount staff = registerStaff(SupportRole.MANAGER);

        staffLogin(staff.email(), STAFF_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").exists())
                .andExpect(jsonPath("$.staff.staffCode").value(matchesPattern("STAFF-\\d{2,}")))
                .andExpect(jsonPath("$.staff.name").value("Support Agent"))
                .andExpect(jsonPath("$.staff.email").value(staff.email()))
                .andExpect(jsonPath("$.staff.role").value("MANAGER"))
                .andExpect(jsonPath("$.staff.permissions", hasItems("MANAGE_CUSTOMERS", "WORK_TICKETS")))
                .andExpect(jsonPath("$.staff.permissions", not(hasItem("MANAGE_STAFF"))))
                .andExpect(jsonPath("$.user").doesNotExist())
                .andExpect(jsonPath("$.staff.passwordHash").doesNotExist());

        mvc.perform(as(staff, get("/api/v1/support/auth/me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staffCode").value(staff.staffCode()))
                .andExpect(jsonPath("$.role").value("MANAGER"));
    }

    @Test
    void loginFailuresAreGenericWhateverTheReason() throws Exception {
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        StaffAccount inactive = registerStaff(SupportRole.MANAGER);
        deactivate(inactive);

        String wrongPassword = staffLogin(staff.email(), "wrong-password-123").andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String unknownEmail = staffLogin("nobody@support.example.test", STAFF_PASSWORD).andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        String deactivated = staffLogin(inactive.email(), STAFF_PASSWORD).andExpect(status().isUnauthorized())
                .andReturn().getResponse().getContentAsString();
        // The same message every time, so the login cannot be used to find out who is on the team.
        assertThat(wrongPassword).contains("Invalid email or password.");
        assertThat(unknownEmail).contains("Invalid email or password.");
        assertThat(deactivated).contains("Invalid email or password.");

        staffLogin("", "").andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/support/auth/login").contentType(MediaType.APPLICATION_JSON).content("not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void customerCredentialsAreNotStaffCredentialsAndTheOtherWayRound() throws Exception {
        Account customer = register();
        StaffAccount staff = registerStaff(SupportRole.MANAGER);

        staffLogin(customer.email(), PASSWORD).andExpect(status().isUnauthorized());     // a customer cannot sign in as staff
        customerLogin(staff.email(), STAFF_PASSWORD).andExpect(status().isUnauthorized()); // staff cannot sign in as a customer
    }

    @Test
    void thePasswordForOneIdentityNeverOpensTheOtherEvenWithTheSameEmail() throws Exception {
        String shared = "shared-" + System.nanoTime() + "@example.test";
        staffService.provision("Shared Email Staff", shared, STAFF_PASSWORD, SupportRole.MANAGER);
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + shared + "\",\"password\":\"" + PASSWORD + "\",\"displayName\":\"Customer\"}"))
                .andExpect(status().isCreated());   // two separate identity tables: no clash

        staffLogin(shared, STAFF_PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.staff.staffCode").exists());
        customerLogin(shared, PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.user.accountCode").exists());
        staffLogin(shared, PASSWORD).andExpect(status().isUnauthorized());
        customerLogin(shared, STAFF_PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void staffPasswordsAreStoredOnlyAsBcryptHashes() throws Exception {
        StaffAccount staff = registerStaff(SupportRole.ADMIN);
        String stored = jdbc.queryForObject("SELECT password_hash FROM support_staff WHERE id = ?", String.class, staff.id());
        assertThat(stored).startsWith("$2").doesNotContain(STAFF_PASSWORD);
    }

    // ------------------------------------------------------------------ neither token works on the other API

    @Test
    void aCustomerTokenIsNotAStaffTokenAndAStaffTokenIsNotACustomerToken() throws Exception {
        Account customer = register(SubscriptionPlan.YEARLY);
        StaffAccount staff = registerStaff(SupportRole.ADMIN);

        for (String path : List.of("/api/v1/support/auth/me", "/api/v1/support/dashboard", "/api/v1/support/customers",
                "/api/v1/support/tickets")) {
            mvc.perform(as(customer, get(path))).andExpect(status().isUnauthorized());
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
        for (String path : List.of("/api/v1/auth/me", "/api/v1/handoffs", "/api/v1/handoffs/dashboard", "/api/v1/tickets")) {
            mvc.perform(as(staff, get(path))).andExpect(status().isUnauthorized());
        }
        // Each token still works on its own side.
        mvc.perform(as(customer, get("/api/v1/auth/me"))).andExpect(status().isOk());
        mvc.perform(as(staff, get("/api/v1/support/dashboard"))).andExpect(status().isOk());
    }

    @Test
    void aBadOrMissingTokenGetsNowhere() throws Exception {
        mvc.perform(get("/api/v1/support/dashboard").header("Authorization", "Bearer not-a-real-token"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/support/dashboard").header("Authorization", "Basic abc"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/support/tickets")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/support/auth/me")).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ who may use the support API

    @Test
    void everySupportEndpointIsForStaffOnly() throws Exception {
        Account customer = register(SubscriptionPlan.HALF_YEARLY);
        String ticket = createTicket(customer, "Authorization matrix");
        StaffAccount support = registerStaff(SupportRole.MANAGER);
        StaffAccount admin = registerStaff(SupportRole.ADMIN);

        List<Call> calls = List.of(
                new Call(HttpMethod.GET, "/api/v1/support/auth/me", null),
                new Call(HttpMethod.GET, "/api/v1/support/dashboard", null),
                new Call(HttpMethod.GET, "/api/v1/support/customers", null),
                new Call(HttpMethod.GET, "/api/v1/support/customers/" + customer.accountCode(), null),
                new Call(HttpMethod.PUT, "/api/v1/support/customers/" + customer.accountCode() + "/prefix", "{\"prefix\":\"AV\"}"),
                new Call(HttpMethod.PUT, "/api/v1/support/customers/" + customer.accountCode() + "/plan",
                        "{\"fromPlan\":\"HALF_YEARLY\",\"toPlan\":\"HALF_YEARLY\"}"),
                new Call(HttpMethod.GET, "/api/v1/support/tickets", null),
                new Call(HttpMethod.GET, "/api/v1/support/tickets/" + ticket, null),
                new Call(HttpMethod.PUT, "/api/v1/support/tickets/" + ticket + "/status", "{\"status\":\"IN_PROGRESS\"}"),
                new Call(HttpMethod.POST, "/api/v1/support/tickets/" + ticket + "/messages", "{\"body\":\"On it.\"}"),
                new Call(HttpMethod.GET, "/api/v1/support/tickets/" + ticket + "/attachments/1/content", null));

        for (Call call : calls) {
            String what = call.method() + " " + call.path();
            // Neither anonymous callers nor ANY customer (even the ticket's owner, even one paying for support) get in.
            assertThat(statusOf(call, null)).as(what + " anonymous").isEqualTo(401);
            assertThat(statusOf(call, customer)).as(what + " as a customer").isEqualTo(401);
            // Staff are let through: SUPPORT and ADMIN alike. (The plan change here is a deliberate no-op that is
            // refused with 409, and the attachment URL names a file that does not exist — both prove the request
            // got past authorization.)
            for (StaffAccount staff : List.of(support, admin)) {
                int actual = statusOf(call, staff);
                if (call.path().contains("/attachments/")) {
                    assertThat(actual).as(what + " as staff").isEqualTo(404);
                } else if (call.path().endsWith("/plan")) {
                    assertThat(actual).as(what + " as staff").isEqualTo(409);
                } else {
                    assertThat(actual).as(what + " as staff").isBetween(200, 299);
                }
            }
        }
    }

    @Test
    void aDeactivatedStaffMembersTokenStopsWorkingAtOnce() throws Exception {
        StaffAccount staff = registerStaff(SupportRole.ADMIN);
        mvc.perform(as(staff, get("/api/v1/support/dashboard"))).andExpect(status().isOk());

        deactivate(staff);   // the token is still valid and unexpired — the database says no
        mvc.perform(as(staff, get("/api/v1/support/dashboard"))).andExpect(status().isUnauthorized());
        mvc.perform(as(staff, get("/api/v1/support/auth/me"))).andExpect(status().isUnauthorized());
        staffLogin(staff.email(), STAFF_PASSWORD).andExpect(status().isUnauthorized());
    }

    @Test
    void thePlanPricesArePublicAndCoverEveryPlanInOrder() throws Exception {
        String body = mvc.perform(get("/api/v1/public/plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].plan").value("MONTHLY"))
                .andExpect(jsonPath("$[0].months").value(1))
                .andExpect(jsonPath("$[0].amount").value(199))
                .andExpect(jsonPath("$[0].currency").value("INR"))
                .andExpect(jsonPath("$[1].plan").value("QUARTERLY"))
                .andExpect(jsonPath("$[1].months").value(3))
                .andExpect(jsonPath("$[1].amount").value(549))
                .andExpect(jsonPath("$[2].plan").value("HALF_YEARLY"))
                .andExpect(jsonPath("$[2].months").value(6))
                .andExpect(jsonPath("$[2].amount").value(999))
                .andExpect(jsonPath("$[3].plan").value("YEARLY"))
                .andExpect(jsonPath("$[3].months").value(12))
                .andExpect(jsonPath("$[3].amount").value(1999))
                .andReturn().getResponse().getContentAsString();
        // Prices and what each plan includes from support — never the support phone number, nor anything about customers.
        assertThat(body).doesNotContain(SUPPORT_PHONE, "accountCode", "email");
        // Read-only: nothing can change a price through the API.
        mvc.perform(request(HttpMethod.PUT, "/api/v1/public/plans")).andExpect(status().isUnauthorized());
        mvc.perform(request(HttpMethod.POST, "/api/v1/public/plans")).andExpect(status().isUnauthorized());
    }

    @Test
    void theGeneralContactAddressIsPublicAndNeverIncludesThePhoneNumber() throws Exception {
        String body = mvc.perform(get("/api/v1/public/contact"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(SUPPORT_MAILBOX))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(SUPPORT_PHONE).doesNotContainIgnoringCase("phone");

        // Only that one read-only address is public.
        mvc.perform(request(HttpMethod.POST, "/api/v1/public/contact")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/public/anything-else")).andExpect(status().isUnauthorized());
    }
}
