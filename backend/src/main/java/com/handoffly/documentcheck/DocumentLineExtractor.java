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

    private static final Set<String> EXTENSIONS = Set.of("csv", "xlsx", "pdf");
    private static final int MAX_LINES = 2000;
    private static final int HEADER_SCAN_ROWS = 20;
    private static final char[] CSV_DELIMITERS = {',', ';', '\t'};

    private static final Set<String> NAME_HEADERS = Set.of(
            "item", "items", "item name", "name", "description", "product", "particulars", "article", "material");
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

    public List<DocumentLine> extract(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("No file was provided.");
        }
        if (file.getSize() > maxFileSizeBytes) {
            throw new BadRequestException("File exceeds the maximum allowed size.");
        }
        String extension = extensionOf(file.getOriginalFilename());
        if (!EXTENSIONS.contains(extension)) {
            throw new BadRequestException("Unsupported file type. Upload a .csv, .xlsx or .pdf file.");
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

        List<DocumentLine> lines = toLines(rows);
        if (lines.isEmpty()) {
            throw new BadRequestException("No item and quantity rows could be found in the file.");
        }
        if (lines.size() > MAX_LINES) {
            throw new BadRequestException("The file has too many rows (maximum " + MAX_LINES + ").");
        }
        return lines;
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
    private static List<DocumentLine> toLines(List<List<String>> rows) {
        int nameCol = -1, qtyCol = -1, firstData = 0;
        for (int r = 0; r < Math.min(rows.size(), HEADER_SCAN_ROWS) && nameCol < 0; r++) {
            List<String> cells = rows.get(r);
            int n = -1, q = -1;
            for (int c = 0; c < cells.size(); c++) {
                String h = cells.get(c).toLowerCase(Locale.ROOT).trim();
                if (n < 0 && NAME_HEADERS.contains(h)) n = c;
                else if (q < 0 && QTY_HEADERS.contains(h)) q = c;
            }
            if (n >= 0 && q >= 0) { nameCol = n; qtyCol = q; firstData = r + 1; }
        }

        List<DocumentLine> lines = new ArrayList<>();
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
            }
        }
        return lines;
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
