package com.handoffly.documentcheck.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * An item list read from a file, for the customer to look over before it is added to a new handoff. Each line
 * is already usable as a handoff item; {@code duplicate} marks a name that appeared earlier in the file (it is
 * flagged, never merged, so the customer decides). {@code skippedRows} counts rows that had something in them
 * but could not become an item, so nothing is dropped silently.
 */
public record ItemImportPreview(String fileName, List<Line> lines, int skippedRows) {

    public record Line(String name, BigDecimal quantity, boolean duplicate) {}
}
