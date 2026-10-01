package com.handoffly.documentcheck;

import com.handoffly.documentcheck.dto.CompareRequest;
import com.handoffly.documentcheck.dto.CompareResult;
import com.handoffly.documentcheck.dto.DocumentField;
import com.handoffly.documentcheck.dto.DocumentLine;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Document-vs-document comparison needs no handoff, so the service is exercised directly.
 * Mirrors the brief's quotation-vs-handoff example.
 */
class DocumentCheckServiceTest {

    private final DocumentCheckService service = new DocumentCheckService(null);

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
    void detectsMissingAndExtraLines() {
        CompareRequest request = new CompareRequest(
                "A", List.of(new DocumentLine("Only in ref", new BigDecimal("1"))), List.of(),
                null, "B", List.of(new DocumentLine("Only in target", new BigDecimal("2"))), List.of());

        CompareResult result = service.compare(null, request);

        assertThat(result.summary().missingInTarget()).isEqualTo(1);
        assertThat(result.summary().extraInTarget()).isEqualTo(1);
        assertThat(result.summary().allMatch()).isFalse();
    }
}
