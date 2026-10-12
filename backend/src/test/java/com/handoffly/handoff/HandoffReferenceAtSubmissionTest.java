package com.handoffly.handoff;

import com.handoffly.support.staff.SupportRole;
import com.handoffly.testsupport.ApiTestBase;
import com.jayway.jsonpath.JsonPath;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A handoff's customer-facing reference is issued when its draft is first submitted, not when it is created. Until then a draft has none,
 * and creating, editing, duplicating or deleting drafts never uses up a number. A draft that was numbered when it was created (before this
 * moved to submission) keeps its reference, and nothing already issued ever changes.
 */
class HandoffReferenceAtSubmissionTest extends ApiTestBase {

    private long draft(Account owner) throws Exception {
        return ((Number) JsonPath.read(createHandoff(owner), "$.id")).longValue();
    }

    private String submit(Account owner, long id) throws Exception {
        return mvc.perform(as(owner, post("/api/v1/handoffs/" + id + "/submit")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String detail(Account owner, long id) throws Exception {
        return mvc.perform(as(owner, get("/api/v1/handoffs/" + id))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private String codeInDb(long id) {
        return jdbc.queryForObject("SELECT public_code FROM handoff WHERE id = ?", String.class, id);
    }

    private Long numberInDb(long id) {
        return jdbc.queryForObject("SELECT reference_number FROM handoff WHERE id = ?", Long.class, id);
    }

    /** The last number issued to this customer. */
    private long sequenceOf(Account owner) {
        return jdbc.queryForObject("SELECT handoff_sequence FROM app_user WHERE id = ?", Long.class, owner.id());
    }

    private List<Map<String, Object>> auditOf(long id) {
        return jdbc.queryForList("SELECT event_type, message FROM audit_event WHERE handoff_id = ? ORDER BY id", id);
    }

    // ------------------------------------------------------------------ a draft has no reference

    @Test
    void aNewDraftHasNoReferenceAndUsesUpNoNumber() throws Exception {
        Account owner = register();
        long first = draft(owner);
        long second = draft(owner);

        mvc.perform(as(owner, get("/api/v1/handoffs/" + first)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT")).andExpect(jsonPath("$.publicCode").doesNotExist());
        assertThat(codeInDb(first)).isNull();
        assertThat(numberInDb(first)).isNull();
        assertThat(sequenceOf(owner)).isZero();

        // It is in the lists, the search (by its title; it has no reference to search for) and the dashboard, like any draft.
        mvc.perform(as(owner, get("/api/v1/handoffs"))).andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].publicCode").doesNotExist());
        mvc.perform(as(owner, get("/api/v1/handoffs").param("q", "Laptop"))).andExpect(jsonPath("$.totalElements").value(2));
        mvc.perform(as(owner, get("/api/v1/handoffs/dashboard"))).andExpect(jsonPath("$.statusCounts.DRAFT").value(2));

        // Editing it does not number it either.
        mvc.perform(as(owner, patch("/api/v1/handoffs/" + second).contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Edited\",\"senderName\":\"S\",\"recipientName\":\"R\",\"recipientEmail\":\"r@example.test\"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.publicCode").doesNotExist());
        assertThat(sequenceOf(owner)).isZero();
    }

    @Test
    void aDuplicateIsAnotherUnnumberedDraft() throws Exception {
        Account owner = register();
        long original = ((Number) JsonPath.read(createAndSubmitHandoff(owner), "$.id")).longValue();
        assertThat(sequenceOf(owner)).isEqualTo(1);

        mvc.perform(as(owner, get("/api/v1/handoffs/" + original + "/template"))).andExpect(status().isOk());
        assertThat(sequenceOf(owner)).isEqualTo(1);   // looking at the template issues nothing
        createHandoff(owner);                          // saving the copy goes through the ordinary create
        assertThat(sequenceOf(owner)).isEqualTo(1);
    }

    @Test
    void deletingADraftUsesNoNumberAndTheMessagesNeverSayNull() throws Exception {
        Account owner = register();
        long id = draft(owner);

        mvc.perform(as(owner, delete("/api/v1/handoffs/" + id))).andExpect(status().isNoContent());

        assertThat(sequenceOf(owner)).isZero();
        List<Map<String, Object>> events = auditOf(id);
        assertThat(events).extracting(e -> e.get("event_type")).containsExactly("HANDOFF_CREATED", "HANDOFF_DELETED");
        assertThat(events.get(1).get("message")).isEqualTo("Draft deleted.");
        assertThat(events).extracting(e -> String.valueOf(e.get("message"))).noneMatch(m -> m.contains("null"));
    }

    @Test
    void deletingADraftThatWasNumberedBeforeKeepsItsReferenceInTheMessage() throws Exception {
        Account owner = register();
        long id = draft(owner);
        jdbc.update("UPDATE handoff SET public_code = 'LG-7', reference_number = 7 WHERE id = ?", id);

        mvc.perform(as(owner, delete("/api/v1/handoffs/" + id))).andExpect(status().isNoContent());

        assertThat(auditOf(id).get(1).get("message")).isEqualTo("Draft LG-7 deleted.");
        assertThat(codeInDb(id)).isEqualTo("LG-7");   // the row, its reference and its history stay
    }

    // ------------------------------------------------------------------ submission issues it

    @Test
    void theFirstSubmissionIssuesTheNextNumberAndEverythingCarriesIt() throws Exception {
        Account owner = register();
        String prefix = prefixOf(owner);
        long a = draft(owner);
        long b = draft(owner);
        long c = draft(owner);

        // Numbers follow the order of submission, not of creation; a draft that is never submitted never gets one.
        assertThat((String) JsonPath.read(submit(owner, b), "$.publicCode")).isEqualTo(prefix + "-1");
        assertThat(emailSender.getLastMessage().subject()).contains(prefix + "-1");   // the email already had it
        mvc.perform(get("/api/v1/r/" + emailSender.extractLastToken())).andExpect(jsonPath("$.publicCode").value(prefix + "-1"));
        assertThat((String) JsonPath.read(submit(owner, a), "$.publicCode")).isEqualTo(prefix + "-2");
        assertThat(codeInDb(c)).isNull();

        assertThat(codeInDb(b)).isEqualTo(prefix + "-1");
        assertThat(numberInDb(b)).isEqualTo(1L);
        assertThat(numberInDb(a)).isEqualTo(2L);
        assertThat(sequenceOf(owner)).isEqualTo(2);

        // The audit event of the submission says which reference it was issued.
        assertThat(auditOf(b)).extracting(e -> e.get("message")).contains("Outgoing handoff submitted as " + prefix + "-1.");

        // Pulling the same reference up again later (resend, detail) never changes it.
        mvc.perform(as(owner, post("/api/v1/handoffs/" + b + "/resend-link"))).andExpect(status().isOk());
        assertThat(codeInDb(b)).isEqualTo(prefix + "-1");
        assertThat(sequenceOf(owner)).isEqualTo(2);
    }

    @Test
    void aSecondSubmissionOfTheSameDraftIsRefusedAndIssuesNothing() throws Exception {
        Account owner = register();
        long id = draft(owner);
        submit(owner, id);

        mvc.perform(as(owner, post("/api/v1/handoffs/" + id + "/submit"))).andExpect(status().isConflict());

        assertThat(sequenceOf(owner)).isEqualTo(1);
        assertThat(auditOf(id)).extracting(e -> e.get("event_type")).filteredOn("OUTGOING_SUBMITTED"::equals).hasSize(1);
    }

    @Test
    void twoSubmissionsOfTheSameDraftAtOnceIssueOneReference() throws Exception {
        Account owner = register();
        long id = draft(owner);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(pool.submit((Callable<Integer>) () -> {
                    go.await();
                    return mvc.perform(as(owner, post("/api/v1/handoffs/" + id + "/submit"))).andReturn().getResponse().getStatus();
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> r : results) statuses.add(r.get(60, TimeUnit.SECONDS));

            assertThat(statuses).containsExactlyInAnyOrder(200, 409);   // one wins; the other finds it already sent
        } finally {
            pool.shutdownNow();
        }
        assertThat(sequenceOf(owner)).isEqualTo(1);
        assertThat(codeInDb(id)).isEqualTo(prefixOf(owner) + "-1");
    }

    // ------------------------------------------------------------------ legacy drafts

    @Test
    void aDraftNumberedBeforeKeepsItsReferenceAndTheCounterDoesNotMove() throws Exception {
        Account owner = register();
        long id = draft(owner);
        // What a draft created before this change looks like: numbered at creation, with the counter already past it.
        jdbc.update("UPDATE handoff SET public_code = 'LG-7', reference_number = 7 WHERE id = ?", id);
        jdbc.update("UPDATE app_user SET handoff_sequence = 7 WHERE id = ?", owner.id());
        List<Map<String, Object>> before = auditOf(id);

        assertThat((String) JsonPath.read(submit(owner, id), "$.publicCode")).isEqualTo("LG-7");

        assertThat(codeInDb(id)).isEqualTo("LG-7");
        assertThat(numberInDb(id)).isEqualTo(7L);
        assertThat(sequenceOf(owner)).isEqualTo(7);   // nothing was issued
        assertThat(emailSender.getLastMessage().subject()).contains("LG-7");
        assertThat(auditOf(id).subList(0, before.size())).isEqualTo(before);   // history already recorded is untouched
        assertThat(auditOf(id)).extracting(e -> e.get("message")).contains("Outgoing handoff submitted.");   // nothing newly issued to report

        // The next draft to be submitted carries on from the counter: LG-7 is never issued again.
        String next = (String) JsonPath.read(submit(owner, draft(owner)), "$.publicCode");
        assertThat(next).isEqualTo(prefixOf(owner) + "-8");
    }

    // ------------------------------------------------------------------ the prefix in force at submission

    @Test
    void aDraftIsNumberedUnderThePrefixInForceWhenItIsSubmittedAndOldReferencesAreNeverRewritten() throws Exception {
        StaffAccount staff = registerStaff(SupportRole.MANAGER);
        Account owner = register();
        String st = uniquePrefix();
        String sx = uniquePrefix();
        setPrefix(staff, owner, st);
        long early = draft(owner);
        long later = draft(owner);
        submit(owner, early);   // ST-1
        assertThat(codeInDb(early)).isEqualTo(st + "-1");

        setPrefix(staff, owner, sx);   // support changes the prefix while a draft is waiting

        assertThat((String) JsonPath.read(submit(owner, later), "$.publicCode")).isEqualTo(sx + "-2");
        assertThat(codeInDb(early)).isEqualTo(st + "-1");   // the one already issued is not rewritten
        assertThat(sequenceOf(owner)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ a failed submission uses nothing

    @Test
    void aFailedEmailRollsEverythingBackAndTheRetryGetsTheNumberThatWasNeverUsed() throws Exception {
        Account owner = register();
        long id = draft(owner);
        List<Map<String, Object>> before = auditOf(id);

        emailSender.setFailing(true);
        try {
            mvc.perform(as(owner, post("/api/v1/handoffs/" + id + "/submit"))).andExpect(status().isInternalServerError());
        } finally {
            emailSender.setFailing(false);
        }

        // Still a draft, still unnumbered, still retryable, with nothing left behind.
        mvc.perform(as(owner, get("/api/v1/handoffs/" + id)))
                .andExpect(jsonPath("$.status").value("DRAFT")).andExpect(jsonPath("$.publicCode").doesNotExist());
        assertThat(codeInDb(id)).isNull();
        assertThat(numberInDb(id)).isNull();
        assertThat(sequenceOf(owner)).isZero();
        assertThat(auditOf(id)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recipient_link WHERE handoff_id = ?", Integer.class, id)).isZero();

        // The retry works, and gets number 1: the failed attempt never used it up.
        assertThat((String) JsonPath.read(submit(owner, id), "$.publicCode")).isEqualTo(prefixOf(owner) + "-1");
        assertThat(sequenceOf(owner)).isEqualTo(1);
    }

    @Test
    void aSubmissionThatIsRefusedBeforeItStartsUsesNoNumberEither() throws Exception {
        Account owner = register();
        // No items.
        long empty = ((Number) JsonPath.read(mvc.perform(as(owner, post("/api/v1/handoffs").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Empty\",\"senderName\":\"S\",\"recipientName\":\"R\",\"recipientEmail\":\"r@example.test\",\"items\":[]}")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id")).longValue();
        mvc.perform(as(owner, post("/api/v1/handoffs/" + empty + "/submit"))).andExpect(status().isConflict());
        assertThat(sequenceOf(owner)).isZero();
        assertThat(codeInDb(empty)).isNull();

        // A plan that has ended since the draft was made.
        long id = draft(owner);
        var user = users.findById(owner.id()).orElseThrow();
        user.startPlan(user.getSubscriptionPlan(), Instant.now().minus(60, ChronoUnit.DAYS), Instant.now().minus(1, ChronoUnit.DAYS));
        users.saveAndFlush(user);
        mvc.perform(as(owner, post("/api/v1/handoffs/" + id + "/submit"))).andExpect(status().isForbidden());
        assertThat(sequenceOf(owner)).isZero();
        assertThat(codeInDb(id)).isNull();
    }

    // ------------------------------------------------------------------ order

    @Test
    void theReferenceSortUsesTheNumberInsideTheReferenceAndPutsDraftsLast() throws Exception {
        Account owner = register();
        long first = draft(owner);
        long second = draft(owner);
        long third = draft(owner);
        long unnumbered = draft(owner);
        // Numbers deliberately out of step with the internal ids and with text order ("AV-10" < "AV-9" as text).
        jdbc.update("UPDATE handoff SET public_code = 'AV-100', reference_number = 100 WHERE id = ?", first);
        jdbc.update("UPDATE handoff SET public_code = 'ZZ-10', reference_number = 10 WHERE id = ?", second);
        jdbc.update("UPDATE handoff SET public_code = 'AV-9', reference_number = 9 WHERE id = ?", third);

        List<Integer> ascending = JsonPath.read(mvc.perform(as(owner, get("/api/v1/handoffs").param("sort", "reference,asc")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.content[*].id");
        assertThat(ascending).containsExactly((int) third, (int) second, (int) first, (int) unnumbered);   // 9, 10, 100, then the draft

        List<Integer> descending = JsonPath.read(mvc.perform(as(owner, get("/api/v1/handoffs").param("sort", "reference,desc")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.content[*].id");
        assertThat(descending).containsExactly((int) first, (int) second, (int) third, (int) unnumbered);   // the draft is last either way

        // Without asking for it, the order is as it always was (here: by id).
        List<Integer> byId = JsonPath.read(mvc.perform(as(owner, get("/api/v1/handoffs").param("sort", "id,asc")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.content[*].id");
        assertThat(byId).containsExactly((int) first, (int) second, (int) third, (int) unnumbered);
    }

    // ------------------------------------------------------------------ the documents

    @Test
    void aDraftPdfSaysItIsNotSubmittedAndASubmittedOneCarriesTheReference() throws Exception {
        Account owner = register();
        long id = draft(owner);

        String draftText = pdfText(owner, id);
        assertThat(draftText).contains("DRAFT — NOT SUBMITTED");
        assertThat(draftText).doesNotContain("null").doesNotContain("Reference:");

        submit(owner, id);
        String sentText = pdfText(owner, id);
        assertThat(sentText).contains("Reference: " + prefixOf(owner) + "-1").doesNotContain("DRAFT — NOT SUBMITTED");
    }

    @Test
    void aDraftSpreadsheetLeavesTheReferenceEmptyAndStillSaysDraft() throws Exception {
        Account owner = register();
        long id = draft(owner);

        byte[] bytes = mvc.perform(as(owner, get("/api/v1/handoffs/" + id + "/excel"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (XSSFWorkbook book = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = book.getSheetAt(0);
            List<String> cells = new ArrayList<>();
            for (Row row : sheet) {
                if (row.getCell(0) != null) cells.add(row.getCell(0).toString());
            }
            assertThat(cells).contains("Reference:");
            assertThat(cells).noneMatch(c -> c.contains("null"));
            assertThat(cells).anyMatch(c -> c.contains("status: Draft"));
        }
    }

    private String pdfText(Account owner, long id) throws Exception {
        byte[] pdf = mvc.perform(as(owner, get("/api/v1/handoffs/" + id + "/pdf"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc).replaceAll("\\s+", " ");
        }
    }
}
