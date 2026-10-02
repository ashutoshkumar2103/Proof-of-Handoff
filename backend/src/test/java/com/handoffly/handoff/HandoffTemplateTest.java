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

/**
 * Duplicating a handoff: the server hands back only what is reusable, and a draft made from it is an ordinary new
 * draft — nothing of the original's lifecycle comes with it, and the original is left exactly as it was.
 */
class HandoffTemplateTest extends ApiTestBase {

    private static final String HANDOFFS = "/api/v1/handoffs";

    /** A handoff taken well into its life: sent, accepted, partly returned, with an attachment and due date. */
    private long livedInHandoff(Account owner) throws Exception {
        String body = """
                {"title":"Wedding rentals","purpose":"Saturday event","category":"EVENT","senderName":"Asha Rao",
                 "senderOrganization":"Asha Events","recipientName":"Ravi Kumar","recipientEmail":"ravi@example.test",
                 "recipientPhone":"+91 90000 33333","dueAt":"2030-01-01T10:00:00Z",
                 "items":[{"name":"Chairs","quantity":500,"unit":"pcs","serialNumber":"LOT-7","notes":"Stackable","condition":"GOOD"},
                          {"name":"Tables","quantity":80,"unit":"pcs","condition":"GOOD"}]}""";
        String created = mvc.perform(as(owner, post(HANDOFFS).contentType(MediaType.APPLICATION_JSON).content(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(created, "$.id")).longValue();
        long chairs = ((Number) JsonPath.read(created, "$.items[0].id")).longValue();

        mvc.perform(as(owner, post(HANDOFFS + "/" + id + "/submit"))).andExpect(status().isOk());
        String token = emailSender.extractLastToken();
        mvc.perform(post("/api/v1/r/" + token + "/accept").contentType(MediaType.APPLICATION_JSON)
                .content("{\"acknowledgementName\":\"Ravi Kumar\"}")).andExpect(status().isOk());
        mvc.perform(as(owner, post(HANDOFFS + "/" + id + "/returns").contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":[{\"itemId\":" + chairs + ",\"quantity\":200,\"condition\":\"GOOD\"}]}")))
                .andExpect(status().isCreated());
        return id;
    }

    @Test
    void theTemplateHoldsOnlyWhatIsReusableAndTheQuantitiesThatWentOut() throws Exception {
        Account owner = register();
        long id = livedInHandoff(owner);

        String json = mvc.perform(as(owner, get(HANDOFFS + "/" + id + "/template"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Wedding rentals (copy)"))
                .andExpect(jsonPath("$.category").value("EVENT"))
                .andExpect(jsonPath("$.purpose").value("Saturday event"))
                .andExpect(jsonPath("$.senderName").value("Asha Rao"))
                .andExpect(jsonPath("$.senderOrganization").value("Asha Events"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].name").value("Chairs"))
                .andExpect(jsonPath("$.items[0].quantity").value(500))     // what went out, not the 300 that remain
                .andExpect(jsonPath("$.items[0].unit").value("pcs"))
                .andExpect(jsonPath("$.items[1].name").value("Tables"))
                .andReturn().getResponse().getContentAsString();

        // Exactly these fields and no others: nothing transactional can be in it, whatever the entity grows later.
        Map<String, Object> template = JsonPath.read(json, "$");
        assertThat(template.keySet()).containsExactlyInAnyOrder("title", "category", "purpose", "senderName", "senderOrganization", "items");
        Map<String, Object> item = JsonPath.read(json, "$.items[0]");
        assertThat(item.keySet()).containsExactlyInAnyOrder("name", "quantity", "unit");
        assertThat(json).doesNotContain("ravi@example.test", "Ravi", "LOT-7", "Stackable", "2030-01-01", "ACTIVE_WITH_RECIPIENT",
                token(), "acknowledg");
    }

    private String token() {
        return emailSender.extractLastToken();
    }

    @Test
    void aDraftMadeFromItIsAnOrdinaryNewDraftAndTheOriginalIsUntouched() throws Exception {
        Account owner = register();
        long id = livedInHandoff(owner);
        String before = mvc.perform(as(owner, get(HANDOFFS + "/" + id))).andReturn().getResponse().getContentAsString();
        String originalCode = JsonPath.read(before, "$.publicCode");

        String t = mvc.perform(as(owner, get(HANDOFFS + "/" + id + "/template"))).andReturn().getResponse().getContentAsString();
        // The customer adds who it is for (the form requires it), then saves through the normal create call.
        String createBody = """
                {"title":"%s","category":"%s","purpose":"%s","senderName":"%s","senderOrganization":"%s",
                 "recipientName":"Someone Else","recipientEmail":"else@example.test",
                 "items":[{"name":"%s","quantity":%s,"unit":"%s"},{"name":"%s","quantity":%s,"unit":"%s"}]}"""
                .formatted(JsonPath.read(t, "$.title"), JsonPath.read(t, "$.category"), JsonPath.read(t, "$.purpose"),
                        JsonPath.read(t, "$.senderName"), JsonPath.read(t, "$.senderOrganization"),
                        JsonPath.read(t, "$.items[0].name"), JsonPath.read(t, "$.items[0].quantity"), JsonPath.read(t, "$.items[0].unit"),
                        JsonPath.read(t, "$.items[1].name"), JsonPath.read(t, "$.items[1].quantity"), JsonPath.read(t, "$.items[1].unit"));
        String created = mvc.perform(as(owner, post(HANDOFFS).contentType(MediaType.APPLICATION_JSON).content(createBody)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.title").value("Wedding rentals (copy)"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].outgoing").value(500))
                .andExpect(jsonPath("$.items[0].condition").value("GOOD"))
                .andExpect(jsonPath("$.items[0].serialNumber").doesNotExist())
                .andExpect(jsonPath("$.items[0].returnedConfirmed").value(0))
                .andExpect(jsonPath("$.dueAt").doesNotExist())
                .andExpect(jsonPath("$.acknowledgementName").doesNotExist())
                .andExpect(jsonPath("$.outgoingAt").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        long newId = ((Number) JsonPath.read(created, "$.id")).longValue();
        assertThat(newId).isNotEqualTo(id);

        // A fresh reference from this customer's own counter, no link, no returns, no files, one creation event.
        String newCode = JsonPath.read(created, "$.publicCode");
        assertThat(newCode).isNotEqualTo(originalCode);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recipient_link WHERE handoff_id = ?", Long.class, newId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM return_event WHERE handoff_id = ?", Long.class, newId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attachment WHERE handoff_id = ?", Long.class, newId)).isZero();
        List<String> events = JsonPath.read(mvc.perform(as(owner, get(HANDOFFS + "/" + newId + "/events"))).andReturn()
                .getResponse().getContentAsString(), "$[*].type");
        assertThat(events).containsExactly("HANDOFF_CREATED");

        // The original is exactly as it was: same status, returns and history.
        String after = mvc.perform(as(owner, get(HANDOFFS + "/" + id))).andReturn().getResponse().getContentAsString();
        assertThat((Object) JsonPath.read(after, "$.status")).isEqualTo(JsonPath.read(before, "$.status"));
        assertThat((Object) JsonPath.read(after, "$.totalReturned")).isEqualTo(JsonPath.read(before, "$.totalReturned"));
        assertThat((Object) JsonPath.read(after, "$.totalRemaining")).isEqualTo(JsonPath.read(before, "$.totalRemaining"));
        assertThat((Object) JsonPath.read(after, "$.publicCode")).isEqualTo(originalCode);
    }

    @Test
    void askingForATemplateCreatesNothing() throws Exception {
        Account owner = register();
        long id = livedInHandoff(owner);
        long handoffs = jdbc.queryForObject("SELECT COUNT(*) FROM handoff WHERE owner_user_id = ?", Long.class, owner.id());
        long sequence = jdbc.queryForObject("SELECT handoff_sequence FROM app_user WHERE id = ?", Long.class, owner.id());
        for (int i = 0; i < 3; i++) {
            mvc.perform(as(owner, get(HANDOFFS + "/" + id + "/template"))).andExpect(status().isOk());
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM handoff WHERE owner_user_id = ?", Long.class, owner.id())).isEqualTo(handoffs);
        assertThat(jdbc.queryForObject("SELECT handoff_sequence FROM app_user WHERE id = ?", Long.class, owner.id())).isEqualTo(sequence);   // no number used up
    }

    @Test
    void aLongTitleStillFitsAndOnlyTheOwnerCanAskForATemplate() throws Exception {
        Account owner = register();
        Account other = register();
        String longTitle = "T".repeat(200);
        String created = mvc.perform(as(owner, post(HANDOFFS).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"" + longTitle + "\",\"senderName\":\"S\",\"recipientName\":\"R\",\"recipientEmail\":\"r@example.test\"}")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(created, "$.id")).longValue();

        String title = JsonPath.read(mvc.perform(as(owner, get(HANDOFFS + "/" + id + "/template"))).andReturn().getResponse()
                .getContentAsString(), "$.title");
        assertThat(title).hasSize(200).endsWith(" (copy)");

        mvc.perform(as(other, get(HANDOFFS + "/" + id + "/template"))).andExpect(status().isForbidden());
        mvc.perform(get(HANDOFFS + "/" + id + "/template")).andExpect(status().isUnauthorized());
        mvc.perform(as(owner, get(HANDOFFS + "/999999/template"))).andExpect(status().isNotFound());
    }
}
