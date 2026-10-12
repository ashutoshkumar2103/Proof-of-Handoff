package com.handoffly.handoff;

import com.handoffly.support.staff.SupportRole;
import com.handoffly.testsupport.ApiTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A draft has a reference of its own (AKD-1, AKD-2 ...: the account's prefix, D for draft), from the account's own draft counter. It is
 * kept apart from the official reference (AK-1 ...), which is still issued only when the draft is first submitted: drafts never use up an
 * official number, and a draft's reference never becomes its official one.
 */
class HandoffDraftReferenceTest extends ApiTestBase {

    private long newDraft(Account owner) throws Exception {
        return ((Number) JsonPath.read(createHandoff(owner), "$.id")).longValue();
    }

    private String detail(Account owner, long id) throws Exception {
        return mvc.perform(as(owner, get("/api/v1/handoffs/" + id))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String draftCode(Account owner, long id) throws Exception {
        return JsonPath.read(detail(owner, id), "$.draftCode");
    }

    private long handoffSequence(Account owner) {
        return jdbc.queryForObject("SELECT handoff_sequence FROM app_user WHERE id = ?", Long.class, owner.id());
    }

    private long draftSequence(Account owner) {
        return jdbc.queryForObject("SELECT draft_sequence FROM app_user WHERE id = ?", Long.class, owner.id());
    }

    private void submit(Account owner, long id) throws Exception {
        mvc.perform(as(owner, post("/api/v1/handoffs/" + id + "/submit"))).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ the reference a draft is given

    @Test
    void draftsAreNamedByTheAccountsPrefixThenDAndANumberOfTheirOwn() throws Exception {
        Account a = register();
        String prefix = prefixOf(a);

        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            codes.add(draftCode(a, newDraft(a)));
        }

        assertThat(codes).containsExactly(prefix + "D-1", prefix + "D-2", prefix + "D-3");
        assertThat(new HashSet<>(codes)).hasSize(3);   // several drafts of one customer can be told apart
    }

    @Test
    void aDraftKeepsItsReferenceWhateverIsDoneToIt() throws Exception {
        Account a = register();
        long id = newDraft(a);
        String code = draftCode(a, id);

        mvc.perform(as(a, patch("/api/v1/handoffs/" + id).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Renamed\",\"senderName\":\"S\",\"recipientName\":\"R\",\"recipientEmail\":\"r@example.test\"}")))
                .andExpect(status().isOk());
        mvc.perform(as(a, put("/api/v1/handoffs/" + id + "/items").contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[{\"name\":\"Chair\",\"quantity\":4}]}"))).andExpect(status().isOk());

        assertThat(draftCode(a, id)).isEqualTo(code);
        assertThat(draftCode(a, id)).isEqualTo(code);   // and every time it is read
    }

    @Test
    void twoCustomersNeverGetTheSameCompleteDraftReference() throws Exception {
        Account a = register();
        Account b = register();
        Set<String> all = new TreeSet<>();
        for (int i = 0; i < 4; i++) {
            all.add(draftCode(a, newDraft(a)));
            all.add(draftCode(b, newDraft(b)));
        }

        assertThat(prefixOf(a)).isNotEqualTo(prefixOf(b));   // every customer has a prefix of their own
        assertThat(all).hasSize(8);                          // so even the same draft number reads differently
        assertThat(all.stream().filter(c -> c.startsWith(prefixOf(a) + "D-"))).hasSize(4);
        assertThat(all.stream().filter(c -> c.startsWith(prefixOf(b) + "D-"))).hasSize(4);
    }

    @Test
    void aDraftReferenceIsNeverTheTextOfAnOfficialReference() throws Exception {
        Account a = register();
        long id = newDraft(a);
        submit(a, id);

        String json = detail(a, id);
        String official = JsonPath.read(json, "$.publicCode");
        String draft = JsonPath.read(json, "$.draftCode");

        assertThat(official).isEqualTo(prefixOf(a) + "-1");
        assertThat(draft).isEqualTo(prefixOf(a) + "D-1");
        assertThat(official).isNotEqualTo(draft);
    }

    @Test
    void aDraftReferenceThatWouldReadAsAnOfficialOneOfTheSameAccountIsSkippedNotReused() throws Exception {
        // An account whose official references were once AKD-n (its prefix was AKD before support changed it to AK) must not be given a draft
        // that reads as one of them.
        Account a = register();
        shareLegacyPrefix("AKD", a);
        long old = newDraft(a);
        submit(a, old);   // official AKD-1, its draft reference AKDD-1
        jdbc.update("UPDATE app_user SET handoff_prefix = 'AK' WHERE id = ?", a.id());
        jdbc.update("UPDATE app_user SET draft_sequence = 0 WHERE id = ?", a.id());   // the next draft number would be 1: AKD-1, the old official one

        String code = draftCode(a, newDraft(a));

        assertThat(code).isEqualTo("AKD-2");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM handoff WHERE owner_user_id = ? AND public_code = ?", Integer.class, a.id(), "AKD-1")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ separate from the official numbers

    @Test
    void creatingEditingDuplicatingImportingAndDeletingDraftsNeverUsesAnOfficialNumber() throws Exception {
        Account a = register();
        assertThat(handoffSequence(a)).isZero();

        long first = newDraft(a);
        mvc.perform(as(a, patch("/api/v1/handoffs/" + first).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Edited\",\"senderName\":\"S\",\"recipientName\":\"R\",\"recipientEmail\":\"r@example.test\"}")))
                .andExpect(status().isOk());
        mvc.perform(as(a, put("/api/v1/handoffs/" + first + "/items").contentType(MediaType.APPLICATION_JSON)   // importing items lands here
                .content("{\"items\":[{\"name\":\"Chair\",\"quantity\":4},{\"name\":\"Table\",\"quantity\":1}]}"))).andExpect(status().isOk());

        // Duplicating: reading the template is read-only and issues nothing; the copy is an ordinary create.
        mvc.perform(as(a, get("/api/v1/handoffs/" + first + "/template"))).andExpect(status().isOk());
        assertThat(draftSequence(a)).isEqualTo(1);
        long copy = ((Number) JsonPath.read(mvc.perform(as(a, post("/api/v1/handoffs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Copy\",\"senderName\":\"S\",\"recipientName\":\"R\",\"recipientEmail\":\"r@example.test\","
                        + "\"items\":[{\"name\":\"Chair\",\"quantity\":4},{\"name\":\"Table\",\"quantity\":1}]}")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id")).longValue();
        assertThat(draftCode(a, copy)).isEqualTo(prefixOf(a) + "D-2");

        mvc.perform(as(a, delete("/api/v1/handoffs/" + copy))).andExpect(status().isNoContent());

        assertThat(handoffSequence(a)).isZero();   // not one official number was used
        assertThat(draftSequence(a)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM handoff WHERE owner_user_id = ? AND public_code IS NOT NULL", Integer.class, a.id())).isZero();
    }

    @Test
    void submittingUsesTheOfficialNumberAndLeavesTheDraftNumbersAlone() throws Exception {
        Account a = register();
        String prefix = prefixOf(a);
        long one = newDraft(a);
        long two = newDraft(a);
        long three = newDraft(a);

        submit(a, two);   // submitted out of order: the official number follows the submission, not the draft
        submit(a, one);

        assertThat((String) JsonPath.read(detail(a, two), "$.publicCode")).isEqualTo(prefix + "-1");
        assertThat((String) JsonPath.read(detail(a, one), "$.publicCode")).isEqualTo(prefix + "-2");
        assertThat((String) JsonPath.read(detail(a, one), "$.draftCode")).isEqualTo(prefix + "D-1");   // kept for the record, not the reference
        assertThat(handoffSequence(a)).isEqualTo(2);
        assertThat(draftSequence(a)).isEqualTo(3);
        assertThat((Object) JsonPath.read(detail(a, three), "$.publicCode")).isNull();   // the third is still a draft, still unnumbered
    }

    @Test
    void aDeletedDraftsReferenceIsNeverGivenToAnotherDraft() throws Exception {
        Account a = register();
        String prefix = prefixOf(a);
        newDraft(a);
        long second = newDraft(a);
        assertThat(draftCode(a, second)).isEqualTo(prefix + "D-2");

        mvc.perform(as(a, delete("/api/v1/handoffs/" + second))).andExpect(status().isNoContent());
        long third = newDraft(a);

        assertThat(draftCode(a, third)).isEqualTo(prefix + "D-3");   // not D-2 again
        // The deleted draft's row, with its reference, is kept (its audit history points at it).
        assertThat(jdbc.queryForObject("SELECT draft_code FROM handoff WHERE id = ?", String.class, second)).isEqualTo(prefix + "D-2");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE handoff_id = ?", Integer.class, second)).isGreaterThanOrEqualTo(2);
    }

    @Test
    void concurrentDraftCreationNeverIssuesTheSameReferenceTwice() throws Exception {
        Account a = register();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return JsonPath.read(createHandoff(a), "$.draftCode");
                }));
            }
            go.countDown();

            Set<String> issued = new TreeSet<>();
            for (Future<String> result : results) {
                issued.add(result.get(60, TimeUnit.SECONDS));
            }
            String p = prefixOf(a);
            assertThat(issued).containsExactlyInAnyOrder(p + "D-1", p + "D-2", p + "D-3", p + "D-4", p + "D-5", p + "D-6", p + "D-7", p + "D-8");
            assertThat(draftSequence(a)).isEqualTo(threads);
        } finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ prefix changes

    @Test
    void aPrefixChangeRewritesNeitherOfficialNorDraftReferencesAlreadyIssued() throws Exception {
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        Account a = register();
        String old = prefixOf(a);
        long sent = newDraft(a);
        submit(a, sent);                  // official OLD-1, draft OLDD-1
        long pending = newDraft(a);       // draft OLDD-2

        String now = uniquePrefix();
        setPrefix(staff, a, now);

        assertThat((String) JsonPath.read(detail(a, sent), "$.publicCode")).isEqualTo(old + "-1");   // official reference untouched
        assertThat((String) JsonPath.read(detail(a, sent), "$.draftCode")).isEqualTo(old + "D-1");
        assertThat(draftCode(a, pending)).isEqualTo(old + "D-2");                                     // a draft keeps the reference it was issued
        long fresh = newDraft(a);
        assertThat(draftCode(a, fresh)).isEqualTo(now + "D-3");                                       // new drafts: the new prefix, the counter goes on
        submit(a, pending);
        assertThat((String) JsonPath.read(detail(a, pending), "$.publicCode")).isEqualTo(now + "-2");  // as before: numbered under the prefix in force then
        assertThat((String) JsonPath.read(detail(a, pending), "$.draftCode")).isEqualTo(old + "D-2");
    }

    // ------------------------------------------------------------------ drafts that existed before

    @Test
    void aHandoffNumberedWhenItWasCreatedKeepsItsOfficialReferenceAndNeedsNoDraftOne() throws Exception {
        Account a = register();
        long id = newDraft(a);
        // As the drafts that were numbered at creation (before references moved to submission) are stored: an official reference, no draft one.
        jdbc.update("UPDATE handoff SET public_code = 'HO-9', reference_number = 9, draft_code = NULL WHERE id = ?", id);

        String json = detail(a, id);
        assertThat((String) JsonPath.read(json, "$.publicCode")).isEqualTo("HO-9");
        assertThat((Object) JsonPath.read(json, "$.draftCode")).isNull();

        submit(a, id);
        assertThat((String) JsonPath.read(detail(a, id), "$.publicCode")).isEqualTo("HO-9");   // submitting keeps it; no number is issued
        assertThat(handoffSequence(a)).isZero();
    }

    @Test
    void aDraftCanBeFoundByItsReferenceOnTheDashboard() throws Exception {
        Account a = register();
        newDraft(a);
        long second = newDraft(a);
        String code = draftCode(a, second);

        String found = mvc.perform(as(a, get("/api/v1/handoffs?q=" + code))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat((Integer) JsonPath.read(found, "$.totalElements")).isEqualTo(1);
        assertThat(((Number) JsonPath.read(found, "$.content[0].id")).longValue()).isEqualTo(second);
        assertThat((String) JsonPath.read(found, "$.content[0].draftCode")).isEqualTo(code);
        assertThat((Object) JsonPath.read(found, "$.content[0].publicCode")).isNull();
    }

    @Test
    void oneCustomerCannotSeeAnotherCustomersDraftThroughItsReference() throws Exception {
        Account a = register();
        Account b = register();
        long id = newDraft(a);
        String code = draftCode(a, id);

        String found = mvc.perform(as(b, get("/api/v1/handoffs?q=" + code))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat((Integer) JsonPath.read(found, "$.totalElements")).isZero();
        mvc.perform(as(b, get("/api/v1/handoffs/" + id))).andExpect(status().is4xxClientError());
        assertThat(((Map<?, ?>) JsonPath.read(detail(a, id), "$")).get("draftCode")).isEqualTo(code);
    }
}
