package com.handoffly.common.util;

import java.math.BigDecimal;

/**
 * The few rules for writing a CSV file by hand: quote a cell only when it needs it, end each line the way spreadsheet
 * programs expect, write quantities without trailing zeros, and make text that came from a customer harmless in a
 * spreadsheet. It formats; it stores nothing and knows nothing about what the cells mean.
 */
public final class Csv {

    /** Lets Excel read accents correctly; goes at the very start of a file. */
    public static final String BOM = "﻿";

    private static final String EOL = "\r\n";

    private Csv() {}

    /** Appends one line: each cell quoted if it holds a comma, a quote or a line break, then a line break. No cells is a blank line. */
    public static void row(StringBuilder out, String... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) out.append(',');
            out.append(quote(cells[i]));
        }
        out.append(EOL);
    }

    /**
     * Text a customer typed, made harmless in a spreadsheet: a cell that starts with a formula character gets a leading
     * apostrophe, so it is shown as text and never evaluated.
     */
    public static String safe(String text) {
        if (text == null || text.isEmpty()) return "";
        char first = text.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r'
                ? "'" + text : text;
    }

    /** A quantity as plain text without trailing zeros (50.000 is 50, 2.500 is 2.5). */
    public static String number(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.signum() == 0 ? BigDecimal.ZERO : stripped).toPlainString();
    }

    private static String quote(String cell) {
        if (cell == null || cell.isEmpty()) return "";
        boolean needsQuotes = cell.contains(",") || cell.contains("\"") || cell.contains("\n") || cell.contains("\r");
        return needsQuotes ? "\"" + cell.replace("\"", "\"\"") + "\"" : cell;
    }
}
