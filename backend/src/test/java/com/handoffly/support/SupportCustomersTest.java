package com.handoffly.support;

import com.handoffly.testsupport.ApiTestBase;
import com.handoffly.support.staff.SupportRole;
import com.handoffly.user.SubscriptionPlan;
import com.handoffly.user.User;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** What support can find out about a customer, and the one thing it can change: the handoff prefix. */
class SupportCustomersTest extends ApiTestBase {

    private ResultActions search(StaffAccount staff, String query) throws Exception {
        return mvc.perform(as(staff, get("/api/v1/support/customers").param("q", query)));
    }

    private ResultActions putPrefix(Bearer who, String accountCode, String json) throws Exception {
        return mvc.perform(as(who, put("/api/v1/support/customers/" + accountCode + "/prefix")
                .contentType(MediaType.APPLICATION_JSON).content(json)));
    }

    @Test
    void customersAreFoundByAccountIdNameOrEmailIgnoringCase() throws Exception {
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Account target = register("Zebulon Quux " + suffix, SubscriptionPlan.HALF_YEARLY);
        register();   // someone else, to make sure the search really narrows

        search(staff, target.accountCode())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].accountCode").value(target.accountCode()))
                .andExpect(jsonPath("$.content[0].name").value("Zebulon Quux " + suffix))
                .andExpect(jsonPath("$.content[0].email").value(target.email()))
                .andExpect(jsonPath("$.content[0].phone").value(CUSTOMER_PHONE))
                .andExpect(jsonPath("$.content[0].plan").value("HALF_YEARLY"))
                .andExpect(jsonPath("$.content[0].handoffPrefix").value(prefixOf(target)))
                .andExpect(jsonPath("$.content[0].createdAt").exists());
        search(staff, target.accountCode().toLowerCase()).andExpect(jsonPath("$.totalElements").value(1));
        search(staff, "zEbUlOn quux " + suffix).andExpect(jsonPath("$.totalElements").value(1));
        search(staff, target.email().toUpperCase()).andExpect(jsonPath("$.totalElements").value(1));
        search(staff, "  " + target.email() + "  ").andExpect(jsonPath("$.totalElements").value(1));
        search(staff, "no-such-customer-" + suffix).andExpect(jsonPath("$.totalElements").value(0));

        // With no text, customers are listed (page by page).
        search(staff, "").andExpect(status().isOk()).andExpect(jsonPath("$.totalElements", greaterThan(1)));
        mvc.perform(as(staff, get("/api/v1/support/customers"))).andExpect(status().isOk());
    }

    @Test
    void staffAreAnotherIdentityAndNeverAppearAmongCustomers() throws Exception {
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        StaffAccount admin = registerStaff(SupportRole.ADMIN);

        search(staff, admin.email()).andExpect(jsonPath("$.totalElements").value(0));
        search(staff, admin.staffCode()).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(as(staff, get("/api/v1/support/customers/" + admin.staffCode()))).andExpect(status().isNotFound());
        putPrefix(staff, admin.staffCode(), "{\"prefix\":\"AV\"}").andExpect(status().isNotFound());
    }

    @Test
    void theListIsAlwaysSortedByAccountIdAndPagesAreCapped() throws Exception {
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        register();
        register();

        // A caller-chosen sort (even by a field that must stay private) is ignored.
        for (String sort : List.of("accountCode,desc", "passwordHash,asc", "nonsense")) {
            String body = mvc.perform(as(staff, get("/api/v1/support/customers").param("sort", sort)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            List<String> codes = JsonPath.read(body, "$.content[*].accountCode");
            assertThat(codes).isSortedAccordingTo(String::compareTo);
        }
        mvc.perform(as(staff, get("/api/v1/support/customers").param("size", "1000")))
                .andExpect(jsonPath("$.size").value(100));
        mvc.perform(as(staff, get("/api/v1/support/customers").param("size", "3").param("page", "0")))
                .andExpect(jsonPath("$.size").value(3))
                .andExpect(jsonPath("$.content.length()").value(3));
    }

    @Test
    void theProfileShowsWhatSupportNeedsAndNothingMore() throws Exception {
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        Account customer = register("Priya Nair", SubscriptionPlan.YEARLY);
        String handoff = createHandoff(customer);

        String body = mvc.perform(as(staff, get("/api/v1/support/customers/" + customer.accountCode())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.accountCode").value(customer.accountCode()))
                .andExpect(jsonPath("$.customer.name").value("Priya Nair"))
                .andExpect(jsonPath("$.customer.email").value(customer.email()))
                .andExpect(jsonPath("$.customer.phone").value(CUSTOMER_PHONE))
                .andExpect(jsonPath("$.customer.plan").value("YEARLY"))
                .andExpect(jsonPath("$.customer.handoffPrefix").value(prefixOf(customer)))
                .andExpect(jsonPath("$.customer.createdAt").exists())
                // Entitlements are shown, derived from the plan. The support phone is the customer's to see, not this page's.
                .andExpect(jsonPath("$.entitlements.contactSupport").value(true))
                .andExpect(jsonPath("$.entitlements.ticket").value(true))
                .andExpect(jsonPath("$.entitlements.call").value(true))
                .andExpect(jsonPath("$.entitlements.supportPhone").doesNotExist())
                .andExpect(jsonPath("$.nextHandoffReference").value(prefixOf(customer) + "-2"))
                .andExpect(jsonPath("$.openTickets").value(0))
                .andExpect(jsonPath("$.recentTickets.length()").value(0))
                .andReturn().getResponse().getContentAsString();

        // No secrets, no role, and none of the customer's handoff data.
        assertThat(body).doesNotContain("passwordHash", "password", "\"role\"", "Laptop handout", "hire@example.test",
                JsonPath.read(handoff, "$.publicCode").toString() + "\"");
    }

    @Test
    void theProfileCountsOpenTicketsAndListsTheFiveMostRecent() throws Exception {
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        Account customer = register(SubscriptionPlan.HALF_YEARLY);
        String first = null;
        String last = null;
        for (int i = 1; i <= 6; i++) {
            last = createTicket(customer, "Ticket " + i);
            if (first == null) first = last;
        }
        mvc.perform(as(staff, get("/api/v1/support/customers/" + customer.accountCode())))
                .andExpect(jsonPath("$.openTickets").value(6))
                .andExpect(jsonPath("$.recentTickets.length()").value(5))
                .andExpect(jsonPath("$.recentTickets[0].ticketCode").value(last))
                .andExpect(jsonPath("$.recentTickets[*].ticketCode", not(hasItem(first))));

        // Resolved and closed tickets no longer count as open.
        mvc.perform(as(staff, put("/api/v1/support/tickets/" + first + "/status")
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"RESOLVED\"}"))).andExpect(status().isOk());
        mvc.perform(as(staff, put("/api/v1/support/tickets/" + last + "/status")
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"CLOSED\"}"))).andExpect(status().isOk());
        mvc.perform(as(staff, get("/api/v1/support/customers/" + customer.accountCode())))
                .andExpect(jsonPath("$.openTickets").value(4));
    }

    @Test
    void supportSetsAValidPrefixAndOnlyNewHandoffsUseIt() throws Exception {
        StaffAccount staff = registerStaff(SupportRole.ADMIN);
        Account customer = register();
        String original = prefixOf(customer);
        String existing = createHandoff(customer);
        String av = uniquePrefix();
        String longest = uniquePrefix() + "X";

        putPrefix(staff, customer.accountCode(), "{\"prefix\":\"" + av + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.handoffPrefix").value(av))
                .andExpect(jsonPath("$.nextHandoffReference").value(av + "-2"));
        // Setting it again to the same value is harmless.
        putPrefix(staff, customer.accountCode(), "{\"prefix\":\"" + av + "\"}").andExpect(status().isOk());
        // The longest allowed.
        putPrefix(staff, customer.accountCode(), "{\"prefix\":\"" + longest + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.customer.handoffPrefix").value(longest));

        long existingId = ((Number) JsonPath.read(existing, "$.id")).longValue();
        mvc.perform(as(customer, get("/api/v1/handoffs/" + existingId)))
                .andExpect(jsonPath("$.publicCode").value(original + "-1"));   // issued under the prefix it had: never rewritten
    }

    @Test
    void invalidPrefixesAreRefusedAndChangeNothing() throws Exception {
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        Account customer = register();
        String original = prefixOf(customer);

        for (String invalid : List.of("", " ", "A", "ABCDEF", "av", "Av", "A1", "12", "A-B", "A_B", "A B", " AV", "AV ",
                "AV\\n", "ÄÖ", "<b>", "../")) {
            putPrefix(staff, customer.accountCode(), "{\"prefix\":\"" + invalid + "\"}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("validation_failed"))
                    .andExpect(jsonPath("$.errors[0].field").value("prefix"));
        }
        putPrefix(staff, customer.accountCode(), "{}").andExpect(status().isBadRequest());
        putPrefix(staff, customer.accountCode(), "{\"prefix\":null}").andExpect(status().isBadRequest());
        putPrefix(staff, customer.accountCode(), "{\"prefix\":").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("malformed_request"));
        putPrefix(staff, customer.accountCode(), "").andExpect(status().isBadRequest());

        mvc.perform(as(staff, get("/api/v1/support/customers/" + customer.accountCode())))
                .andExpect(jsonPath("$.customer.handoffPrefix").value(original));
        putPrefix(staff, "CUS-999999", "{\"prefix\":\"AV\"}").andExpect(status().isNotFound());
    }

    @Test
    void customersCannotSetTheirOwnPrefix() throws Exception {
        Account customer = register();
        String original = prefixOf(customer);
        // A customer token is not a support credential at all.
        putPrefix(customer, customer.accountCode(), "{\"prefix\":\"AV\"}").andExpect(status().isUnauthorized());
        assertThat(users.findById(customer.id()).orElseThrow().getHandoffPrefix()).isEqualTo(original);
    }

    @Test
    void supportCannotTouchLoginDetailsOrImpersonateAndTheOnlyPlanRouteIsTheExplicitOne() throws Exception {
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        Account customer = register();
        String code = customer.accountCode();

        // Extra fields on the prefix request are simply ignored: it cannot be used to change anything else.
        putPrefix(staff, code, "{\"prefix\":\"" + uniquePrefix() + "\",\"plan\":\"YEARLY\",\"subscriptionPlan\":\"YEARLY\",\"role\":\"ADMIN\","
                + "\"password\":\"hacked-123\",\"enabled\":false,\"email\":\"takeover@example.test\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.plan").value("MONTHLY"));
        User stored = users.findById(customer.id()).orElseThrow();
        assertThat(stored.getSubscriptionPlan()).isEqualTo(SubscriptionPlan.MONTHLY);
        assertThat(stored.isEnabled()).isTrue();
        assertThat(stored.getEmail()).isEqualTo(customer.email());
        assertThat(login(customer.email()).accountCode()).isEqualTo(code);   // the old password still works

        // And no endpoint exists for the rest (a plan changes only through the explicit, audited plan route).
        mvc.perform(as(staff, put("/api/v1/support/customers/" + code)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"plan\":\"YEARLY\"}")))
                .andExpect(status().isMethodNotAllowed());
        for (String action : List.of("subscription", "role", "password", "entitlements", "impersonate", "login-as", "enabled")) {
            for (HttpMethod method : List.of(HttpMethod.PUT, HttpMethod.POST, HttpMethod.PATCH)) {
                int result = mvc.perform(as(staff, request(method, "/api/v1/support/customers/" + code + "/" + action)
                                .contentType(MediaType.APPLICATION_JSON).content("{\"plan\":\"YEARLY\"}")))
                        .andReturn().getResponse().getStatus();
                assertThat(result).as(method + " ." + action).isIn(404, 405);   // nothing is mapped there
            }
        }
        assertThat(users.findById(customer.id()).orElseThrow().getSubscriptionPlan()).isEqualTo(SubscriptionPlan.MONTHLY);
    }

    @Test
    void aCompleteAccountIdFindsExactlyThatCustomerWhileAPartialOneStillSearches() throws Exception {
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        Account target = register();
        for (int i = 0; i < 12; i++) {
            register();   // enough other accounts that a short ID is a prefix of other IDs somewhere in the suite
        }
        search(staff, target.accountCode()).andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].accountCode").value(target.accountCode()));
        search(staff, target.accountCode().toLowerCase()).andExpect(jsonPath("$.totalElements").value(1));
        search(staff, "CUS-999999").andExpect(jsonPath("$.totalElements").value(0));   // a complete ID that does not exist
        search(staff, "CUS-").andExpect(jsonPath("$.totalElements", greaterThan(10)));   // a partial one is "contains"
    }
}
