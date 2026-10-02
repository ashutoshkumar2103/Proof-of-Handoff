package com.handoffly.testsupport;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/** Builds small in-memory CSV / XLSX / PDF uploads for document-extraction and comparison tests. */
public final class TestDocuments {

    private static final Pattern NUMBER = Pattern.compile("\\d+(\\.\\d+)?");

    private TestDocuments() {}

    public static MockMultipartFile file(String filename, byte[] bytes) {
        return new MockMultipartFile("file", filename, "application/octet-stream", bytes);
    }

    public static MockMultipartFile csv(String filename, String content) {
        return file(filename, content.getBytes(StandardCharsets.UTF_8));
    }

    /** One sheet; numeric-looking cells are stored as numbers, as Excel would. */
    public static MockMultipartFile xlsx(String filename, String[]... rows) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    if (NUMBER.matcher(rows[r][c]).matches()) row.createCell(c).setCellValue(Double.parseDouble(rows[r][c]));
                    else row.createCell(c).setCellValue(rows[r][c]);
                }
            }
            workbook.write(out);
            return file(filename, out.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A text PDF with one line of text per argument; no arguments gives a page with no text. */
    public static MockMultipartFile pdf(String filename, String... lines) {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.setLeading(16);
                content.newLineAtOffset(50, 700);
                for (String line : lines) {
                    content.showText(line);
                    content.newLine();
                }
                content.endText();
            }
            document.save(out);
            return file(filename, out.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
