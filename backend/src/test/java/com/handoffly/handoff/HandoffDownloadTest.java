package com.handoffly.handoff;

import com.handoffly.testsupport.ApiTestBase;
import com.handoffly.user.SubscriptionPlan;
import com.jayway.jsonpath.JsonPath;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The Proof-of-Handoff record can be downloaded — as a PDF and as an Excel workbook — whatever state the handoff is in, from a draft to a
 * closed one, and only by its owner.
 */
class HandoffDownloadTest extends ApiTestBase {

    private static final String XLSX = HandoffExcelService.CONTENT_TYPE;

    private long create(Account owner, String title) throws Exception {
        String body = """
                {"title":"%s","senderName":"Sender","recipientName":"Recipient","recipientEmail":"recipient@example.test",
                 "items":[{"name":"Chairs","quantity":10,"unit":"pcs","sku":"CH-1"},{"name":"Tables","quantity":2,"unit":"pcs"}]}"""
                .formatted(title);
        return ((Number) JsonPath.read(mvc.perform(as(owner, post("/api/v1/handoffs").contentType(MediaType.APPLICATION_JSON).content(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    private void send(Account owner, String path) throws Exception {
        mvc.perform(as(owner, post(path))).andExpect(status().isOk());
    }

    private void accept() throws Exception {
        mvc.perform(post("/api/v1/r/" + emailSender.extractLastToken() + "/accept")
                .contentType(MediaType.APPLICATION_JSON).content("{\"acknowledgementName\":\"Recipient\"}")).andExpect(status().isOk());
    }

    private int itemId(Account owner, long id, int index) throws Exception {
        return JsonPath.read(mvc.perform(as(owner, get("/api/v1/handoffs/" + id))).andReturn().getResponse().getContentAsString(),
                "$.items[" + index + "].id");
    }

    private void giveBack(Account owner, long id, int itemId, int quantity) throws Exception {
        mvc.perform(as(owner, post("/api/v1/handoffs/" + id + "/returns")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":[{\"itemId\":" + itemId + ",\"quantity\":" + quantity + ",\"condition\":\"GOOD\"}]}")))
                .andExpect(status().isCreated());
    }

    private MvcResult download(Account who, long id, String format) throws Exception {
        return mvc.perform(as(who, get("/api/v1/handoffs/" + id + "/" + format))).andExpect(status().isOk()).andReturn();
    }

    /** What the sheet shows, row by row: the cells of a row joined with " | " (empty cells left out; a blank row is an empty line). */
    private static List<String> lines(Sheet sheet) {
        DataFormatter formatter = new DataFormatter(Locale.ENGLISH);
        List<String> rows = new ArrayList<>();
        for (int r = 0; r <= sheet.getLastRowNum(); r++) {   // blank spacer rows are kept, so a position here is the row number
            Row row = sheet.getRow(r);
            List<String> cells = new ArrayList<>();
            for (Cell cell : row == null ? List.<Cell>of() : row) {
                String text = formatter.formatCellValue(cell).trim();
                if (!text.isEmpty()) cells.add(text);
            }
            rows.add(String.join(" | ", cells));
        }
        return rows;
    }

    /** Text with its spacing and capitals levelled, so the PDF's small-capital labels and the sheet's can be compared. */
    private static String squash(String text) {
        return text.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ENGLISH);
    }

    private static Workbook open(MvcResult result) throws IOException {
        return new XSSFWorkbook(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()));
    }

    // ------------------------------------------------------------------ every status

    @Test
    void bothFormatsCanBeDownloadedInEveryStatus() throws Exception {
        Account owner = register();
        Map<String, Long> byStatus = new LinkedHashMap<>();

        byStatus.put("DRAFT", create(owner, "Draft"));

        long awaiting = create(owner, "Awaiting");
        send(owner, "/api/v1/handoffs/" + awaiting + "/submit");
        byStatus.put("AWAITING_RECIPIENT", awaiting);

        long active = create(owner, "Active");
        send(owner, "/api/v1/handoffs/" + active + "/submit");
        accept();
        byStatus.put("ACTIVE_WITH_RECIPIENT", active);

        long partial = create(owner, "Partial");
        send(owner, "/api/v1/handoffs/" + partial + "/submit");
        accept();
        giveBack(owner, partial, itemId(owner, partial, 0), 4);
        byStatus.put("PARTIALLY_RETURNED", partial);

        long closed = create(owner, "Closed");
        send(owner, "/api/v1/handoffs/" + closed + "/submit");
        accept();
        giveBack(owner, closed, itemId(owner, closed, 0), 10);
        giveBack(owner, closed, itemId(owner, closed, 1), 2);
        send(owner, "/api/v1/handoffs/" + closed + "/close");
        byStatus.put("CLOSED", closed);

        long cancelled = create(owner, "Cancelled");
        send(owner, "/api/v1/handoffs/" + cancelled + "/submit");
        send(owner, "/api/v1/handoffs/" + cancelled + "/cancel");
        byStatus.put("CANCELLED", cancelled);

        for (Map.Entry<String, Long> e : byStatus.entrySet()) {
            long id = e.getValue();
            String status = JsonPath.read(mvc.perform(as(owner, get("/api/v1/handoffs/" + id))).andReturn().getResponse().getContentAsString(), "$.status");
            assertThat(status).as("the handoff for " + e.getKey()).isEqualTo(e.getKey());

            MvcResult pdf = download(owner, id, "pdf");
            assertThat(pdf.getResponse().getContentType()).isEqualTo("application/pdf");
            assertThat(new String(pdf.getResponse().getContentAsByteArray(), 0, 4)).isEqualTo("%PDF");

            MvcResult excel = download(owner, id, "excel");
            assertThat(excel.getResponse().getContentType()).isEqualTo(XLSX);
            assertThat(excel.getResponse().getHeader("Content-Disposition")).contains("attachment").contains(".xlsx");
            try (Workbook book = open(excel)) {
                assertThat(book.getNumberOfSheets()).isEqualTo(1);   // one document, like the PDF
                assertThat(lines(book.getSheetAt(0))).startsWith("HandOffly", "Proof of Handoff");
            }
        }
    }

    // ------------------------------------------------------------------ what the workbook says

    @Test
    void theWorkbookIsTheSinglePageDocumentOfThePdfAndNotAReport() throws Exception {
        Account owner = register();
        long id = create(owner, "=SUM(1+1)");   // a title that looks like a formula
        send(owner, "/api/v1/handoffs/" + id + "/submit");
        accept();
        giveBack(owner, id, itemId(owner, id, 0), 4);

        try (Workbook book = open(download(owner, id, "excel"))) {
            assertThat(book.getNumberOfSheets()).isEqualTo(1);
            Sheet sheet = book.getSheetAt(0);
            List<String> rows = lines(sheet);

            // The PDF's sections, in the PDF's order.
            assertThat(rows).startsWith("HandOffly", "Proof of Handoff", "Reference: " + prefixOf(owner) + "-1", "=SUM(1+1)");
            assertThat(rows.stream().filter(r -> r.startsWith("Interim copy — this handoff is not closed (status: Partially Returned)")).count()).isEqualTo(1);
            assertThat(rows).contains("Given by | Sender", "Received by | Recipient", "Acknowledged by | Recipient");
            assertThat(rows.stream().filter(r -> r.startsWith("Date given | ")).count()).isEqualTo(1);
            int items = rows.indexOf("ITEMS");
            int summary = rows.indexOf("SUMMARY");
            int returns = rows.indexOf("RETURN SUMMARY");
            assertThat(items).isPositive();
            assertThat(summary).isGreaterThan(items);
            assertThat(returns).isGreaterThan(summary);

            // The items table: the PDF's columns, with the figures as numbers.
            assertThat(rows.get(items + 1)).isEqualTo("Item | Given | Returned | Missing | Not yet returned | Condition | Note");
            Row chairs = sheet.getRow(items + 2);
            assertThat(chairs.getCell(0).getStringCellValue()).isEqualTo("Chairs\nSKU CH-1");
            assertThat(chairs.getCell(1).getNumericCellValue()).isEqualTo(10.0);
            assertThat(chairs.getCell(2).getNumericCellValue()).isEqualTo(4.0);
            assertThat(chairs.getCell(3).getNumericCellValue()).isEqualTo(0.0);
            assertThat(chairs.getCell(4).getNumericCellValue()).isEqualTo(6.0);
            assertThat(chairs.getCell(5).getStringCellValue()).isEqualTo("Good");
            Row tables = sheet.getRow(items + 3);
            assertThat(tables.getCell(0).getStringCellValue()).isEqualTo("Tables");
            assertThat(tables.getCell(4).getNumericCellValue()).isEqualTo(2.0);

            // The summary and the one line for the day things came back.
            assertThat(rows.subList(summary, returns)).containsSubsequence("Total given | 12", "Total returned | 4", "Total missing | 0", "Not yet returned | 8");
            assertThat(rows.get(returns + 1)).endsWith(":  4 items returned.");

            // The same document as the PDF: every figure and phrase the sheet shows is in that handoff's PDF too.
            String pdfText;
            try (var pdf = org.apache.pdfbox.Loader.loadPDF(download(owner, id, "pdf").getResponse().getContentAsByteArray())) {
                pdfText = new org.apache.pdfbox.text.PDFTextStripper().getText(pdf);
            }
            for (String phrase : new String[]{"Proof of Handoff", "Reference: " + prefixOf(owner) + "-1", "Interim copy", "Given by", "Received by", "Acknowledged by",
                    "Chairs", "SKU CH-1", "Tables", "Total given", "Total returned", "Total missing", "Not yet returned", "4 items returned", "Good"}) {
                assertThat(squash(pdfText)).as("the PDF also says: " + phrase).contains(squash(phrase));
                assertThat(squash(String.join(" ", rows))).as("the sheet says: " + phrase).contains(squash(phrase));
            }

            // Nothing of a report: no second sheet, no audit history, no other handoffs.
            assertThat(String.join("\n", rows)).doesNotContain("HANDOFF_CREATED").doesNotContain("Field | Value");

            // Text stays text: the formula-looking title is not a formula.
            for (Row row : sheet) {
                for (Cell cell : row) assertThat(cell.getCellType()).isNotEqualTo(CellType.FORMULA);
            }
        }
    }

    @Test
    void aClosedHandoffsWorkbookCarriesNoInterimNoteAndNothingStillOut() throws Exception {
        Account owner = register();
        long id = create(owner, "Done");
        send(owner, "/api/v1/handoffs/" + id + "/submit");
        accept();
        giveBack(owner, id, itemId(owner, id, 0), 10);
        giveBack(owner, id, itemId(owner, id, 1), 2);
        send(owner, "/api/v1/handoffs/" + id + "/close");

        try (Workbook book = open(download(owner, id, "excel"))) {
            List<String> rows = lines(book.getSheetAt(0));
            assertThat(rows).noneMatch(r -> r.startsWith("Interim copy"));
            assertThat(rows).contains("Total given | 12", "Total returned | 12", "Total missing | 0");
            assertThat(rows).noneMatch(r -> r.startsWith("Not yet returned |"));   // as in the PDF, only while something is still out
        }
    }

    @Test
    void aDraftsWorkbookStillHasItsPartiesAndItems() throws Exception {
        Account owner = register();
        long id = create(owner, "Not sent yet");

        try (Workbook book = open(download(owner, id, "excel"))) {
            List<String> rows = lines(book.getSheetAt(0));
            assertThat(rows).contains("Acknowledged by | Not yet acknowledged", "Date given | —");
            assertThat(rows.stream().anyMatch(r -> r.startsWith("Interim copy — this handoff is not closed (status: Draft)"))).isTrue();
            assertThat(rows).anyMatch(r -> r.startsWith("Chairs"));
            assertThat(rows).doesNotContain("RETURN SUMMARY");
        }
    }

    // ------------------------------------------------------------------ who may download

    @Test
    void onlyTheOwnerCanDownloadEitherFormatAndAnyPlanMay() throws Exception {
        Account owner = register(SubscriptionPlan.MONTHLY);   // a download is not a plan feature
        Account other = register();
        long id = create(owner, "Private");

        download(owner, id, "excel");
        for (String format : new String[]{"excel", "pdf"}) {
            mvc.perform(as(other, get("/api/v1/handoffs/" + id + "/" + format))).andExpect(status().isForbidden());
            mvc.perform(get("/api/v1/handoffs/" + id + "/" + format)).andExpect(status().isUnauthorized());
        }
    }
}
