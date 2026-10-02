package com.handoffly.user;

import com.handoffly.testsupport.ApiTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Account ID, default role and plan, and what each plan entitles a customer to — over the real API. */
class AccountIdentityTest extends ApiTestBase {

    private static long number(String accountCode) {
        return Long.parseLong(accountCode.substring("CUS-".length()));
    }

    @Test
    void everyNewAccountGetsItsOwnStableAccountId() throws Exception {
        Account first = register();
        Account second = register();

        assertThat(first.accountCode()).matches("CUS-\\d{2,}");
        assertThat(second.accountCode()).matches("CUS-\\d{2,}").isNotEqualTo(first.accountCode());
        assertThat(number(second.accountCode())).isEqualTo(number(first.accountCode()) + 1);   // no gaps, no reuse

        // The ID never changes: not on later reads, not on a later login.
        mvc.perform(as(first, get("/api/v1/auth/me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountCode").value(first.accountCode()));
        assertThat(login(first.email()).accountCode()).isEqualTo(first.accountCode());
    }

    @Test
    void aFailedRegistrationDoesNotUseUpAnAccountId() throws Exception {
        Account first = register();
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + first.email() + "\",\"password\":\"" + PASSWORD + "\",\"displayName\":\"Again\"}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\",\"password\":\"" + PASSWORD + "\",\"displayName\":\"Bad\"}"))
                .andExpect(status().isBadRequest());

        assertThat(number(register().accountCode())).isEqualTo(number(first.accountCode()) + 1);
    }

    @Test
    void registrationOnlyEverCreatesACustomerWithNoPlanWhateverTheRequestSays() throws Exception {
        long staffBefore = jdbc.queryForObject("SELECT COUNT(*) FROM support_staff", Long.class);
        String email = "sneaky-" + System.nanoTime() + "@example.test";
        MvcResult res = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"displayName\":\"Sneaky\","
                                + "\"role\":\"ADMIN\",\"staff\":true,\"plan\":\"YEARLY\",\"subscriptionPlan\":\"YEARLY\","
                                + "\"accountCode\":\"CUS-999999\",\"enabled\":true,\"handoffPrefix\":\"ZZ\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.role").doesNotExist())   // a customer has no role at all
                .andExpect(jsonPath("$.user.plan").isEmpty())   // registering activates nothing, however the request asks
                .andExpect(jsonPath("$.user.subscription.status").value("INACTIVE"))
                .andReturn();

        String accountCode = JsonPath.read(res.getResponse().getContentAsString(), "$.user.accountCode");
        assertThat(accountCode).isNotEqualTo("CUS-999999");
        User saved = users.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(saved.getSubscriptionPlan()).isNull();
        assertThat(saved.getPlanStartedAt()).isNull();
        assertThat(saved.getPlanValidUntil()).isNull();
        assertThat(saved.getHandoffPrefix()).isEqualTo("HO");

        // No staff identity came out of it, and these credentials are not accepted by the support login.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM support_staff", Long.class)).isEqualTo(staffBefore);
        mvc.perform(post("/api/v1/support/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void theRegistrationPhoneIsOptionalAndKept() throws Exception {
        mvc.perform(as(register(), get("/api/v1/auth/me")))
                .andExpect(jsonPath("$.phone").value(CUSTOMER_PHONE));

        MvcResult res = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"nophone-" + System.nanoTime() + "@example.test\",\"password\":\"" + PASSWORD
                                + "\",\"displayName\":\"No Phone\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.phone").doesNotExist())
                .andReturn();
        assertThat(res.getResponse().getContentAsString()).doesNotContain(PASSWORD).doesNotContain("passwordHash");
    }

    @Test
    void monthlyCustomersHaveNoSupportEntitlementsAndNeverSeeThePhoneNumber() throws Exception {
        Account monthly = register();
        mvc.perform(as(monthly, get("/api/v1/auth/me")))
                .andExpect(jsonPath("$.plan").value("MONTHLY"))
                .andExpect(jsonPath("$.support.contactSupport").value(false))
                .andExpect(jsonPath("$.support.ticket").value(false))
                .andExpect(jsonPath("$.support.call").value(false))
                .andExpect(jsonPath("$.support.supportPhone").doesNotExist());
    }

    @Test
    void halfYearlyCustomersGetContactSupportAndTicketsButNoCall() throws Exception {
        Account halfYearly = register(SubscriptionPlan.HALF_YEARLY);
        mvc.perform(as(halfYearly, get("/api/v1/auth/me")))
                .andExpect(jsonPath("$.plan").value("HALF_YEARLY"))
                .andExpect(jsonPath("$.support.contactSupport").value(true))
                .andExpect(jsonPath("$.support.ticket").value(true))
                .andExpect(jsonPath("$.support.call").value(false))
                .andExpect(jsonPath("$.support.supportPhone").doesNotExist());
    }

    @Test
    void yearlyCustomersGetEverythingIncludingTheSupportPhoneNumber() throws Exception {
        Account yearly = register(SubscriptionPlan.YEARLY);
        mvc.perform(as(yearly, get("/api/v1/auth/me")))
                .andExpect(jsonPath("$.plan").value("YEARLY"))
                .andExpect(jsonPath("$.support.contactSupport").value(true))
                .andExpect(jsonPath("$.support.ticket").value(true))
                .andExpect(jsonPath("$.support.call").value(true))
                .andExpect(jsonPath("$.support.supportPhone").value(SUPPORT_PHONE));
    }

    @Test
    void entitlementsFollowThePlanTheMomentItChanges() throws Exception {
        Account customer = register(SubscriptionPlan.YEARLY);
        setPlan(customer, SubscriptionPlan.MONTHLY);
        mvc.perform(as(customer, get("/api/v1/auth/me")))
                .andExpect(jsonPath("$.support.contactSupport").value(false))
                .andExpect(jsonPath("$.support.supportPhone").doesNotExist());
    }
}
