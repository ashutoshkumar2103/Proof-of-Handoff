package com.handoffly.job;

import com.handoffly.notification.EmailMessage;
import com.handoffly.testsupport.ApiTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A draft has no official reference until it is submitted, yet the Weekly Summary counts every handoff <em>created</em> in the week, drafts
 * included — creation is the one date every handoff has. So a draft appears in the summary under its own draft reference (AKD-3), never as
 * "null". Producing the summary is read-only: it never numbers a draft or issues a reference.
 */
class WeeklySummaryDraftReferenceTest extends ApiTestBase {

    private static final String RUN = "/api/v1/account/jobs/WEEKLY_SUMMARY/run";
    private static final String HISTORY = "/api/v1/account/jobs/history";

    private long draft(Account owner) throws Exception {
        return ((Number) JsonPath.read(createHandoff(owner), "$.id")).longValue();
    }

    /** The draft reference the draft was given when it was created (AKD-3). */
    private String draftCodeOf(Account owner, long id) throws Exception {
        return JsonPath.read(mvc.perform(as(owner, get("/api/v1/handoffs/" + id))).andReturn().getResponse().getContentAsString(), "$.draftCode");
    }

    /** A handoff that has been submitted, so it has its official reference: returns the reference. */
    private String numbered(Account owner) throws Exception {
        return JsonPath.read(createAndSubmitHandoff(owner), "$.publicCode");
    }

    /** Runs the customer's Weekly Summary and returns the one email it sent. */
    private EmailMessage runWeekly(Account who) throws Exception {
        mvc.perform(as(who, post(RUN))).andExpect(status().isOk());
        List<EmailMessage> mail = emailSender.messagesTo(who.email());
        assertThat(mail).hasSize(1);
        return mail.get(0);
    }

    private String historyJson(Account who) throws Exception {
        return mvc.perform(as(who, get(HISTORY))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private static void assertNoPlaceholderText(String text) {
        assertThat(text).doesNotContain("null").doesNotContain("undefined").doesNotContain("reference not assigned");
    }

    @Test
    void aNumberedHandoffKeepsItsReference() throws Exception {
        Account a = register();
        String reference = numbered(a);

        String body = runWeekly(a).textBody();

        assertThat(body).contains("Handoffs created: 1 (" + reference + ")");
        assertThat(body).contains("Handoffs still open: 1 (" + reference + ")");
        assertNoPlaceholderText(body);
        assertThat((String) JsonPath.read(historyJson(a), "$.content[0].summary")).contains(reference);
    }

    @Test
    void anUnnumberedDraftIsNamedByItsDraftReference() throws Exception {
        Account a = register();
        String reference = numbered(a);
        String d = draftCodeOf(a, draft(a));
        assertThat(d).isEqualTo(prefixOf(a) + "D-2");   // e.g. AKD-2: the account's prefix, D for draft (the submitted handoff was created first, as draft 1)

        EmailMessage mail = runWeekly(a);

        assertThat(mail.textBody()).contains("Handoffs created: 2 (" + reference + ", " + d + ")");
        assertNoPlaceholderText(mail.textBody());
        assertNoPlaceholderText(mail.htmlBody());
        assertThat(mail.htmlBody()).contains(d);

        // What the run keeps in the job history says the same, in the summary line and in the stored references.
        String history = historyJson(a);
        String summary = JsonPath.read(history, "$.content[0].summary");
        assertThat(summary).contains(reference).contains(d);
        assertNoPlaceholderText(summary);
        assertThat((List<String>) JsonPath.read(history, "$.content[0].successfulHandoffs")).containsExactly(reference, d);
        List<Map<String, Object>> stored = jdbc.queryForList("SELECT sent_handoffs FROM customer_job_run WHERE user_id = ?", a.id());
        assertThat(stored).hasSize(1);
        assertThat((String) stored.get(0).get("SENT_HANDOFFS")).isEqualTo(reference + ", " + d);
    }

    @Test
    void severalDraftsAreEachCountedAndEachNamedDifferently() throws Exception {
        Account a = register();
        String first = draftCodeOf(a, draft(a));
        String second = draftCodeOf(a, draft(a));
        String third = draftCodeOf(a, draft(a));
        assertThat(List.of(first, second, third)).containsExactly(prefixOf(a) + "D-1", prefixOf(a) + "D-2", prefixOf(a) + "D-3");

        String body = runWeekly(a).textBody();

        assertThat(body).contains("Handoffs created: 3 (" + first + ", " + second + ", " + third + ")");
        assertNoPlaceholderText(body);
    }

    @Test
    void aDraftIsCountedAsCreatedOnlyAndGivesNothing() throws Exception {
        // The summary's own definition (matches creation date; a draft has nothing given, is not open, is not closed) is unchanged.
        Account a = register();
        String reference = numbered(a);   // 1 item of the default test quantity, submitted
        draft(a);

        String body = runWeekly(a).textBody();

        assertThat(body).contains("Handoffs created: 2 (");
        assertThat(body).contains("Handoffs closed: 0");
        assertThat(body).contains("Handoffs still open: 1 (" + reference + ")");   // the draft is not "open"
        assertThat(body).doesNotContain("Handoffs still open: 2");
        assertThat(body).contains("Items given: 1");   // the draft gave nothing
    }

    @Test
    void producingTheSummaryNeverNumbersOrChangesADraft() throws Exception {
        Account a = register();
        long draftId = draft(a);
        numbered(a);
        String sql = "SELECT public_code, draft_code, reference_number, status, version, updated_at, deleted_at FROM handoff WHERE id = ?";
        Map<String, Object> draftBefore = jdbc.queryForMap(sql, draftId);
        long handoffSequenceBefore = jdbc.queryForObject("SELECT handoff_sequence FROM app_user WHERE id = ?", Long.class, a.id());
        long draftSequenceBefore = jdbc.queryForObject("SELECT draft_sequence FROM app_user WHERE id = ?", Long.class, a.id());
        int eventsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE handoff_id = ?", Integer.class, draftId);

        runWeekly(a);
        mvc.perform(as(a, post(RUN))).andExpect(status().isOk());   // and again

        assertThat(jdbc.queryForMap(sql, draftId)).isEqualTo(draftBefore);   // still a DRAFT, same draft reference, no official one, untouched
        assertThat(draftBefore.get("PUBLIC_CODE")).isNull();
        assertThat(draftBefore.get("DRAFT_CODE")).isNotNull();
        assertThat(draftBefore.get("STATUS")).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT handoff_sequence FROM app_user WHERE id = ?", Long.class, a.id())).isEqualTo(handoffSequenceBefore);
        assertThat(jdbc.queryForObject("SELECT draft_sequence FROM app_user WHERE id = ?", Long.class, a.id())).isEqualTo(draftSequenceBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE handoff_id = ?", Integer.class, draftId)).isEqualTo(eventsBefore);
    }

    @Test
    void aCustomersSummaryNeverIncludesAnotherCustomersDraftsOrHandoffs() throws Exception {
        Account a = register();
        Account b = register();
        String mine = numbered(a);
        String theirs = numbered(b);
        String theirDraft = draftCodeOf(b, draft(b));
        String theirOtherDraft = draftCodeOf(b, draft(b));

        String body = runWeekly(a).textBody();

        assertThat(body).contains("Handoffs created: 1 (" + mine + ")");
        assertThat(body).doesNotContain(theirs).doesNotContain(theirDraft).doesNotContain(theirOtherDraft);
        assertNoPlaceholderText(body);
        assertThat(emailSender.messagesTo(b.email())).isEmpty();   // b's job was not run
    }

    @Test
    void aSummaryWithOnlyADraftStillSendsOneEmailAndIsOneHistoryRow() throws Exception {
        Account a = register();
        String d = draftCodeOf(a, draft(a));

        mvc.perform(as(a, post(RUN))).andExpect(status().isOk());

        assertThat(emailSender.messagesTo(a.email())).hasSize(1);
        assertThat(emailSender.messagesTo(a.email()).get(0).textBody()).contains("Handoffs created: 1 (" + d + ")");
        assertThat((Integer) JsonPath.read(historyJson(a), "$.totalElements")).isEqualTo(1);
        assertThat((String) JsonPath.read(historyJson(a), "$.content[0].status")).isEqualTo("SENT");
    }
}
