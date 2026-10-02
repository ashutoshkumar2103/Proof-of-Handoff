package com.handoffly.attachment;

import com.handoffly.common.config.HandOfflyProperties;
import com.handoffly.common.error.BadRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** An upload is accepted only if its content really is what its declared type says. */
class UploadPolicyTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0};
    private static final byte[] PDF = "%PDF-1.7\n1 0 obj".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ZIP = {'P', 'K', 3, 4, 0, 0};
    private static final byte[] OLE2 = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1, 0};
    private static final byte[] EXE = {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0};
    private static final byte[] SCRIPT = "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8);

    private static UploadPolicy policy() {
        HandOfflyProperties p = new HandOfflyProperties();
        p.getStorage().setMaxFileSizeBytes(1_000_000);
        p.getStorage().setAllowedContentTypes(List.of("image/png", "image/jpeg", "image/gif", "image/webp", "application/pdf",
                "text/plain", "text/csv", "application/msword", "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
        return new UploadPolicy(p);
    }

    private static MockMultipartFile file(String name, String type, byte[] content) {
        return new MockMultipartFile("file", name, type, content);
    }

    @Test
    void genuineFilesOfEveryAllowedTypeAreAccepted() {
        UploadPolicy policy = policy();
        byte[] webp = "RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1);
        byte[] gif = "GIF89a\1\0\1\0".getBytes(StandardCharsets.ISO_8859_1);
        Object[][] genuine = {
                {"a.png", "image/png", PNG}, {"a.jpg", "image/jpeg", JPEG}, {"a.gif", "image/gif", gif},
                {"a.webp", "image/webp", webp}, {"a.pdf", "application/pdf", PDF},
                {"a.txt", "text/plain", "plain notes, with accents: café".getBytes(StandardCharsets.UTF_8)},
                {"a.csv", "text/csv", "Item,Qty\nTable,1\n".getBytes(StandardCharsets.UTF_8)},
                {"a.csv", "text/csv", "café;1\n".getBytes(StandardCharsets.ISO_8859_1)},   // not UTF-8: still text
                {"a.doc", "application/msword", OLE2}, {"a.xls", "application/vnd.ms-excel", OLE2},
                {"a.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", ZIP},
                {"a.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ZIP}};
        for (Object[] g : genuine) {
            assertThatCode(() -> policy.check(file((String) g[0], (String) g[1], (byte[]) g[2])))
                    .as(g[0] + " as " + g[1]).doesNotThrowAnyException();
        }
    }

    @Test
    void contentThatDoesNotMatchTheDeclaredTypeIsRefused() {
        UploadPolicy policy = policy();
        Object[][] disguised = {
                {"photo.png", "image/png", SCRIPT}, {"photo.png", "image/png", EXE}, {"photo.png", "image/png", JPEG},
                {"photo.jpg", "image/jpeg", PNG}, {"doc.pdf", "application/pdf", EXE}, {"doc.pdf", "application/pdf", SCRIPT},
                {"notes.txt", "text/plain", EXE}, {"notes.csv", "text/csv", PNG},
                {"sheet.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", EXE},
                {"old.doc", "application/msword", ZIP}, {"tiny.png", "image/png", new byte[]{1, 2, 3}}};
        for (Object[] d : disguised) {
            assertThatThrownBy(() -> policy.check(file((String) d[0], (String) d[1], (byte[]) d[2])))
                    .as(d[0] + " as " + d[1]).isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("does not match its type");
        }
    }

    @Test
    void theOtherRulesStillApply() {
        UploadPolicy policy = policy();
        assertThatThrownBy(() -> policy.check(file("x.exe", "application/x-msdownload", EXE)))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("not allowed");
        assertThatThrownBy(() -> policy.check(file("empty.txt", "text/plain", new byte[0]))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> policy.check(file("big.txt", "text/plain", new byte[1_000_001])))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("maximum");
        assertThatThrownBy(() -> policy.check(null)).isInstanceOf(BadRequestException.class);
        // The safe display name drops any path.
        assertThat(policy.check(file("..\\..\\etc\\notes.txt", "text/plain; charset=utf-8", "hi".getBytes())).filename())
                .isEqualTo("notes.txt");
    }
}
