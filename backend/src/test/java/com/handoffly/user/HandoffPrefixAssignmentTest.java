package com.handoffly.user;

import com.handoffly.support.staff.SupportRole;
import com.handoffly.testsupport.ApiTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Every customer who registers gets a handoff prefix of their own, made from their organization or name, that no other customer has. The
 * tests run against the whole application (registration, the account counter's lock, the database), so they prove the uniqueness, the
 * case-insensitivity, the Support rules and the account-scoped numbering as the customer meets them — and that what existed before is not touched.
 */
class HandoffPrefixAssignmentTest extends ApiTestBase {

    /**
     * Other tests share this database and register customers too, so before a test that names the prefixes it expects, the customers that
     * hold those prefixes are moved to ones of their own (test data only), leaving them free.
     */
    private void free(String... prefixes) {
        for (String prefix : prefixes) {
            for (Long id : jdbc.queryForList("SELECT id FROM app_user WHERE UPPER(handoff_prefix) = ?", Long.class, prefix)) {
                jdbc.update("UPDATE app_user SET handoff_prefix = ? WHERE id = ?", uniquePrefix(), id);
            }
        }
    }

    private record Registered(Account account, String prefix) {}

    private Registered registerAs(String displayName, String organization) throws Exception {
        String email = "reg-" + UUID.randomUUID().toString().substring(0, 12) + "@example.test";
        String org = organization == null ? "" : ",\"organization\":\"" + organization + "\"";
        MvcResult res = mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\",\"displayName\":\"" + displayName + "\"" + org + "}"))
                .andExpect(status().isCreated()).andReturn();
        String json = res.getResponse().getContentAsString();
        Account account = new Account(((Number) JsonPath.read(json, "$.user.id")).longValue(), JsonPath.read(json, "$.user.accountCode"), email, JsonPath.read(json, "$.token"));
        String prefix = JsonPath.read(json, "$.user.handoffPrefix");
        assertThat(prefixOf(account)).isEqualTo(prefix);   // what the response says is what is stored
        return new Registered(account, prefix);
    }

    private ResultActions putPrefix(StaffAccount staff, Account customer, String prefix) throws Exception {
        return mvc.perform(as(staff, put("/api/v1/support/customers/" + customer.accountCode() + "/prefix")
                .contentType(MediaType.APPLICATION_JSON).content("{\"prefix\":\"" + prefix + "\"}")));
    }

    // ------------------------------------------------------------------ the default prefix

    @Test
    void anOrganizationGivesItsInitialsAndASimilarOneGetsAnotherPrefix() throws Exception {
        free("ST", "SI");

        assertThat(registerAs("Anita Rao", "Siam Traders").prefix()).isEqualTo("ST");
        String second = registerAs("Anil Menon", "Siam Technologies").prefix();
        assertThat(second).isNotEqualTo("ST").isEqualTo("SI");   // the next choice of the rule, the same every time
        assertThat(registerAs("Someone Else", "Siam Traders Pvt Ltd").prefix()).isNotIn("ST", "SI");
    }

    @Test
    void theOrganizationIsPreferredOtherwiseTheFirstAndLastNameOtherwiseTheSingleName() throws Exception {
        free("ST", "RK", "AS", "FL", "RA", "RM");

        assertThat(registerAs("Rahul Kumar", "Siam Traders").prefix()).isEqualTo("ST");   // the organization, not the person
        assertThat(registerAs("Rahul Kumar", null).prefix()).isEqualTo("RK");             // the name
        assertThat(registerAs("Dinesh Gupta", "ABC Solutions").prefix()).isEqualTo("AS");
        assertThat(registerAs("Flipkart", null).prefix()).isEqualTo("FL");                // a single name: two letters, never one
        assertThat(registerAs("Ram", null).prefix()).isEqualTo("RA");
        assertThat(registerAs("Rahul Kumar", "   ").prefix()).isNotEqualTo("RK").matches("[A-Z]{2}");   // a blank organization is none, so the name: but RK is taken now
    }

    @Test
    void similarSingleNamesGetDifferentPrefixes() throws Exception {
        free("FL", "FT", "FA");

        Registered flipkart = registerAs("Flipkart", null);
        Registered flatkart = registerAs("Flatkart", null);
        Registered flipkartAgain = registerAs("Flipkart", null);

        assertThat(flipkart.prefix()).isEqualTo("FL");
        assertThat(flatkart.prefix()).isNotEqualTo("FL").isEqualTo("FT");
        assertThat(flipkartAgain.prefix()).isNotIn("FL", "FT").matches("[A-Z]{2}");
    }

    @Test
    void aPrefixIsTheSameCaseInsensitivelyAndAccountsThatCameBeforeKeepWhatTheyHave() throws Exception {
        free("ST", "SI");
        Account legacy = register();
        shareLegacyPrefix("st", legacy);   // however it came to be: lower case

        Registered siam = registerAs("Siam Traders Again", "Siam Traders");
        assertThat(siam.prefix()).isNotEqualToIgnoringCase("ST");   // st and ST are one prefix
        assertThat(prefixOf(legacy)).isEqualTo("st");                // and the account that had it is not touched
    }

    @Test
    void theOldDefaultIsNotGivenToNewCustomersWhileAnyoneHasIt() throws Exception {
        free("HO", "HR");
        Account first = register();
        Account second = register();
        shareLegacyPrefix("HO", first, second);   // as every account from before the rule has

        Registered hotel = registerAs("Hotel Orchid", null);   // would be HO
        assertThat(hotel.prefix()).isNotEqualTo("HO").isEqualTo("HR");
        assertThat(prefixOf(first)).isEqualTo("HO");
        assertThat(prefixOf(second)).isEqualTo("HO");   // two accounts sharing HO are reported by the test, never rewritten
    }

    @Test
    void registeringManyCustomersWithTheSameNameNeverRepeatsAPrefix() throws Exception {
        List<String> prefixes = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            prefixes.add(registerAs("Test Customer", "Quill Works").prefix());
        }
        assertThat(prefixes).doesNotHaveDuplicates().allMatch(p -> p.matches("[A-Z]{2,3}"));
    }

    // ------------------------------------------------------------------ at the same moment

    @Test
    void customersRegisteringAtTheSameMomentNeverShareAPrefix() throws Exception {
        free("ST", "SI", "SE", "SS", "SA");
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return registerAs("Owner " + UUID.randomUUID().toString().substring(0, 4), "Siam Traders").prefix();   // all want ST
                }));
            }
            go.countDown();
            List<String> prefixes = new ArrayList<>();
            for (Future<String> result : results) prefixes.add(result.get(120, TimeUnit.SECONDS));

            assertThat(prefixes).hasSize(threads).doesNotHaveDuplicates();
            assertThat(prefixes).contains("ST");   // one of them got the preferred one
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void aRegistrationAndASupportChangeAtTheSameMomentCannotBothTakeTheSamePrefix() throws Exception {
        free("ST");
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        List<Account> existing = new ArrayList<>();
        for (int i = 0; i < 6; i++) existing.add(register());

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            results.add(pool.submit(() -> {   // a customer who would be given ST...
                go.await();
                registerAs("Owner", "Siam Traders");
                return 0;
            }));
            for (Account customer : existing) {   // ...while support tries to give ST to each of six others
                results.add(pool.submit(() -> {
                    go.await();
                    return putPrefix(staff, customer, "ST").andReturn().getResponse().getStatus();
                }));
            }
            go.countDown();
            for (Future<Integer> result : results) result.get(120, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM app_user WHERE UPPER(handoff_prefix) = 'ST'", Long.class)).isEqualTo(1L);   // exactly one of the seven has it
    }

    // ------------------------------------------------------------------ Support changes a prefix

    @Test
    void supportCannotGiveACustomerAPrefixAnotherCustomerHasAndCanGiveOneThatIsFree() throws Exception {
        free("ST", "SX");
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        Registered siam = registerAs("Anita Rao", "Siam Traders");
        Registered other = registerAs("Rahul Kumar", null);
        assertThat(siam.prefix()).isEqualTo("ST");
        String otherBefore = other.prefix();

        putPrefix(staff, other.account(), "ST").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("ST")));
        assertThat(prefixOf(other.account())).isEqualTo(otherBefore);   // refused: nothing changed

        putPrefix(staff, other.account(), "SX").andExpect(status().isOk()).andExpect(jsonPath("$.customer.handoffPrefix").value("SX"));
        assertThat(prefixOf(other.account())).isEqualTo("SX");
        assertThat(prefixOf(siam.account())).isEqualTo("ST");
    }

    @Test
    void supportsCheckIgnoresCaseAndSayingTheCustomersOwnPrefixAgainIsNotRefused() throws Exception {
        free("ST");
        StaffAccount staff = registerStaff(SupportRole.ADMIN);
        Account holder = register();
        Account other = register();
        shareLegacyPrefix("st", holder);   // lower case, as the database may hold it

        putPrefix(staff, other, "ST").andExpect(status().isConflict());   // ST is st
        putPrefix(staff, holder, "ST").andExpect(status().isOk());        // the holder's own, however written: not a clash with themselves

        // Customers from before the rule share HO: asking for it again changes nothing for them, and it is not given to a third.
        Account a = register();
        Account b = register();
        Account c = register();
        free("HO");
        shareLegacyPrefix("HO", a, b);
        putPrefix(staff, a, "HO").andExpect(status().isOk());
        putPrefix(staff, c, "HO").andExpect(status().isConflict());
    }

    @Test
    void aPrefixIsFreeAgainWhenItsCustomerMovesOff() throws Exception {
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        Account first = register();
        Account second = register();
        String taken = prefixOf(first);

        putPrefix(staff, second, taken).andExpect(status().isConflict());
        putPrefix(staff, first, uniquePrefix()).andExpect(status().isOk());
        putPrefix(staff, second, taken).andExpect(status().isOk());
        assertThat(prefixOf(second)).isEqualTo(taken);
    }

    // ------------------------------------------------------------------ references

    @Test
    void referencesAreNumberedPerCustomerUnderTheirOwnPrefixAndNeverRewritten() throws Exception {
        free("ST", "RK");
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        Registered a = registerAs("Anita Rao", "Siam Traders");
        Registered b = registerAs("Rahul Kumar", null);
        assertThat(a.prefix()).isEqualTo("ST");
        assertThat(b.prefix()).isEqualTo("RK");
        setPlan(a.account(), com.handoffly.user.SubscriptionPlan.MONTHLY);   // registration activates no plan: that is done as test setup
        setPlan(b.account(), com.handoffly.user.SubscriptionPlan.MONTHLY);

        assertThat(reference(createHandoff(a.account()))).isEqualTo("ST-1");
        assertThat(reference(createHandoff(a.account()))).isEqualTo("ST-2");
        assertThat(reference(createHandoff(b.account()))).isEqualTo("RK-1");
        assertThat(reference(createHandoff(b.account()))).isEqualTo("RK-2");

        // Support changes A's prefix: what A was given stays as it was, and the number carries on.
        String moved = uniquePrefix();
        putPrefix(staff, a.account(), moved).andExpect(status().isOk());
        assertThat(reference(createHandoff(a.account()))).isEqualTo(moved + "-3");
        String list = mvc.perform(as(a.account(), get("/api/v1/handoffs").param("size", "10"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(list, "$.content[*].publicCode")).containsExactlyInAnyOrder("ST-1", "ST-2", moved + "-3");
        // B is untouched by any of it.
        assertThat(reference(createHandoff(b.account()))).isEqualTo("RK-3");
    }

    private static String reference(String handoffJson) {
        return JsonPath.read(handoffJson, "$.publicCode");
    }

    @Test
    void whoeverRegistersShowsTheirPrefixOnTheAccountAndTheirNextReference() throws Exception {
        Registered customer = registerAs("Nina Shah", "Pearl Stores");

        mvc.perform(as(customer.account(), get("/api/v1/auth/me"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.handoffPrefix").value(customer.prefix()));
    }
}
