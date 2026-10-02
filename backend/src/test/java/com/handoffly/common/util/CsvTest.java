package com.handoffly.common.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class CsvTest {

    private static String row(String... cells) {
        StringBuilder out = new StringBuilder();
        Csv.row(out, cells);
        return out.toString();
    }

    @Test
    void aCellIsQuotedOnlyWhenItHoldsACommaAQuoteOrALineBreak() {
        assertThat(row("plain", "two words", "")).isEqualTo("plain,two words,\r\n");
        assertThat(row("a,b", "say \"hi\"", "two\nlines")).isEqualTo("\"a,b\",\"say \"\"hi\"\"\",\"two\nlines\"\r\n");
        assertThat(row((String) null, "x")).isEqualTo(",x\r\n");
    }

    @Test
    void noCellsIsABlankLine() {
        assertThat(row()).isEqualTo("\r\n");
    }

    @Test
    void textThatWouldRunAsAFormulaIsMadeIntoPlainText() {
        for (String formula : new String[]{"=1+1", "+1", "-1", "@SUM(A1)", "\tcmd", "\rcmd"}) {
            assertThat(Csv.safe(formula)).isEqualTo("'" + formula);
        }
        assertThat(Csv.safe("Chairs")).isEqualTo("Chairs");
        assertThat(Csv.safe("a=b")).isEqualTo("a=b");
        assertThat(Csv.safe("")).isEmpty();
        assertThat(Csv.safe(null)).isEmpty();
    }

    @Test
    void aQuantityHasNoTrailingZerosAndNeverUsesExponentNotation() {
        assertThat(Csv.number(new BigDecimal("50.000"))).isEqualTo("50");
        assertThat(Csv.number(new BigDecimal("2.500"))).isEqualTo("2.5");
        assertThat(Csv.number(new BigDecimal("0.000"))).isEqualTo("0");
        assertThat(Csv.number(new BigDecimal("100"))).isEqualTo("100");
        assertThat(Csv.number(new BigDecimal("1E+3"))).isEqualTo("1000");
    }
}
