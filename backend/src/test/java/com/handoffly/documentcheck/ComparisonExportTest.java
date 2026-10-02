package com.handoffly.documentcheck;

import com.handoffly.testsupport.ApiTestBase;
import com.handoffly.user.SubscriptionPlan;
import com.jayway.jsonpath.JsonPath;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A finished HandoffCheck comparison as a PDF or CSV: it always agrees with the comparison shown, it is stateless,
 * and it is safe to open in a spreadsheet.
 */
class ComparisonExportTest extends ApiTestBase {

    private static final String CHECK = "/api/v1/handoff-check";

    private static String request(String fileA, String fileB, String comparisonBody) {
        return "{\"fileAName\":\"" + fileA + "\",\"fileBName\":\"" + fileB + "\",\"comparison\":" + comparisonBody + "}";
    }

    /** File A: Table 100, Light 10, Chair 5, Cable 2; File B: Table 98, Light 10, Speaker 4, Cable 2 — one of each outcome. */
    private static final String COMPARISON = """
            {"referenceLabel":"File A","targetLabel":"File B",
             "referenceLines":[{"name":"Table","quantity":100},{"name":"Light","quantity":10},{"name":"Chair","quantity":5},{"name":"Cable","quantity":2}],
             "targetLines":[{"name":"Table","quantity":98},{"name":"Light","quantity":10},{"name":"Speaker","quantity":4},{"name":"Cable","quantity":2}],
             "referenceFields":[{"label":"Delivery date","value":"2026-10-01"}],
             "targetFields":[{"label":"Delivery date","value":"2026-10-03"}]}""";

    private MvcResult export(Account who, String format, String body) throws Exception {
        return mvc.perform(as(who, post(CHECK + "/export").param("format", format)
                .contentType(MediaType.APPLICATION_JSON).content(body))).andReturn();
    }

    private static String text(MvcResult r) {
        return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private static String pdfText(MvcResult r) throws Exception {
        try (PDDocument doc = Loader.loadPDF(r.getResponse().getContentAsByteArray())) {
            return new PDFTextStripper().getText(doc);
        }
    }

    // ------------------------------------------------------------------ CSV

    @Test
    void theCsvCarriesTheFilesTheTimeTheCountsAndEveryLine() throws Exception {
        Account customer = register(SubscriptionPlan.YEARLY);
        MvcResult r = export(customer, "CSV", request("quote.xlsx", "delivery.csv", COMPARISON));

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getContentType()).startsWith("text/csv");
        assertThat(r.getResponse().getHeader("Content-Disposition")).contains("attachment")
                .containsPattern("handoffcheck-comparison-\\d{8}-\\d{4}\\.csv");
        String csv = text(r);
        assertThat(csv).startsWith("﻿");   // Excel reads accents correctly
        assertThat(csv).contains("HandoffCheck comparison", "File A,quote.xlsx", "File B,delivery.csv", "Compared on,",
                "Matching items,2", "Different quantity,1", "Missing in File B,1", "Extra in File B,1");
        assertThat(csv).contains("Table,100,98,-2,Different", "Light,10,10,0,Match", "Chair,5,,,Missing in File B",
                "Speaker,,4,,Extra in File B", "Cable,2,2,0,Match");
        assertThat(csv).contains("Delivery date,2026-10-01,2026-10-03,Different");
    }

    @Test
    void theCsvIsSafeToOpenInASpreadsheet() throws Exception {
        Account customer = register(SubscriptionPlan.YEARLY);
        String evil = """
                {"referenceLabel":"File A","targetLabel":"File B",
                 "referenceLines":[{"name":"=HYPERLINK(\\"http://evil.example\\",\\"x\\")","quantity":1},{"name":"@SUM(A1)","quantity":1},
                                   {"name":"+1+1","quantity":1},{"name":"-2+3","quantity":1},{"name":"Plain, with comma","quantity":1}],
                 "targetLines":[{"name":"x","quantity":1}]}""";
        String csv = text(export(customer, "CSV", request("=cmd.csv", "b.csv", evil)));

        assertThat(csv).contains("'=HYPERLINK(", "'@SUM(A1)", "'+1+1", "'-2+3");   // shown as text, never evaluated
        assertThat(csv).contains("File A,'=cmd.csv");
        assertThat(csv).contains("\"Plain, with comma\"");                          // ordinary quoting still works
        for (String line : csv.split("\r\n")) {
            assertThat(line).doesNotStartWith("=").doesNotStartWith("@").doesNotStartWith("+");
        }
    }

    // ------------------------------------------------------------------ PDF

    @Test
    void thePdfIsAValidDocumentWithTheSameContent() throws Exception {
        Account customer = register(SubscriptionPlan.YEARLY);
        MvcResult r = export(customer, "PDF", request("quote.xlsx", "delivery.csv", COMPARISON));

        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getContentType()).isEqualTo("application/pdf");
        assertThat(r.getResponse().getHeader("Content-Disposition")).containsPattern("handoffcheck-comparison-\\d{8}-\\d{4}\\.pdf");
        assertThat(new String(r.getResponse().getContentAsByteArray(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        String pdf = pdfText(r);
        assertThat(pdf).contains("HandoffCheck comparison", "quote.xlsx", "delivery.csv", "UTC",
                "Table", "Light", "Chair", "Speaker", "Cable", "Delivery date", "2026-10-01", "2026-10-03",
                "Missing in File B", "Extra in File B", "Different", "Match");
        assertThat(pdf).containsPattern("MATCHING\\s+2").containsPattern("DIFFERENT QUANTITY\\s+1")
                .containsPattern("MISSING IN FILE B\\s+1").containsPattern("EXTRA IN FILE B\\s+1");
    }

    @Test
    void aLongComparisonRunsOverSeveralPagesAndStaysValid() throws Exception {
        Account customer = register(SubscriptionPlan.YEARLY);
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            if (i > 0) lines.append(',');
            lines.append("{\"name\":\"Item number ").append(i).append("\",\"quantity\":").append(i + 1).append('}');
        }
        String body = "{\"referenceLines\":[" + lines + "],\"targetLines\":[" + lines.toString().replace("\"quantity\":1}", "\"quantity\":2}") + "]}";
        MvcResult r = export(customer, "PDF", request("a.csv", "b.csv", body));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        try (PDDocument doc = Loader.loadPDF(r.getResponse().getContentAsByteArray())) {
            assertThat(doc.getNumberOfPages()).isGreaterThan(2);
            assertThat(new PDFTextStripper().getText(doc)).contains("Item number 299", "Page 3");
        }
    }

    // ------------------------------------------------------------------ it matches what was shown

    @Test
    void theExportAgreesLineForLineWithTheComparisonThatWasShown() throws Exception {
        Account customer = register(SubscriptionPlan.YEARLY);
        String shown = mvc.perform(as(customer, post(CHECK).contentType(MediaType.APPLICATION_JSON).content(COMPARISON)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String csv = text(export(customer, "CSV", request("a", "b", COMPARISON)));

        List<String> names = JsonPath.read(shown, "$.lines[*].name");
        List<String> statuses = JsonPath.read(shown, "$.lines[*].status");
        List<String> exported = new ArrayList<>();
        boolean inLines = false;
        for (String row : csv.split("\r\n")) {
            if (row.startsWith("Item,")) { inLines = true; continue; }
            if (row.isEmpty()) inLines = false;
            if (inLines) exported.add(row);
        }
        assertThat(exported).hasSize(names.size());
        for (int i = 0; i < names.size(); i++) {
            assertThat(exported.get(i)).startsWith(names.get(i) + ",");
            String word = switch (statuses.get(i)) {
                case "MATCH" -> "Match";
                case "MISMATCH" -> "Different";
                case "MISSING_IN_TARGET" -> "Missing in File B";
                default -> "Extra in File B";
            };
            assertThat(exported.get(i)).endsWith(word);
        }
        assertThat(csv).contains("Matching items," + (int) JsonPath.read(shown, "$.summary.matched"),
                "Different quantity," + (int) JsonPath.read(shown, "$.summary.mismatched"),
                "Missing in File B," + (int) JsonPath.read(shown, "$.summary.missingInTarget"),
                "Extra in File B," + (int) JsonPath.read(shown, "$.summary.extraInTarget"));
    }

    // ------------------------------------------------------------------ standalone and stateless

    @Test
    void exportingNeedsNoHandoffAndCreatesOrChangesNothing() throws Exception {
        Account customer = register(SubscriptionPlan.YEARLY);
        long handoffs = jdbc.queryForObject("SELECT COUNT(*) FROM handoff", Long.class);
        long returns = jdbc.queryForObject("SELECT COUNT(*) FROM return_event", Long.class);
        long attachments = jdbc.queryForObject("SELECT COUNT(*) FROM attachment", Long.class);
        long audits = jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class);

        assertThat(export(customer, "PDF", request("a", "b", COMPARISON)).getResponse().getStatus()).isEqualTo(200);
        assertThat(export(customer, "CSV", request("a", "b", COMPARISON)).getResponse().getStatus()).isEqualTo(200);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM handoff", Long.class)).isEqualTo(handoffs);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM return_event", Long.class)).isEqualTo(returns);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM attachment", Long.class)).isEqualTo(attachments);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event", Long.class)).isEqualTo(audits);
    }

    @Test
    void theNamesAreOptionalAndTheOtherModesAreUntouched() throws Exception {
        Account customer = register(SubscriptionPlan.YEARLY);
        String csv = text(export(customer, "CSV", "{\"comparison\":" + COMPARISON + "}"));
        assertThat(csv).contains("File A,File A", "File B,File B");   // the labels stand in for missing names

        // The existing endpoints still behave as before.
        mvc.perform(as(customer, post(CHECK).contentType(MediaType.APPLICATION_JSON).content(COMPARISON)))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ refused

    @Test
    void badRequestsAreRefusedAndOnlyCustomersMayExport() throws Exception {
        Account customer = register(SubscriptionPlan.YEARLY);
        assertThat(export(customer, "DOC", request("a", "b", COMPARISON)).getResponse().getStatus()).isEqualTo(400);
        assertThat(export(customer, "pdf", request("a", "b", COMPARISON)).getResponse().getStatus()).isEqualTo(400);   // PDF or CSV, as written
        assertThat(export(customer, "CSV", "{}").getResponse().getStatus()).isEqualTo(400);
        assertThat(export(customer, "CSV", request("a", "b", "{\"referenceLines\":[]}")).getResponse().getStatus()).isEqualTo(400);
        assertThat(export(customer, "CSV", request("n".repeat(256), "b", COMPARISON)).getResponse().getStatus()).isEqualTo(400);
        mvc.perform(post(CHECK + "/export").param("format", "CSV").contentType(MediaType.APPLICATION_JSON)
                .content(request("a", "b", COMPARISON))).andExpect(status().isUnauthorized());
        mvc.perform(post(CHECK + "/export").contentType(MediaType.APPLICATION_JSON).content(request("a", "b", COMPARISON)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anotherCustomersHandoffCannotBeComparedAgainstThroughTheExport() throws Exception {
        Account owner = register(SubscriptionPlan.YEARLY);
        Account intruder = register(SubscriptionPlan.YEARLY);
        long handoffId = ((Number) JsonPath.read(createHandoff(owner), "$.id")).longValue();
        String body = "{\"referenceLines\":[{\"name\":\"Laptop\",\"quantity\":1}],\"handoffId\":" + handoffId + "}";

        assertThat(export(intruder, "CSV", request("a", "b", body)).getResponse().getStatus()).isEqualTo(403);
        MvcResult own = export(owner, "CSV", request("a", "b", body));   // the owner may, as with the comparison itself
        assertThat(own.getResponse().getStatus()).isEqualTo(200);
        assertThat(text(own)).contains("Laptop,1,1,0,Match");
    }

    @Test
    void anOversizedComparisonIsRefused() throws Exception {
        Account customer = register(SubscriptionPlan.YEARLY);
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < 2001; i++) {
            if (i > 0) lines.append(',');
            lines.append("{\"name\":\"I").append(i).append("\",\"quantity\":1}");
        }
        assertThat(export(customer, "CSV", request("a", "b", "{\"referenceLines\":[" + lines + "],\"targetLines\":[{\"name\":\"x\",\"quantity\":1}]}"))
                .getResponse().getStatus()).isEqualTo(400);
    }
}
