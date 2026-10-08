package com.handoffly.handoff;

import com.handoffly.support.staff.SupportRole;
import com.handoffly.testsupport.ApiTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Handoff references are numbered per customer (ST-1, ST-2 … under the prefix the customer was given when they registered, or
 * under the one support sets), independent of the internal database ids, and never repeat within a customer.
 */
class HandoffNumberingTest extends ApiTestBase {

    private static String reference(String handoffJson) {
        return JsonPath.read(handoffJson, "$.publicCode");
    }

    private static long id(String handoffJson) {
        return ((Number) JsonPath.read(handoffJson, "$.id")).longValue();
    }

    @Test
    void eachCustomerNumbersTheirOwnHandoffsFromOne() throws Exception {
        Account first = register();
        Account second = register();

        String a1 = createHandoff(first);
        String a2 = createHandoff(first);
        String a3 = createHandoff(first);
        String b1 = createHandoff(second);

        String firstPrefix = prefixOf(first);
        assertThat(firstPrefix).isNotEqualTo(prefixOf(second));   // each customer's own prefix
        assertThat(List.of(reference(a1), reference(a2), reference(a3))).containsExactly(firstPrefix + "-1", firstPrefix + "-2", firstPrefix + "-3");
        // The second customer starts at 1 — not at 4 — so the number is not derived from any global id.
        assertThat(reference(b1)).isEqualTo(prefixOf(second) + "-1");
        // Internal ids stay globally unique; they are what the API addresses a handoff by.
        assertThat(Set.of(id(a1), id(a2), id(a3), id(b1))).hasSize(4);
    }

    @Test
    void thePrefixSupportSetsGivesItsReferencesWhileAnotherCustomerKeepsTheirOwn() throws Exception {
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        Account avCustomer = register();
        Account otherCustomer = register();
        String av = uniquePrefix();
        String theirs = prefixOf(otherCustomer);
        setPrefix(staff, avCustomer, av);

        assertThat(reference(createHandoff(avCustomer))).isEqualTo(av + "-1");
        assertThat(reference(createHandoff(avCustomer))).isEqualTo(av + "-2");
        assertThat(reference(createHandoff(otherCustomer))).isEqualTo(theirs + "-1");
        assertThat(reference(createHandoff(otherCustomer))).isEqualTo(theirs + "-2");
    }

    @Test
    void twoCustomersWhoShareAPrefixFromBeforeTheRuleStillEachReachOnlyTheirOwn() throws Exception {
        Account first = register();
        Account second = register();
        String shared = uniquePrefix();
        shareLegacyPrefix(shared, first, second);   // accounts that existed before prefixes were unique all had HO

        String mine = createHandoff(first);
        String theirs = createHandoff(second);
        assertThat(reference(mine)).isEqualTo(shared + "-1");
        assertThat(reference(theirs)).isEqualTo(shared + "-1");
        assertThat(id(mine)).isNotEqualTo(id(theirs));

        mvc.perform(as(first, get("/api/v1/handoffs/" + id(mine))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.publicCode").value(shared + "-1"));
        mvc.perform(as(first, get("/api/v1/handoffs/" + id(theirs)))).andExpect(status().isForbidden());
        mvc.perform(as(first, get("/api/v1/handoffs").param("q", shared + "-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(id(mine)));
    }

    @Test
    void changingThePrefixLeavesExistingHandoffsAloneAndNeverRestartsTheNumbers() throws Exception {
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        Account customer = register();
        String av = uniquePrefix();
        String rk = uniquePrefix();
        setPrefix(staff, customer, av);
        String h1 = createHandoff(customer);
        String h2 = createHandoff(customer);
        assertThat(List.of(reference(h1), reference(h2))).containsExactly(av + "-1", av + "-2");

        setPrefix(staff, customer, rk);
        assertThat(reference(createHandoff(customer))).isEqualTo(rk + "-3");   // carries on from 2, under the new prefix
        mvc.perform(as(customer, get("/api/v1/handoffs/" + id(h1)))).andExpect(jsonPath("$.publicCode").value(av + "-1"));
        mvc.perform(as(customer, get("/api/v1/handoffs/" + id(h2)))).andExpect(jsonPath("$.publicCode").value(av + "-2"));

        setPrefix(staff, customer, av);   // its own old prefix is free again: no one else took it
        assertThat(reference(createHandoff(customer))).isEqualTo(av + "-4");   // av-1 / av-2 are never handed out again
    }

    @Test
    void concurrentCreationNeverIssuesTheSameNumberTwice() throws Exception {
        Account customer = register();
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return reference(createHandoff(customer));
                }));
            }
            go.countDown();

            Set<String> issued = new TreeSet<>();
            for (Future<String> result : results) {
                issued.add(result.get(60, TimeUnit.SECONDS));
            }
            String own = prefixOf(customer);
            assertThat(issued).containsExactlyInAnyOrder(own + "-1", own + "-2", own + "-3", own + "-4", own + "-5", own + "-6");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void theRecipientEmailLinkAndPdfCarryTheCustomersOwnReference() throws Exception {
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        Account customer = register();
        String av = uniquePrefix();
        setPrefix(staff, customer, av);
        long handoffId = id(createHandoff(customer));

        mvc.perform(as(customer, post("/api/v1/handoffs/" + handoffId + "/submit"))).andExpect(status().isOk());
        assertThat(emailSender.getLastMessage().subject()).contains(av + "-1");

        mvc.perform(get("/api/v1/r/" + emailSender.extractLastToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicCode").value(av + "-1"));

        mvc.perform(as(customer, get("/api/v1/handoffs/" + handoffId + "/pdf")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("HandOffly-" + av + "-1-Proof-of-Handoff.pdf")));
    }

    @Test
    void handoffsStayPrivateToTheirOwnerAndStaffCredentialsOpenNoneOfThem() throws Exception {
        Account owner = register();
        Account otherCustomer = register();
        StaffAccount support = registerStaff(SupportRole.MANAGER);
        StaffAccount admin = registerStaff(SupportRole.ADMIN);
        long handoffId = id(createHandoff(owner));
        String returnBody = "{\"lines\":[{\"itemId\":1,\"quantity\":1}]}";

        // Another customer is refused everything.
        for (String path : List.of("", "/events", "/pdf", "/attachments")) {
            mvc.perform(as(otherCustomer, get("/api/v1/handoffs/" + handoffId + path))).andExpect(status().isForbidden());
        }
        mvc.perform(as(otherCustomer, post("/api/v1/handoffs/" + handoffId + "/returns")
                .contentType(MediaType.APPLICATION_JSON).content(returnBody))).andExpect(status().isForbidden());
        mvc.perform(as(otherCustomer, post("/api/v1/handoffs/" + handoffId + "/cancel"))).andExpect(status().isForbidden());
        mvc.perform(as(otherCustomer, get("/api/v1/handoffs")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));

        // Support staff are a different identity: their token is simply not a customer credential.
        for (StaffAccount staff : List.of(support, admin)) {
            for (String path : List.of("", "/events", "/pdf", "/attachments")) {
                mvc.perform(as(staff, get("/api/v1/handoffs/" + handoffId + path))).andExpect(status().isUnauthorized());
            }
            mvc.perform(as(staff, post("/api/v1/handoffs/" + handoffId + "/returns")
                    .contentType(MediaType.APPLICATION_JSON).content(returnBody))).andExpect(status().isUnauthorized());
            mvc.perform(as(staff, post("/api/v1/handoffs/" + handoffId + "/cancel"))).andExpect(status().isUnauthorized());
            mvc.perform(as(staff, get("/api/v1/handoffs"))).andExpect(status().isUnauthorized());
        }

        // Still the owner's, untouched.
        mvc.perform(as(owner, get("/api/v1/handoffs/" + handoffId)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));
    }

    @Test
    void aRegisteredButHandoffLessCustomerStartsAtOneWhateverOthersHaveDone() throws Exception {
        Account busy = register();
        for (int i = 0; i < 3; i++) {
            createHandoff(busy);
        }
        Account fresh = register();
        mvc.perform(as(fresh, post("/api/v1/handoffs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"First\",\"senderName\":\"S\",\"recipientName\":\"R\",\"recipientEmail\":\"r@example.test\","
                                + "\"items\":[{\"name\":\"Drill\",\"quantity\":1}]}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.publicCode").value(prefixOf(fresh) + "-1"));
    }
}
