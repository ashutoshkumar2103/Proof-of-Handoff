package com.handoffly.documentcheck;

import com.handoffly.common.config.HandOfflyProperties;
import com.handoffly.common.error.BadRequestException;
import com.handoffly.documentcheck.dto.DocumentLine;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic (no AI/OCR) extraction of item + quantity lines from an uploaded CSV, Excel
 * (.xlsx) or text-based PDF. Every format is first reduced to rows of cells; one shared
 * routine then turns rows into {@link DocumentLine}s, so the parsing rule lives in one place.
 */
@Component
public class DocumentLineExtractor {

    /** Every supported file type, in the order they are listed to the user. */
    private static final List<String> TYPES = List.of("csv", "xlsx", "pdf");
    /** The spreadsheet types: what an item list can be imported from. */
    public static final Set<String> SPREADSHEET_TYPES = Set.of("csv", "xlsx");
    private static final int MAX_LINES = 2000;
    private static final int HEADER_SCAN_ROWS = 20;
    private static final char[] CSV_DELIMITERS = {',', ';', '\t'};

    private static final Set<String> NAME_HEADERS = Set.of(
            "item", "items", "item name", "item description", "item details", "name", "description", "product",
            "product name", "particulars", "article", "material");
    private static final Set<String> QTY_HEADERS = Set.of(
            "qty", "quantity", "count", "nos", "units", "pcs", "returned", "return qty", "return quantity");

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)?");
    private static final Pattern THOUSANDS = Pattern.compile("\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?");
    private static final Pattern CELL_GAP = Pattern.compile("\\t| {2,}");
    private static final Pattern NAME_THEN_QTY = Pattern.compile(
            "^(.*?\\S)\\s*[:xX×\\-–]?\\s*(\\d+(?:[.,]\\d+)?)\\s*(?:pcs|nos|units?)?$", Pattern.CASE_INSENSITIVE);

    private final long maxFileSizeBytes;

    public DocumentLineExtractor(HandOfflyProperties properties) {
        this.maxFileSizeBytes = properties.getStorage().getMaxFileSizeBytes();
    }

    /** The item/quantity lines found, and how many rows that had something in them were left out. */
    public record Extraction(List<DocumentLine> lines, int skippedRows) {}

    public List<DocumentLine> extract(MultipartFile file) {
        Extraction found = extractReport(file, Set.copyOf(TYPES));
        if (found.lines().isEmpty()) {
            throw new BadRequestException("No item and quantity rows could be found in the file.");
        }
        return found.lines();
    }

    /**
     * The one place a file is read into lines, for any caller: checks the size and that its type is one of
     * {@code allowedTypes}, reads it, and reports the rows it could not use. Having no usable rows is not an error
     * here — {@link #extract} makes it one, and an importer can say something more helpful.
     */
    public Extraction extractReport(MultipartFile file, Set<String> allowedTypes) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("No file was provided.");
        }
        if (file.getSize() > maxFileSizeBytes) {
            throw new BadRequestException("File exceeds the maximum allowed size.");
        }
        String extension = extensionOf(file.getOriginalFilename());
        if (!allowedTypes.contains(extension)) {
            throw new BadRequestException("Unsupported file type. Upload a " + describe(allowedTypes) + " file.");
        }

        List<List<String>> rows;
        try {
            byte[] data = file.getBytes();
            rows = switch (extension) {
                case "csv" -> csvRows(data);
                case "xlsx" -> xlsxRows(data);
                default -> pdfRows(data);
            };
        } catch (IOException | RuntimeException e) {
            if (e instanceof BadRequestException bad) throw bad;
            throw new BadRequestException("The file could not be read as a valid ." + extension + " document.");
        }

        Extraction found = toLines(rows);
        if (found.lines().size() > MAX_LINES) {
            throw new BadRequestException("The file has too many rows (maximum " + MAX_LINES + ").");
        }
        return found;
    }

    /** ".csv, .xlsx or .pdf" for the types in {@code allowed}. */
    private static String describe(Set<String> allowed) {
        List<String> names = TYPES.stream().filter(allowed::contains).map(t -> "." + t).toList();
        return names.size() < 2 ? String.join("", names)
                : String.join(", ", names.subList(0, names.size() - 1)) + " or " + names.getLast();
    }

    // ------------------------------------------------------------ Format readers

    private static List<List<String>> csvRows(byte[] data) {
        String text = new String(data, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) text = text.substring(1);
        List<String> physical = text.lines().filter(l -> !l.isBlank()).toList();
        if (physical.isEmpty()) return List.of();

        // Sample several lines: a title line above the data has no delimiter of its own.
        List<String> sample = physical.subList(0, Math.min(physical.size(), HEADER_SCAN_ROWS));
        char delimiter = CSV_DELIMITERS[0];
        long best = -1;
        for (char d : CSV_DELIMITERS) {
            long count = sample.stream().mapToLong(l -> l.chars().filter(c -> c == d).count()).sum();
            if (count > best) { best = count; delimiter = d; }
        }
        List<List<String>> rows = new ArrayList<>();
        for (String line : physical) rows.add(splitCsvLine(line, delimiter));
        return rows;
    }

    private static List<String> splitCsvLine(String line, char delimiter) {
        List<String> cells = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                else if (c == '"') quoted = false;
                else cur.append(c);
            } else if (c == '"') {
                quoted = true;
            } else if (c == delimiter) {
                cells.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        cells.add(cur.toString().trim());
        return cells;
    }

    private static List<List<String>> xlsxRows(byte[] data) throws IOException {
        List<List<String>> rows = new ArrayList<>();
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        try (Workbook workbook = new XSSFWorkbook(new ByteArrayInputStream(data))) {
            if (workbook.getNumberOfSheets() == 0) return rows;
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            Sheet sheet = workbook.getSheetAt(0);
            for (Row row : sheet) {
                List<String> cells = new ArrayList<>();
                for (int c = 0; c < Math.max(row.getLastCellNum(), 0); c++) {
                    Cell cell = row.getCell(c);
                    cells.add(cell == null ? "" : formatter.formatCellValue(cell, evaluator).trim());
                }
                rows.add(cells);
            }
        }
        return rows;
    }

    private static List<List<String>> pdfRows(byte[] data) throws IOException {
        String text;
        try (PDDocument document = Loader.loadPDF(data)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            text = stripper.getText(document);
        }
        if (text.isBlank()) {
            throw new BadRequestException(
                    "No text could be read from this PDF. Scanned or image-only PDFs are not supported.");
        }
        List<List<String>> rows = new ArrayList<>();
        for (String line : text.lines().map(String::trim).filter(l -> !l.isEmpty()).toList()) {
            String[] cells = CELL_GAP.split(line);
            if (cells.length >= 2) {
                rows.add(List.of(cells));
                continue;
            }
            Matcher m = NAME_THEN_QTY.matcher(line);
            rows.add(m.matches() ? List.of(m.group(1).trim(), m.group(2)) : List.of(line));
        }
        return rows;
    }

    // ----------------------------------------------------- Shared rows -> lines

    /**
     * Uses a header row (name + quantity columns) when one is found near the top; otherwise
     * takes the first non-numeric cell as the name and the first number after it as the
     * quantity. Rows without both are skipped.
     */
    private static Extraction toLines(List<List<String>> rows) {
        int nameCol = -1, qtyCol = -1, firstData = 0;
        for (int r = 0; r < Math.min(rows.size(), HEADER_SCAN_ROWS) && nameCol < 0; r++) {
            List<String> cells = rows.get(r);
            int n = -1, q = -1;
            for (int c = 0; c < cells.size(); c++) {
                String h = headerKey(cells.get(c));
                if (n < 0 && NAME_HEADERS.contains(h)) n = c;
                else if (q < 0 && QTY_HEADERS.contains(h)) q = c;
            }
            if (n >= 0 && q >= 0) { nameCol = n; qtyCol = q; firstData = r + 1; }
        }

        List<DocumentLine> lines = new ArrayList<>();
        int skipped = 0;
        for (int r = firstData; r < rows.size(); r++) {
            List<String> cells = rows.get(r);
            String name = null;
            BigDecimal quantity = null;
            if (nameCol >= 0) {
                name = cell(cells, nameCol);
                quantity = parseQuantity(cell(cells, qtyCol));
            } else {
                for (int c = 0; c < cells.size(); c++) {
                    String value = cells.get(c);
                    if (value.isBlank()) continue;
                    if (name == null) {
                        if (parseQuantity(value) == null) name = value;
                    } else if ((quantity = parseQuantity(value)) != null) {
                        break;
                    }
                }
            }
            if (name != null && !name.isBlank() && quantity != null) {
                lines.add(new DocumentLine(name.trim(), quantity));
            } else if (isSkippedRow(cells, nameCol >= 0)) {
                skipped++;
            }
        }
        return new Extraction(lines, skipped);
    }

    /**
     * A header cell reduced to the words it is made of, so "Qty.", "QUANTITY (pcs)" and "Item  Description" match
     * the same names as "qty", "quantity" and "item description".
     */
    private static String headerKey(String raw) {
        return raw.toLowerCase(Locale.ROOT).replaceAll("\\(.*?\\)", " ").replaceAll("[^a-z ]", " ")
                .replaceAll("\\s+", " ").trim();
    }

    /**
     * A row that had content but gave no item and quantity. With a header row, any non-empty row counts; without one,
     * a single-cell row is a title or a note, not a missing quantity, so only rows with two or more cells count.
     */
    private static boolean isSkippedRow(List<String> cells, boolean hasHeader) {
        long filled = cells.stream().filter(c -> !c.isBlank()).count();
        return hasHeader ? filled > 0 : filled > 1;
    }

    private static String cell(List<String> cells, int index) {
        return index >= 0 && index < cells.size() ? cells.get(index) : "";
    }

    private static BigDecimal parseQuantity(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (THOUSANDS.matcher(v).matches()) v = v.replace(",", "");
        else if (NUMBER.matcher(v).matches()) v = v.replace(',', '.');
        else return null;
        return new BigDecimal(v);
    }

    private static String extensionOf(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).trim().toLowerCase(Locale.ROOT);
    }
}
