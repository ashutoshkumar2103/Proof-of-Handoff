package com.handoffly.handoff;

import com.handoffly.testsupport.ApiTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Deleting a draft takes it out of the customer's workspace but never out of the audit trail: the handoff's history is append-only,
 * and its events keep pointing at the handoff row they describe (which is kept, marked as deleted, rather than erased).
 */
class HandoffDeleteDraftTest extends ApiTestBase {

    /** The real repository, watched, so one test can make saving fail the way a database failure would. */
    @MockitoSpyBean
    private HandoffRepository handoffRepository;

    @Autowired
    private com.handoffly.attachment.AttachmentRepository attachments;

    private long draft(Account owner) throws Exception {
        return ((Number) JsonPath.read(createHandoff(owner), "$.id")).longValue();
    }

    private List<Map<String, Object>> auditOf(long handoffId) {
        return jdbc.queryForList("SELECT id, event_type, actor_ref, message FROM audit_event WHERE handoff_id = ? ORDER BY id", handoffId);
    }

    private int rowsOf(long handoffId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM handoff WHERE id = ?", Integer.class, handoffId);
    }

    @Test
    void aDraftThatAlreadyHasItsCreationEventCanBeDeleted() throws Exception {
        Account owner = register();
        long id = draft(owner);
        assertThat(auditOf(id)).extracting(e -> e.get("event_type")).containsExactly("HANDOFF_CREATED");   // the event that used to block the delete

        mvc.perform(as(owner, delete("/api/v1/handoffs/" + id))).andExpect(status().isNoContent());
    }

    @Test
    void theAuditTrailIsKeptAndRecordsTheDeletion() throws Exception {
        Account owner = register();
        long id = draft(owner);
        List<Map<String, Object>> before = auditOf(id);

        mvc.perform(as(owner, delete("/api/v1/handoffs/" + id))).andExpect(status().isNoContent());

        List<Map<String, Object>> after = auditOf(id);
        assertThat(after).hasSize(before.size() + 1);
        assertThat(after.subList(0, before.size())).isEqualTo(before);   // what was recorded is exactly as it was: nothing changed or removed
        assertThat(after.get(after.size() - 1).get("event_type")).isEqualTo("HANDOFF_DELETED");
        assertThat(after.get(after.size() - 1).get("actor_ref")).isEqualTo(owner.email());
        assertThat(rowsOf(id)).isEqualTo(1);   // the events still point at a real handoff row
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM handoff WHERE id = ?", java.sql.Timestamp.class, id)).isNotNull();
    }

    @Test
    void aDeletedDraftIsGoneFromEveryListAndLookup() throws Exception {
        Account owner = register();
        long kept = draft(owner);
        long gone = draft(owner);
        String goneCode = JsonPath.read(mvc.perform(as(owner, get("/api/v1/handoffs/" + gone))).andReturn().getResponse().getContentAsString(), "$.publicCode");

        mvc.perform(as(owner, delete("/api/v1/handoffs/" + gone))).andExpect(status().isNoContent());

        mvc.perform(as(owner, get("/api/v1/handoffs"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(kept));
        mvc.perform(as(owner, get("/api/v1/handoffs?q=" + goneCode))).andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(as(owner, get("/api/v1/handoffs/dashboard"))).andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.statusCounts.DRAFT").value(1));
        mvc.perform(as(owner, get("/api/v1/handoffs/" + gone))).andExpect(status().isNotFound());
        mvc.perform(as(owner, get("/api/v1/handoffs/" + gone + "/template"))).andExpect(status().isNotFound());
        mvc.perform(as(owner, get("/api/v1/handoffs/" + gone + "/pdf"))).andExpect(status().isNotFound());
        mvc.perform(as(owner, delete("/api/v1/handoffs/" + gone))).andExpect(status().isNotFound());   // already gone
    }

    @Test
    void aDraftWithAnAttachmentCanBeDeletedAndItsRecordIsKept() throws Exception {
        Account owner = register();
        long id = draft(owner);
        mvc.perform(as(owner, multipart("/api/v1/handoffs/" + id + "/attachments")
                        .file(new MockMultipartFile("file", "note.txt", "text/plain", "keep me".getBytes()))))
                .andExpect(status().isCreated());

        mvc.perform(as(owner, delete("/api/v1/handoffs/" + id))).andExpect(status().isNoContent());

        assertThat(attachments.findAll().stream().filter(a -> a.getHandoffId() == id)).hasSize(1);
        mvc.perform(as(owner, get("/api/v1/handoffs/" + id + "/attachments"))).andExpect(status().isNotFound());
    }

    @Test
    void aHandoffThatHasBeenSentCannotBeDeleted() throws Exception {
        Account owner = register();
        long id = draft(owner);
        mvc.perform(as(owner, post("/api/v1/handoffs/" + id + "/submit"))).andExpect(status().isOk());

        mvc.perform(as(owner, delete("/api/v1/handoffs/" + id))).andExpect(status().isConflict());

        mvc.perform(as(owner, get("/api/v1/handoffs/" + id))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM handoff WHERE id = ?", java.sql.Timestamp.class, id)).isNull();
        assertThat(auditOf(id)).extracting(e -> e.get("event_type")).doesNotContain("HANDOFF_DELETED");
    }

    @Test
    void aHandoffThatIsAcceptedOrClosedCannotBeDeletedEither() throws Exception {
        Account owner = register();
        long id = draft(owner);
        mvc.perform(as(owner, post("/api/v1/handoffs/" + id + "/submit"))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/r/" + emailSender.extractLastToken() + "/accept").contentType("application/json")
                .content("{\"acknowledgementName\":\"New Hire\"}")).andExpect(status().isOk());
        mvc.perform(as(owner, delete("/api/v1/handoffs/" + id))).andExpect(status().isConflict());

        long cancelled = draft(owner);
        mvc.perform(as(owner, post("/api/v1/handoffs/" + cancelled + "/submit"))).andExpect(status().isOk());
        mvc.perform(as(owner, post("/api/v1/handoffs/" + cancelled + "/cancel").contentType("application/json").content("{\"reason\":\"Test\"}"))).andExpect(status().isOk());
        mvc.perform(as(owner, delete("/api/v1/handoffs/" + cancelled))).andExpect(status().isConflict());
    }

    @Test
    void aCustomerCannotDeleteAnotherCustomersDraft() throws Exception {
        Account owner = register();
        Account other = register();
        long id = draft(owner);

        mvc.perform(as(other, delete("/api/v1/handoffs/" + id))).andExpect(status().is4xxClientError());
        mvc.perform(delete("/api/v1/handoffs/" + id)).andExpect(status().isUnauthorized());

        mvc.perform(as(owner, get("/api/v1/handoffs/" + id))).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM handoff WHERE id = ?", java.sql.Timestamp.class, id)).isNull();
        assertThat(auditOf(id)).hasSize(1);
    }

    @Test
    void aDatabaseFailureIsAnErrorNotADeletionAndLeavesEverythingAsItWas() throws Exception {
        Account owner = register();
        long id = draft(owner);
        List<Map<String, Object>> before = auditOf(id);
        doThrow(new DataIntegrityViolationException("simulated database failure")).when(handoffRepository).saveAndFlush(any());
        try {
            mvc.perform(as(owner, delete("/api/v1/handoffs/" + id)))
                    .andExpect(status().isInternalServerError())
                    .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("simulated"))));   // nothing internal is shown
        } finally {
            reset(handoffRepository);
        }

        mvc.perform(as(owner, get("/api/v1/handoffs/" + id))).andExpect(status().isOk());   // still there
        assertThat(auditOf(id)).isEqualTo(before);                                            // and no deletion was recorded
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM handoff WHERE id = ?", java.sql.Timestamp.class, id)).isNull();
    }

    @Test
    void theHistoryOfAHandoffThatIsNotDeletedIsUnchanged() throws Exception {
        Account owner = register();
        long id = draft(owner);
        mvc.perform(as(owner, post("/api/v1/handoffs/" + id + "/submit"))).andExpect(status().isOk());

        mvc.perform(as(owner, get("/api/v1/handoffs/" + id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(auditOf(id).size()))
                .andExpect(jsonPath("$.events[0].type").value("HANDOFF_CREATED"));
    }
}
