package com.handoffly.documentcheck;

import com.handoffly.common.error.ApiException;
import com.handoffly.documentcheck.dto.CompareResult;
import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.ColumnText;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfPageEventHelper;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * Turns a finished comparison into a PDF or a CSV: which two files, when, how many items matched, differed, were
 * missing or extra, and every line and field with its status. It only formats the result it is given — it never
 * stores anything and is not an audit report. Text that came from the customer's files is made safe for
 * spreadsheet programs in the CSV (a leading {@code =}, {@code +}, {@code -} or {@code @} would otherwise be run
 * as a formula).
 */
@Service
public class ComparisonExportService {

    /** A finished file, ready to send. */
    public record ExportedFile(String filename, String contentType, byte[] content) {}

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'", Locale.ENGLISH);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.ENGLISH);
    private static final String CSV_BOM = "﻿";   // lets Excel read accents correctly
    private static final String EOL = "\r\n";

    private static final Color PRIMARY = new Color(0x25, 0x63, 0xEB);
    private static final Color INK = new Color(0x1A, 0x20, 0x27);
    private static final Color MUTED = new Color(0x6B, 0x72, 0x80);
    private static final Color BORDER = new Color(0xD9, 0xDE, 0xE5);
    private static final Color PANEL = new Color(0xF1, 0xF5, 0xF9);
    private static final Color WARNING = new Color(0xB4, 0x53, 0x09);
    private static final Color DANGER = new Color(0xB9, 0x1C, 0x1C);
    private static final Font BRAND = new Font(Font.HELVETICA, 18, Font.BOLD, PRIMARY);
    private static final Font TITLE = new Font(Font.HELVETICA, 13, Font.BOLD, INK);
    private static final Font BODY = new Font(Font.HELVETICA, 9, Font.NORMAL, INK);
    private static final Font BODY_BOLD = new Font(Font.HELVETICA, 9, Font.BOLD, INK);
    private static final Font SMALL = new Font(Font.HELVETICA, 8, Font.NORMAL, MUTED);
    private static final Font HEAD = new Font(Font.HELVETICA, 7, Font.BOLD, MUTED);

    public ExportedFile export(CompareResult result, String fileAName, String fileBName,
                               ComparisonExportFormat format, Instant at) {
        String name = "handoffcheck-comparison-" + STAMP.format(at.atOffset(ZoneOffset.UTC)) + "." + format.extension();
        byte[] content = switch (format) {
            case CSV -> csv(result, fileAName, fileBName, at).getBytes(StandardCharsets.UTF_8);
            case PDF -> pdf(result, fileAName, fileBName, at);
        };
        return new ExportedFile(name, format.contentType(), content);
    }

    // ------------------------------------------------------------------ CSV

    String csv(CompareResult r, String fileAName, String fileBName, Instant at) {
        StringBuilder out = new StringBuilder(CSV_BOM);
        row(out, "HandoffCheck comparison");
        row(out, r.referenceLabel(), safe(label(fileAName, r.referenceLabel())));
        row(out, r.targetLabel(), safe(label(fileBName, r.targetLabel())));
        row(out, "Compared on", WHEN.format(at.atOffset(ZoneOffset.UTC)));
        row(out);
        row(out, "Summary");
        CompareResult.Summary s = r.summary();
        row(out, "Matching items", String.valueOf(s.matched()));
        row(out, "Different quantity", String.valueOf(s.mismatched()));
        row(out, "Missing in " + r.targetLabel(), String.valueOf(s.missingInTarget()));
        row(out, "Extra in " + r.targetLabel(), String.valueOf(s.extraInTarget()));
        row(out);
        row(out, "Item", r.referenceLabel() + " quantity", r.targetLabel() + " quantity",
                "Difference (" + r.targetLabel() + " minus " + r.referenceLabel() + ")", "Status");
        for (CompareResult.LineComparison l : r.lines()) {
            row(out, safe(itemLabel(l, r)), number(l.referenceQuantity()), number(l.targetQuantity()), number(l.difference()),
                    statusText(l.status(), r.targetLabel()));
        }
        if (!r.fields().isEmpty()) {
            row(out);
            row(out, "Field", r.referenceLabel() + " value", r.targetLabel() + " value", "Status");
            for (CompareResult.FieldComparison f : r.fields()) {
                row(out, safe(f.label()), safe(f.referenceValue()), safe(f.targetValue()), statusText(f.status(), r.targetLabel()));
            }
        }
        return out.toString();
    }

    private static void row(StringBuilder out, String... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) out.append(',');
            out.append(quote(cells[i]));
        }
        out.append(EOL);
    }

    private static String quote(String cell) {
        if (cell == null || cell.isEmpty()) return "";
        boolean needsQuotes = cell.contains(",") || cell.contains("\"") || cell.contains("\n") || cell.contains("\r");
        return needsQuotes ? "\"" + cell.replace("\"", "\"\"") + "\"" : cell;
    }

    /**
     * Text that came from the customer's files, made harmless in a spreadsheet: a cell that starts with a formula
     * character gets a leading apostrophe, so it is shown as text and never evaluated.
     */
    static String safe(String text) {
        if (text == null || text.isEmpty()) return "";
        char first = text.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r'
                ? "'" + text : text;
    }

    private static String label(String given, String fallback) {
        return given == null || given.isBlank() ? fallback : given.trim();
    }

    // ------------------------------------------------------------------ PDF

    byte[] pdf(CompareResult r, String fileAName, String fileBName, Instant at) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 36, 36, 36, 48);
        try {
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setPageEvent(new PageNumbers());
            doc.open();

            Paragraph brand = new Paragraph("Hand", BRAND);
            brand.add(new org.openpdf.text.Chunk("Offly", new Font(Font.HELVETICA, 18, Font.BOLD, INK)));
            doc.add(brand);
            Paragraph title = new Paragraph("HandoffCheck comparison", TITLE);
            title.setSpacingAfter(8);
            doc.add(title);

            PdfPTable meta = new PdfPTable(new float[]{1.3f, 5f});
            meta.setWidthPercentage(100);
            meta.setSpacingAfter(10);
            metaRow(meta, r.referenceLabel(), label(fileAName, r.referenceLabel()));
            metaRow(meta, r.targetLabel(), label(fileBName, r.targetLabel()));
            metaRow(meta, "Compared on", WHEN.format(at.atOffset(ZoneOffset.UTC)));
            doc.add(meta);

            CompareResult.Summary s = r.summary();
            PdfPTable stats = new PdfPTable(4);
            stats.setWidthPercentage(100);
            stats.setSpacingAfter(12);
            stat(stats, "Matching", s.matched(), INK);
            stat(stats, "Different quantity", s.mismatched(), s.mismatched() > 0 ? WARNING : INK);
            stat(stats, "Missing in " + r.targetLabel(), s.missingInTarget(), s.missingInTarget() > 0 ? DANGER : INK);
            stat(stats, "Extra in " + r.targetLabel(), s.extraInTarget(), s.extraInTarget() > 0 ? DANGER : INK);
            doc.add(stats);

            PdfPTable lines = new PdfPTable(new float[]{4f, 1.4f, 1.4f, 1.4f, 2.4f});
            lines.setWidthPercentage(100);
            lines.setHeaderRows(1);
            for (String h : new String[]{"ITEM", r.referenceLabel().toUpperCase(Locale.ROOT),
                    r.targetLabel().toUpperCase(Locale.ROOT), "DIFFERENCE", "STATUS"}) {
                lines.addCell(head(h, h.equals("ITEM") || h.equals("STATUS") ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT));
            }
            for (CompareResult.LineComparison l : r.lines()) {
                lines.addCell(cell(itemLabel(l, r), BODY_BOLD, Element.ALIGN_LEFT));
                lines.addCell(cell(number(l.referenceQuantity()), BODY, Element.ALIGN_RIGHT));
                lines.addCell(cell(number(l.targetQuantity()), BODY, Element.ALIGN_RIGHT));
                lines.addCell(cell(number(l.difference()), BODY, Element.ALIGN_RIGHT));
                lines.addCell(cell(statusText(l.status(), r.targetLabel()), statusFont(l.status()), Element.ALIGN_LEFT));
            }
            doc.add(lines);

            if (!r.fields().isEmpty()) {
                Paragraph fieldsTitle = new Paragraph("Fields", TITLE);
                fieldsTitle.setSpacingBefore(14);
                fieldsTitle.setSpacingAfter(4);
                doc.add(fieldsTitle);
                PdfPTable fields = new PdfPTable(new float[]{2.5f, 3f, 3f, 2.4f});
                fields.setWidthPercentage(100);
                fields.setHeaderRows(1);
                for (String h : new String[]{"FIELD", r.referenceLabel().toUpperCase(Locale.ROOT) + " VALUE",
                        r.targetLabel().toUpperCase(Locale.ROOT) + " VALUE", "STATUS"}) {
                    fields.addCell(head(h, Element.ALIGN_LEFT));
                }
                for (CompareResult.FieldComparison f : r.fields()) {
                    fields.addCell(cell(f.label(), BODY_BOLD, Element.ALIGN_LEFT));
                    fields.addCell(cell(f.referenceValue(), BODY, Element.ALIGN_LEFT));
                    fields.addCell(cell(f.targetValue(), BODY, Element.ALIGN_LEFT));
                    fields.addCell(cell(statusText(f.status(), r.targetLabel()), statusFont(f.status()), Element.ALIGN_LEFT));
                }
                doc.add(fields);
            }

            Paragraph note = new Paragraph("This is a comparison of the two files as they were read and reviewed in HandoffCheck. "
                    + "It is not stored by HandOffly and does not change any handoff or return.", SMALL);
            note.setSpacingBefore(14);
            doc.add(note);
            doc.close();
        } catch (DocumentException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "pdf_failed", "The comparison PDF could not be created.");
        }
        return out.toByteArray();
    }

    private static void metaRow(PdfPTable table, String label, String value) {
        table.addCell(plain(label, SMALL));
        table.addCell(plain(value, BODY_BOLD));
    }

    private static void stat(PdfPTable table, String label, int value, Color color) {
        PdfPCell cell = new PdfPCell();
        cell.setBorderColor(BORDER);
        cell.setBackgroundColor(PANEL);
        cell.setPadding(6);
        cell.addElement(new Paragraph(label.toUpperCase(Locale.ROOT), HEAD));
        cell.addElement(new Paragraph(String.valueOf(value), new Font(Font.HELVETICA, 16, Font.BOLD, color)));
        table.addCell(cell);
    }

    private static PdfPCell plain(String text, Font font) {
        PdfPCell c = new PdfPCell(new Phrase(text == null ? "" : text, font));
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(2);
        return c;
    }

    private static PdfPCell head(String text, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, HEAD));
        c.setHorizontalAlignment(align);
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(BORDER);
        c.setPadding(4);
        return c;
    }

    private static PdfPCell cell(String text, Font font, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text == null ? "" : text, font));
        c.setHorizontalAlignment(align);
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(BORDER);
        c.setPadding(4);
        return c;
    }

    private static Font statusFont(CompareResult.MatchStatus status) {
        return switch (status) {
            case MATCH -> BODY;
            case MISMATCH -> new Font(Font.HELVETICA, 9, Font.BOLD, WARNING);
            case MISSING_IN_TARGET, EXTRA_IN_TARGET -> new Font(Font.HELVETICA, 9, Font.BOLD, DANGER);
        };
    }

    /** "Page n" at the foot of every page. */
    private static final class PageNumbers extends PdfPageEventHelper {
        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            ColumnText.showTextAligned(cb, Element.ALIGN_RIGHT, new Phrase("Page " + writer.getPageNumber(), SMALL),
                    document.right(), document.bottom() - 18, 0);
        }
    }

    // ------------------------------------------------------------------ shared wording

    /** The item's name, with how each file wrote it when they wrote it differently (an accepted spelling match). */
    private static String itemLabel(CompareResult.LineComparison l, CompareResult r) {
        if (l.referenceName() == null && l.targetName() == null) return l.name();
        StringBuilder label = new StringBuilder(l.name()).append(" (");
        if (l.referenceName() != null) label.append(r.referenceLabel()).append(": ").append(l.referenceName());
        if (l.referenceName() != null && l.targetName() != null) label.append("; ");
        if (l.targetName() != null) label.append(r.targetLabel()).append(": ").append(l.targetName());
        return label.append(')').toString();
    }

    private static String statusText(CompareResult.MatchStatus status, String targetLabel) {
        return switch (status) {
            case MATCH -> "Match";
            case MISMATCH -> "Different";
            case MISSING_IN_TARGET -> "Missing in " + targetLabel;
            case EXTRA_IN_TARGET -> "Extra in " + targetLabel;
        };
    }

    /** A quantity as plain text without trailing zeros; empty when there is none. */
    private static String number(BigDecimal value) {
        if (value == null) return "";
        BigDecimal stripped = value.stripTrailingZeros();
        return (stripped.signum() == 0 ? BigDecimal.ZERO : stripped).toPlainString();
    }
}
