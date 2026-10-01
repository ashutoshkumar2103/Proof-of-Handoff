package com.handoffly.documentcheck;

import com.handoffly.common.config.HandOfflyProperties;
import com.handoffly.common.error.BadRequestException;
import com.handoffly.documentcheck.dto.DocumentLine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.handoffly.support.TestDocuments.csv;
import static com.handoffly.support.TestDocuments.file;
import static com.handoffly.support.TestDocuments.pdf;
import static com.handoffly.support.TestDocuments.xlsx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentLineExtractorTest {

    private final DocumentLineExtractor extractor = new DocumentLineExtractor(new HandOfflyProperties());

    private static void assertReturnedLines(List<DocumentLine> lines) {
        assertThat(lines).extracting(DocumentLine::name)
                .containsExactly("Table", "Curtain", "Joker Dress", "Balloon Filler");
        assertThat(lines).extracting(l -> l.quantity().toPlainString())
                .containsExactly("1", "2", "2", "1");
    }

    @Test
    void readsCsvWithHeaderRow() {
        assertReturnedLines(extractor.extract(
                csv("returns.csv", "﻿Item,Qty\nTable,1\n\"Curtain\",2\nJoker Dress,2\nBalloon Filler,1\n")));
    }

    @Test
    void readsHeaderlessSemicolonCsvAndSkipsNonItemRows() {
        assertReturnedLines(extractor.extract(
                csv("r.csv", "Returned items\nTable;1\nCurtain;2\nJoker Dress;2\nBalloon Filler;1\n")));
    }

    @Test
    void readsXlsxFirstSheet() {
        assertReturnedLines(extractor.extract(xlsx("returns.xlsx",
                new String[]{"Item", "Quantity"}, new String[]{"Table", "1"}, new String[]{"Curtain", "2"},
                new String[]{"Joker Dress", "2"}, new String[]{"Balloon Filler", "1"})));
    }

    @Test
    void readsTextPdf() {
        assertReturnedLines(extractor.extract(
                pdf("slip.pdf", "Return slip", "Table 1", "Curtain 2", "Joker Dress 2", "Balloon Filler 1")));
    }

    @Test
    void rejectsUnsupportedTypesEmptyFilesAndFilesWithoutQuantities() {
        assertThatThrownBy(() -> extractor.extract(file("notes.txt", "Table,1".getBytes())))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Unsupported");
        assertThatThrownBy(() -> extractor.extract(file("empty.csv", new byte[0])))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> extractor.extract(csv("words.csv", "just,some,words")))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("No item");
        assertThatThrownBy(() -> extractor.extract(file("broken.xlsx", "not a workbook".getBytes())))
                .isInstanceOf(BadRequestException.class);
    }
}
