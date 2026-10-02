package com.handoffly.documentcheck;

import com.handoffly.support.staff.SupportRole;
import com.handoffly.testsupport.ApiTestBase;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

import static com.handoffly.testsupport.TestDocuments.csv;
import static com.handoffly.testsupport.TestDocuments.file;
import static com.handoffly.testsupport.TestDocuments.pdf;
import static com.handoffly.testsupport.TestDocuments.xlsx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Importing an item list into the New Handoff screen: the same file reading as HandoffCheck, a preview to review,
 * and — above all — no handoff created and no draft workflow bypassed: the items go through the normal create call.
 */
class ItemImportTest extends ApiTestBase {

    private static final String IMPORT = "/api/v1/handoff-check/import-items";

    private ResultActions importFile(Account customer, MockMultipartFile file) throws Exception {
        return mvc.perform(as(customer, multipart(IMPORT).file(file)));
    }

    private long handoffCount(Account customer) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM handoff WHERE owner_user_id = ?", Long.class, customer.id());
    }

    // ------------------------------------------------------------------ reading

    @Test
    void aCsvWithAHeaderRowBecomesReviewableLines() throws Exception {
        Account customer = register();
        importFile(customer, csv("items.csv", "Item,Qty\nChairs,500\nTables,80\nSpeakers,4.5\n")).andExpect(status().isOk())
                .andExpect(jsonPath("$.fileName").value("items.csv"))
                .andExpect(jsonPath("$.lines.length()").value(3))
                .andExpect(jsonPath("$.lines[0].name").value("Chairs"))
                .andExpect(jsonPath("$.lines[0].quantity").value(500))
                .andExpect(jsonPath("$.lines[2].quantity").value(4.5))
                .andExpect(jsonPath("$.lines[0].duplicate").value(false))
                .andExpect(jsonPath("$.skippedRows").value(0));
    }

    @Test
    void anExcelFileAndOtherColumnNamesAreUnderstoodToo() throws Exception {
        Account customer = register();
        importFile(customer, xlsx("stock.xlsx", new String[]{"Description", "Count", "Notes"},
                new String[]{"Laptops", "12", "new"}, new String[]{"Chargers", "12", ""})).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[0].name").value("Laptops"))
                .andExpect(jsonPath("$.lines[1].quantity").value(12));
    }

    @Test
    void headersWithPunctuationUnitsOrExtraWordsAreStillRecognisedSoTheRightColumnIsUsed() throws Exception {
        Account customer = register();
        // "Product Code" is not the item: the "Item Description" column is, and "Qty." is the quantity.
        importFile(customer, csv("codes.csv", "Product Code,Item Description,Qty.\nP1,Chair,5\nP2,Table,2\n")).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[0].name").value("Chair"))
                .andExpect(jsonPath("$.lines[0].quantity").value(5))
                .andExpect(jsonPath("$.lines[1].name").value("Table"))
                .andExpect(jsonPath("$.skippedRows").value(0));
        // The quantity may come first, and its header may carry a unit.
        importFile(customer, csv("units.csv", "QUANTITY (pcs),Product Name\n5,Chair\n2,Table\n")).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[1].name").value("Table"))
                .andExpect(jsonPath("$.lines[1].quantity").value(2));
    }

    @Test
    void semicolonSeparatedAndHeaderlessFilesWork() throws Exception {
        Account customer = register();
        importFile(customer, csv("nohead.csv", "Table;3\nChair;12\n")).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(2));
    }

    // ------------------------------------------------------------------ rows that cannot become items

    @Test
    void rowsThatCannotBecomeItemsAreLeftOutAndCountedNeverSilentlyDropped() throws Exception {
        Account customer = register();
        String data = "Item,Qty\nGood,5\nNo quantity,\nWords,abc\nZero,0\nNegative,-2\nTiny decimals,1.2345\n,7\nAlso good,2\n";
        importFile(customer, csv("mixed.csv", data)).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[0].name").value("Good"))
                .andExpect(jsonPath("$.lines[1].name").value("Also good"))
                .andExpect(jsonPath("$.skippedRows").value(6));
    }

    @Test
    void aNameTooLongForAnItemIsSkippedRatherThanFailingTheWholeFile() throws Exception {
        Account customer = register();
        importFile(customer, csv("long.csv", "Item,Qty\n" + "N".repeat(301) + ",1\nShort,1\n")).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(1)).andExpect(jsonPath("$.skippedRows").value(1));
    }

    @Test
    void repeatedNamesAreFlaggedButNeverMerged() throws Exception {
        Account customer = register();
        importFile(customer, csv("dups.csv", "Item,Qty\nChair,2\nchair ,3\nCHAIR!,1\nTable,1\n")).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(4))
                .andExpect(jsonPath("$.lines[0].duplicate").value(false))
                .andExpect(jsonPath("$.lines[1].duplicate").value(true))
                .andExpect(jsonPath("$.lines[1].quantity").value(3))     // each row keeps its own quantity
                .andExpect(jsonPath("$.lines[2].duplicate").value(true))
                .andExpect(jsonPath("$.lines[3].duplicate").value(false));
    }

    // ------------------------------------------------------------------ files that cannot be used

    @Test
    void onlySpreadsheetsAreAcceptedForImport() throws Exception {
        Account customer = register();
        for (MockMultipartFile f : List.of(pdf("list.pdf", "Table 1", "Chair 2"), file("list.txt", "Table,1".getBytes()),
                file("list.docx", new byte[]{'P', 'K', 3, 4}), file("noextension", "Table,1".getBytes()))) {
            importFile(customer, f).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(containsString("Upload a .csv or .xlsx file")));
        }
    }

    @Test
    void emptyBrokenAndUnusableFilesGetAClearMessage() throws Exception {
        Account customer = register();
        importFile(customer, file("empty.csv", new byte[0])).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("No file")));
        importFile(customer, file("broken.xlsx", "not a workbook".getBytes())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("could not be read")));
        // No quantity column at all, or nothing but invalid quantities: nothing to import.
        importFile(customer, csv("words.csv", "just,some,words\nand,more,words")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("No usable items")));
        importFile(customer, csv("zeros.csv", "Item,Qty\nA,0\nB,0\n")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("quantities above zero")));
        mvc.perform(as(customer, multipart(IMPORT))).andExpect(status().isBadRequest());   // no file part at all
    }

    @Test
    void tooManyRowsAreRefused() throws Exception {
        Account customer = register();
        StringBuilder big = new StringBuilder("Item,Qty\n");
        for (int i = 0; i < 2001; i++) big.append("Item ").append(i).append(",1\n");
        importFile(customer, csv("big.csv", big.toString())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("too many rows")));
    }

    @Test
    void importNeedsASignedInCustomer() throws Exception {
        mvc.perform(multipart(IMPORT).file(csv("items.csv", "Item,Qty\nA,1\n"))).andExpect(status().isUnauthorized());
        StaffAccount staff = registerStaff(SupportRole.ADMIN);
        mvc.perform(as(staff, multipart(IMPORT).file(csv("items.csv", "Item,Qty\nA,1\n")))).andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ it goes through the normal flow

    @Test
    void importingCreatesNoHandoffAndTheItemsGoThroughTheOrdinaryCreateCall() throws Exception {
        Account customer = register();
        long before = handoffCount(customer);
        long sequence = jdbc.queryForObject("SELECT handoff_sequence FROM app_user WHERE id = ?", Long.class, customer.id());

        String preview = importFile(customer, csv("items.csv", "Item,Qty\nChairs,500\nTables,80\n")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        importFile(customer, csv("items.csv", "Item,Qty\nChairs,500\nTables,80\n")).andExpect(status().isOk());   // again: still nothing
        assertThat(handoffCount(customer)).isEqualTo(before);   // previewing created nothing...
        assertThat(jdbc.queryForObject("SELECT handoff_sequence FROM app_user WHERE id = ?", Long.class, customer.id())).isEqualTo(sequence);

        // ...and what the customer accepts is saved through the one existing create call, as an ordinary draft.
        String items = "[{\"name\":\"%s\",\"quantity\":%s},{\"name\":\"%s\",\"quantity\":%s}]".formatted(
                JsonPath.read(preview, "$.lines[0].name"), JsonPath.read(preview, "$.lines[0].quantity"),
                JsonPath.read(preview, "$.lines[1].name"), JsonPath.read(preview, "$.lines[1].quantity"));
        mvc.perform(as(customer, post("/api/v1/handoffs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Imported list\",\"senderName\":\"Me\",\"recipientName\":\"You\","
                                + "\"recipientEmail\":\"you@example.test\",\"items\":" + items + "}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].name").value("Chairs"))
                .andExpect(jsonPath("$.items[0].outgoing").value(500));
        assertThat(handoffCount(customer)).isEqualTo(before + 1);   // exactly one handoff, from the normal flow
    }

    @Test
    void theOtherHandoffCheckEndpointsStillAcceptWhatTheyAlwaysDid() throws Exception {
        Account customer = register(com.handoffly.user.SubscriptionPlan.YEARLY);   // extraction is the tool: a plan that includes it
        // Return import / standalone extraction still take a PDF; only the item import is limited to spreadsheets.
        mvc.perform(as(customer, multipart("/api/v1/handoff-check/extract").file(pdf("slip.pdf", "Table 1", "Chair 2"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(as(customer, multipart("/api/v1/handoff-check/extract").file(file("notes.txt", "Table,1".getBytes()))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(containsString(".csv, .xlsx or .pdf")));
    }
}
