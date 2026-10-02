package com.handoffly.attachment;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Checks that an uploaded file really is what its declared type says, from its first bytes — so a script or an
 * executable cannot be uploaded just by labelling it {@code image/png}. A type it has no rule for passes (it is
 * still held to the allow-list in {@link UploadPolicy}); this only ever makes the check stricter, never looser.
 */
final class ContentSniffer {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] ZIP = {'P', 'K', 0x03, 0x04};
    private static final byte[] OLE2 = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
    private static final int PDF_HEADER_WINDOW = 1024;

    private static final Predicate<byte[]> TEXT = data -> {
        for (byte b : data) {
            if (b == 0) return false;   // a NUL byte means binary content, not text
        }
        return true;
    };
    private static final Predicate<byte[]> OPEN_XML = data -> startsWith(data, ZIP);

    private static final Map<String, Predicate<byte[]>> RULES = Map.ofEntries(
            Map.entry("image/png", data -> startsWith(data, PNG)),
            Map.entry("image/jpeg", data -> startsWith(data, JPEG)),
            Map.entry("image/gif", data -> startsWith(data, "GIF87a".getBytes(StandardCharsets.US_ASCII))
                    || startsWith(data, "GIF89a".getBytes(StandardCharsets.US_ASCII))),
            Map.entry("image/webp", data -> startsWith(data, "RIFF".getBytes(StandardCharsets.US_ASCII))
                    && data.length >= 12
                    && new String(data, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")),
            Map.entry("application/pdf", data -> new String(data, 0, Math.min(data.length, PDF_HEADER_WINDOW),
                    StandardCharsets.ISO_8859_1).contains("%PDF-")),
            Map.entry("text/plain", TEXT),
            Map.entry("text/csv", TEXT),
            Map.entry("application/msword", data -> startsWith(data, OLE2)),
            Map.entry("application/vnd.ms-excel", data -> startsWith(data, OLE2)),
            Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", OPEN_XML),
            Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", OPEN_XML));

    private ContentSniffer() {}

    /** Whether the bytes fit the declared (already normalised) content type; true when there is no rule for it. */
    static boolean matches(String contentType, byte[] data) {
        Predicate<byte[]> rule = RULES.get(contentType);
        return rule == null || rule.test(data);
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        return data.length >= prefix.length && Arrays.equals(data, 0, prefix.length, prefix, 0, prefix.length);
    }
}
