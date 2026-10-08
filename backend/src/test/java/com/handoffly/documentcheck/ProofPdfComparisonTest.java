package com.handoffly.documentcheck;

import com.handoffly.testsupport.ApiTestBase;
import com.handoffly.user.SubscriptionPlan;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.util.List;

import static com.handoffly.testsupport.TestDocuments.file;
import static com.handoffly.testsupport.TestDocuments.xlsx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A real handoff's Proof-of-Handoff PDF (the one the Download PDF button produces) compared in HandoffCheck with a spreadsheet: the result
 * rests on the item and quantity rows alone, and the document's header, parties, dates and footer never appear in it.
 */
class ProofPdfComparisonTest extends ApiTestBase {

    private static final String BODY = """
            {"title":"Education kit","senderName":"Abhinay Kumar","senderOrganization":"School","recipientName":"Kumar",
             "recipientEmail":"k@example.test","purpose":"Term loan",
             "items":[{"name":"Chairs","description":"Blue plastic","quantity":10,"unit":"pcs","sku":"CH-1"},
                      {"name":"Study tables","quantity":2},{"name":"Whiteboard markers","quantity":24}]}""";

    private record Proof(long id, byte[] pdf) {}

    /** A sent, accepted and partly returned handoff, and its PDF exactly as the customer downloads it. */
    private Proof proof(Account owner) throws Exception {
        long id = ((Number) JsonPath.read(mvc.perform(as(owner, post("/api/v1/handoffs").contentType(MediaType.APPLICATION_JSON).content(BODY)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id")).longValue();
        mvc.perform(as(owner, post("/api/v1/handoffs/" + id + "/submit"))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/r/" + emailSender.extractLastToken() + "/accept").contentType(MediaType.APPLICATION_JSON)
                .content("{\"acknowledgementName\":\"Kumar\"}")).andExpect(status().isOk());
        int chairs = JsonPath.read(mvc.perform(as(owner, get("/api/v1/handoffs/" + id))).andReturn().getResponse().getContentAsString(), "$.items[0].id");
        mvc.perform(as(owner, post("/api/v1/handoffs/" + id + "/returns").contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":[{\"itemId\":" + chairs + ",\"quantity\":4,\"condition\":\"GOOD\"}]}"))).andExpect(status().isCreated());
        byte[] pdf = mvc.perform(as(owner, get("/api/v1/handoffs/" + id + "/pdf"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        return new Proof(id, pdf);
    }

    private static MockMultipartFile part(String name, MockMultipartFile source) throws java.io.IOException {
        return new MockMultipartFile(name, source.getOriginalFilename(), "application/octet-stream", source.getBytes());
    }

    @Test
    void theReviewStepGetsTheItemsAndTheirQuantitiesAndNothingFromTheRestOfTheDocument() throws Exception {
        Account owner = register(SubscriptionPlan.HALF_YEARLY);
        Proof proof = proof(owner);

        mvc.perform(as(owner, multipart("/api/v1/handoff-check/extract").file(file("proof.pdf", proof.pdf()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].name").value("Chairs")).andExpect(jsonPath("$[0].quantity").value(10))
                .andExpect(jsonPath("$[1].name").value("Study tables")).andExpect(jsonPath("$[1].quantity").value(2))
                .andExpect(jsonPath("$[2].name").value("Whiteboard markers")).andExpect(jsonPath("$[2].quantity").value(24));
    }

    @Test
    void thePdfAgainstASpreadsheetGivesMatchMismatchMissingAndExtraOnTheItemsAlone() throws Exception {
        Account owner = register(SubscriptionPlan.YEARLY);
        Proof proof = proof(owner);
        MockMultipartFile sheet = xlsx("received.xlsx", new String[]{"Item", "Qty"},
                new String[]{"Chairs", "10"},          // the same
                new String[]{"Study tables", "3"},     // a different quantity
                new String[]{"Projector", "1"});       // not in the PDF; the markers are not in the sheet

        String result = mvc.perform(as(owner, multipart("/api/v1/handoff-check/compare-files")
                        .file(part("fileA", file("proof.pdf", proof.pdf()))).file(part("fileB", sheet))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.totalLines").value(4))
                .andExpect(jsonPath("$.summary.matched").value(1))
                .andExpect(jsonPath("$.summary.mismatched").value(1))
                .andExpect(jsonPath("$.summary.missingInTarget").value(1))
                .andExpect(jsonPath("$.summary.extraInTarget").value(1))
                .andReturn().getResponse().getContentAsString();

        List<String> names = JsonPath.read(result, "$.lines[*].name");
        assertThat(names).containsExactly("Chairs", "Study tables", "Whiteboard markers", "Projector");
        assertThat(result).doesNotContain("Reference").doesNotContain("Abhinay").doesNotContain("Page 1");
        assertThat((List<String>) JsonPath.read(result, "$.lines[*].status"))
                .containsExactly("MATCH", "MISMATCH", "MISSING_IN_TARGET", "EXTRA_IN_TARGET");
    }

    @Test
    void thePdfAgainstItselfMatchesEveryItem() throws Exception {
        Account owner = register(SubscriptionPlan.HALF_YEARLY);
        Proof proof = proof(owner);

        mvc.perform(as(owner, multipart("/api/v1/handoff-check/compare-files")
                        .file(part("fileA", file("a.pdf", proof.pdf()))).file(part("fileB", file("b.pdf", proof.pdf())))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.totalLines").value(3))
                .andExpect(jsonPath("$.summary.allMatch").value(true));
    }

    @Test
    void returnImportReadsTheSamePdfAndMatchesItsItemsToTheHandoff() throws Exception {
        Account owner = register(SubscriptionPlan.MONTHLY);   // Return Import is part of Returns, on every plan
        Proof proof = proof(owner);

        mvc.perform(as(owner, multipart("/api/v1/handoff-check/return-import").file(file("proof.pdf", proof.pdf()))
                        .param("handoffId", String.valueOf(proof.id()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(3))
                .andExpect(jsonPath("$.rows[*].match").value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.is("MATCHED"))))
                .andExpect(jsonPath("$.rows[0].itemName").value("Chairs"));
    }
}
