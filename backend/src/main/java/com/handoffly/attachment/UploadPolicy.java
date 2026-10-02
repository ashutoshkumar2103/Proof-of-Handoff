package com.handoffly.attachment;

import com.handoffly.common.config.HandOfflyProperties;
import com.handoffly.common.error.BadRequestException;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Set;

/**
 * The one set of rules for accepting an uploaded file — allowed types, maximum size, content that matches
 * its declared type, safe display filename — shared by handoff attachments and support-ticket attachments.
 */
@Component
public class UploadPolicy {

    private final Set<String> allowedContentTypes;
    private final long maxFileSizeBytes;

    public UploadPolicy(HandOfflyProperties properties) {
        this.allowedContentTypes = Set.copyOf(properties.getStorage().getAllowedContentTypes());
        this.maxFileSizeBytes = properties.getStorage().getMaxFileSizeBytes();
    }

    /** An upload that passed validation, ready to store. */
    public record CheckedUpload(byte[] data, String contentType, String filename, long size) {}

    /** Validates type and size, then reads the content. @throws BadRequestException if it is not acceptable. */
    public CheckedUpload check(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("No file was provided.");
        }
        if (file.getSize() > maxFileSizeBytes) {
            throw new BadRequestException("File exceeds the maximum allowed size.");
        }
        String contentType = normalize(file.getContentType());
        if (contentType == null) {
            throw new BadRequestException("File content type could not be determined.");
        }
        if (!allowedContentTypes.isEmpty() && !allowedContentTypes.contains(contentType)) {
            throw new BadRequestException("File type '" + contentType + "' is not allowed.");
        }
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded file", e);
        }
        // The declared type is only a claim by the client: the first bytes must agree with it.
        if (!ContentSniffer.matches(contentType, data)) {
            throw new BadRequestException("The file's content does not match its type ('" + contentType + "').");
        }
        return new CheckedUpload(data, contentType, sanitizeFilename(file.getOriginalFilename()), file.getSize());
    }

    private static String normalize(String contentType) {
        if (contentType == null) return null;
        int semi = contentType.indexOf(';');
        String base = (semi >= 0 ? contentType.substring(0, semi) : contentType).trim().toLowerCase();
        return base.isEmpty() ? null : base;
    }

    /** Strips any path components to keep only a safe display filename. */
    private static String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) return "file";
        String base = name.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        return base.isBlank() ? "file" : base;
    }
}
