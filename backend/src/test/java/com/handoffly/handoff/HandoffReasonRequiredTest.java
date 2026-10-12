package com.handoffly.handoff;

import com.handoffly.testsupport.ApiTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Cancelling a handoff, or marking it disputed, needs a reason: it goes into the handoff's history, so it cannot be left empty. */
class HandoffReasonRequiredTest extends ApiTestBase {

    /** A handoff that has been sent (so it can be cancelled). */
    private long sent(Account owner) throws Exception {
        return ((Number) JsonPath.read(createAndSubmitHandoff(owner), "$.id")).longValue();
    }

    /** A handoff the recipient has accepted (so it can be disputed). */
    private long accepted(Account owner) throws Exception {
        long id = sent(owner);
        mvc.perform(post("/api/v1/r/" + emailSender.extractLastToken() + "/accept").contentType(MediaType.APPLICATION_JSON)
                .content("{\"acknowledgementName\":\"New Hire\"}")).andExpect(status().isOk());
        return id;
    }

    private String statusOf(Account owner, long id) throws Exception {
        return JsonPath.read(mvc.perform(as(owner, get("/api/v1/handoffs/" + id))).andReturn().getResponse().getContentAsString(), "$.status");
    }

    private List<Map<String, Object>> events(long id) {
        return jdbc.queryForList("SELECT event_type, message FROM audit_event WHERE handoff_id = ? ORDER BY id", id);
    }

    @Test
    void cancellingWithoutAReasonIsRefusedAndChangesNothing() throws Exception {
        Account owner = register();
        long id = sent(owner);
        int eventsBefore = events(id).size();

        // No body at all, an empty body, no reason, and a reason of nothing but spaces.
        mvc.perform(as(owner, post("/api/v1/handoffs/" + id + "/cancel"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("A reason is required to cancel a handoff."));
        for (String body : List.of("{}", "{\"reason\":null}", "{\"reason\":\"\"}", "{\"reason\":\"   \"}")) {
            mvc.perform(as(owner, post("/api/v1/handoffs/" + id + "/cancel").contentType(MediaType.APPLICATION_JSON).content(body)))
                    .andExpect(status().isBadRequest());
        }

        assertThat(statusOf(owner, id)).isEqualTo("AWAITING_RECIPIENT");   // still as it was
        assertThat(events(id)).hasSize(eventsBefore);                    // and nothing recorded
    }

    @Test
    void cancellingWithAReasonWorksAndTheReasonIsInTheHistory() throws Exception {
        Account owner = register();
        long id = sent(owner);

        mvc.perform(as(owner, post("/api/v1/handoffs/" + id + "/cancel").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"  Sent to the wrong person  \"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(events(id).get(events(id).size() - 1).get("message")).isEqualTo("Handoff cancelled: Sent to the wrong person");
    }

    @Test
    void markingDisputedWithoutAReasonIsRefusedAndWithOneWorks() throws Exception {
        Account owner = register();
        long id = accepted(owner);
        int eventsBefore = events(id).size();

        mvc.perform(as(owner, post("/api/v1/handoffs/" + id + "/dispute"))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("A reason is required to mark a handoff as disputed."));
        mvc.perform(as(owner, post("/api/v1/handoffs/" + id + "/dispute").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"  \"}")))
                .andExpect(status().isBadRequest());
        assertThat(statusOf(owner, id)).isEqualTo("ACTIVE_WITH_RECIPIENT");
        assertThat(events(id)).hasSize(eventsBefore);

        mvc.perform(as(owner, post("/api/v1/handoffs/" + id + "/dispute").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Quantity does not match\"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISPUTED"));
        assertThat(events(id).get(events(id).size() - 1).get("message")).isEqualTo("Handoff marked as disputed: Quantity does not match");
    }

    @Test
    void theOtherRulesAreUnchanged() throws Exception {
        Account owner = register();
        Account other = register();
        long id = sent(owner);
        String reason = "{\"reason\":\"Test\"}";

        // Someone else's handoff is still refused as before, with or without a reason.
        mvc.perform(as(other, post("/api/v1/handoffs/" + id + "/cancel").contentType(MediaType.APPLICATION_JSON).content(reason))).andExpect(status().isForbidden());
        mvc.perform(as(other, post("/api/v1/handoffs/" + id + "/cancel"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/handoffs/" + id + "/cancel").contentType(MediaType.APPLICATION_JSON).content(reason)).andExpect(status().isUnauthorized());

        // A move the state machine does not allow is still a conflict (a sent, unaccepted handoff cannot be disputed).
        mvc.perform(as(owner, post("/api/v1/handoffs/" + id + "/dispute").contentType(MediaType.APPLICATION_JSON).content(reason))).andExpect(status().isConflict());
        assertThat(statusOf(owner, id)).isEqualTo("AWAITING_RECIPIENT");

        // Closing keeps its own rule: no reason needed when nothing is missing, so a closable handoff closes without one.
        // (Covered in the lifecycle tests; here only that cancelling a cancelled handoff is still a conflict.)
        mvc.perform(as(owner, post("/api/v1/handoffs/" + id + "/cancel").contentType(MediaType.APPLICATION_JSON).content(reason))).andExpect(status().isOk());
        mvc.perform(as(owner, post("/api/v1/handoffs/" + id + "/cancel").contentType(MediaType.APPLICATION_JSON).content(reason))).andExpect(status().isConflict());
    }
}
