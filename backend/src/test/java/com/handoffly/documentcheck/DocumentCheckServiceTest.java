package com.handoffly.documentcheck;

import com.handoffly.common.config.HandOfflyProperties;
import com.handoffly.common.error.BadRequestException;
import com.handoffly.documentcheck.dto.CompareRequest;
import com.handoffly.documentcheck.dto.CompareResult;
import com.handoffly.documentcheck.dto.DocumentField;
import com.handoffly.documentcheck.dto.DocumentLine;
import com.handoffly.testsupport.TestDocuments;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static com.handoffly.testsupport.TestDocuments.csv;
import static com.handoffly.testsupport.TestDocuments.pdf;
import static com.handoffly.testsupport.TestDocuments.xlsx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Document-vs-document comparison needs no handoff, so the service is exercised directly.
 * Mirrors the brief's quotation-vs-handoff example.
 */
class DocumentCheckServiceTest {

    private final DocumentCheckService service = new DocumentCheckService(null, null);
    /** Same engine, with the real extractor, for the file-based tests below. */
    private final DocumentCheckService filesService =
            new DocumentCheckService(null, new DocumentLineExtractor(new HandOfflyProperties()));

    @Test
    void detectsMatchesAndMismatches() {
        CompareRequest request = new CompareRequest(
                "Quotation",
                List.of(
                        new DocumentLine("Bedsheets", new BigDecimal("200")),
                        new DocumentLine("Chairs", new BigDecimal("500")),
                        new DocumentLine("Tables", new BigDecimal("80"))),
                List.of(new DocumentField("Delivery date", "2025-09-28")),
                null,
                "Handoff",
                List.of(
                        new DocumentLine("Bedsheets", new BigDecimal("200")),
                        new DocumentLine("Chairs", new BigDecimal("500")),
                        new DocumentLine("Tables", new BigDecimal("70"))),
                List.of(new DocumentField("Delivery date", "2025-09-30")));

        CompareResult result = service.compare(null, request);

        assertThat(result.summary().matched()).isEqualTo(2);
        assertThat(result.summary().mismatched()).isEqualTo(1);
        assertThat(result.summary().allMatch()).isFalse();

        CompareResult.LineComparison tables = result.lines().stream()
                .filter(l -> l.name().equals("Tables")).findFirst().orElseThrow();
        assertThat(tables.status()).isEqualTo(CompareResult.MatchStatus.MISMATCH);
        assertThat(tables.referenceQuantity()).isEqualByComparingTo("80");
        assertThat(tables.targetQuantity()).isEqualByComparingTo("70");

        CompareResult.FieldComparison delivery = result.fields().getFirst();
        assertThat(delivery.status()).isEqualTo(CompareResult.MatchStatus.MISMATCH);
    }

    @Test
    void namesMatchIgnoringCaseWhitespaceAndSimplePunctuationButNotFuzzily() {
        CompareRequest request = new CompareRequest(
                "A",
                List.of(new DocumentLine("  JOKER-dress ", new BigDecimal("2")),
                        new DocumentLine("Table", new BigDecimal("1"))),
                List.of(), null, "B",
                List.of(new DocumentLine("Joker  Dress", new BigDecimal("2")),
                        new DocumentLine("Tables", new BigDecimal("1"))),
                List.of());

        CompareResult result = service.compare(null, request);

        assertThat(result.summary().matched()).isEqualTo(1);        // Joker dress
        assertThat(result.summary().missingInTarget()).isEqualTo(1); // "Table" is not "Tables"
        assertThat(result.summary().extraInTarget()).isEqualTo(1);
    }

    @Test
    void detectsMissingAndExtraLines() {
        CompareRequest request = new CompareRequest(
                "A", List.of(new DocumentLine("Only in ref", new BigDecimal("1"))), List.of(),
                null, "B", List.of(new DocumentLine("Only in target", new BigDecimal("2"))), List.of());

        CompareResult result = service.compare(null, request);

        assertThat(result.summary().missingInTarget()).isEqualTo(1);
        assertThat(result.summary().extraInTarget()).isEqualTo(1);
        assertThat(result.summary().allMatch()).isFalse();
    }

    // ------------------------------------------------ Standalone file-vs-file comparison

    private static final String[][] FILE_A_ROWS = {
            {"Item", "Qty"}, {"Table", "100"}, {"Chair", "200"}, {"Curtain", "50"}, {"Light", "10"}};
    private static final String[][] FILE_B_ROWS = {
            {"Item", "Qty"}, {"Table", "95"}, {"Chair", "200"}, {"Curtain", "40"}, {"Generator", "2"}};

    private static String csvText(String[][] rows) {
        return java.util.Arrays.stream(rows).map(r -> String.join(",", r)).collect(java.util.stream.Collectors.joining("\n"));
    }

    private static String[] pdfLines(String[][] rows) {
        return java.util.Arrays.stream(rows).skip(1).map(r -> r[0] + " " + r[1]).toArray(String[]::new);
    }

    private static CompareResult.LineComparison line(CompareResult result, String name) {
        return result.lines().stream().filter(l -> l.name().equals(name)).findFirst().orElseThrow();
    }

    /** Table 100→95, Chair same, Curtain 50→40, Light only in A, Generator only in B. */
    private static void assertTypicalDifferences(CompareResult result) {
        assertThat(result.referenceLabel()).isEqualTo("File A");
        assertThat(result.targetLabel()).isEqualTo("File B");
        assertThat(result.summary().matched()).isEqualTo(1);
        assertThat(result.summary().mismatched()).isEqualTo(2);
        assertThat(result.summary().missingInTarget()).isEqualTo(1);
        assertThat(result.summary().extraInTarget()).isEqualTo(1);
        assertThat(line(result, "Table").status()).isEqualTo(CompareResult.MatchStatus.MISMATCH);
        assertThat(line(result, "Table").difference()).isEqualByComparingTo("-5");
        assertThat(line(result, "Chair").status()).isEqualTo(CompareResult.MatchStatus.MATCH);
        assertThat(line(result, "Chair").difference()).isEqualByComparingTo("0");
        assertThat(line(result, "Curtain").difference()).isEqualByComparingTo("-10");
        assertThat(line(result, "Light").status()).isEqualTo(CompareResult.MatchStatus.MISSING_IN_TARGET);
        assertThat(line(result, "Light").difference()).isNull();
        assertThat(line(result, "Generator").status()).isEqualTo(CompareResult.MatchStatus.EXTRA_IN_TARGET);
        assertThat(line(result, "Generator").difference()).isNull();
    }

    @Test
    void comparesTwoXlsxFiles() {
        assertTypicalDifferences(filesService.compareFiles(xlsx("a.xlsx", FILE_A_ROWS), xlsx("b.xlsx", FILE_B_ROWS)));
    }

    @Test
    void comparesTwoCsvFiles() {
        assertTypicalDifferences(filesService.compareFiles(
                csv("a.csv", csvText(FILE_A_ROWS)), csv("b.csv", csvText(FILE_B_ROWS))));
    }

    @Test
    void comparesTwoPdfFiles() {
        assertTypicalDifferences(filesService.compareFiles(
                pdf("a.pdf", pdfLines(FILE_A_ROWS)), pdf("b.pdf", pdfLines(FILE_B_ROWS))));
    }

    @Test
    void comparesDifferentFormatsXlsxAgainstPdf() {
        assertTypicalDifferences(filesService.compareFiles(
                xlsx("quotation.xlsx", FILE_A_ROWS), pdf("po.pdf", pdfLines(FILE_B_ROWS))));
    }

    @Test
    void identicalFilesAllMatch() {
        CompareResult result = filesService.compareFiles(
                csv("a.csv", csvText(FILE_A_ROWS)), xlsx("b.xlsx", FILE_A_ROWS));
        assertThat(result.summary().allMatch()).isTrue();
        assertThat(result.summary().matched()).isEqualTo(4);
    }

    @Test
    void reportsQuantityMismatchMissingAndExtraIndividually() {
        CompareResult result = filesService.compareFiles(
                csv("a.csv", "Item,Qty\nTable,5\nLight,1"), csv("b.csv", "Item,Qty\nTable,6\nGenerator,2"));
        assertThat(line(result, "Table").status()).isEqualTo(CompareResult.MatchStatus.MISMATCH);
        assertThat(line(result, "Table").difference()).isEqualByComparingTo("1");
        assertThat(line(result, "Light").status()).isEqualTo(CompareResult.MatchStatus.MISSING_IN_TARGET);
        assertThat(line(result, "Generator").status()).isEqualTo(CompareResult.MatchStatus.EXTRA_IN_TARGET);
    }

    @Test
    void rejectsEmptyMalformedUnsupportedAndUnreadableFilesNamingTheFile() {
        var good = csv("a.csv", csvText(FILE_A_ROWS));
        assertThatThrownBy(() -> filesService.compareFiles(good, TestDocuments.file("empty.csv", new byte[0])))
                .isInstanceOf(BadRequestException.class).hasMessageStartingWith("File B:");
        assertThatThrownBy(() -> filesService.compareFiles(TestDocuments.file("bad.xlsx", "nope".getBytes()), good))
                .isInstanceOf(BadRequestException.class).hasMessageStartingWith("File A:")
                .hasMessageContaining("valid .xlsx");
        assertThatThrownBy(() -> filesService.compareFiles(good, TestDocuments.file("notes.txt", "Table,1".getBytes())))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Unsupported");
        // A PDF with no text layer (e.g. a scan) is refused rather than compared as empty.
        assertThatThrownBy(() -> filesService.compareFiles(good, pdf("scan.pdf")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Scanned");
    }

    @Test
    void correctedLinesAreWhatGetsCompared() {
        // Review step: extract both, fix a misread quantity, then compare the corrected data.
        List<DocumentLine> a = filesService.extractLines(csv("a.csv", "Item,Qty\nTable,100\nChair,200"));
        List<DocumentLine> b = new java.util.ArrayList<>(
                filesService.extractLines(csv("b.csv", "Item,Qty\nTable,95\nChair,200")));
        assertThat(filesService.compare(null, new CompareRequest("File A", a, List.of(), null,
                "File B", b, List.of())).summary().allMatch()).isFalse();

        b.set(0, new DocumentLine("Table", new BigDecimal("100")));   // user corrects 95 -> 100
        CompareResult corrected = filesService.compare(null, new CompareRequest("File A", a, List.of(), null,
                "File B", b, List.of()));
        assertThat(corrected.summary().allMatch()).isTrue();
    }
}
