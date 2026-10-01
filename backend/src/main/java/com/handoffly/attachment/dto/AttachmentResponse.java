package com.handoffly.attachment.dto;

import com.handoffly.attachment.Attachment;
import com.handoffly.attachment.AttachmentKind;
import com.handoffly.common.domain.ActorType;

import java.time.Instant;

public record AttachmentResponse(
        Long id,
        AttachmentKind kind,
        String originalFilename,
        String contentType,
        long sizeBytes,
        ActorType uploadedByType,
        String uploadedByRef,
        Instant createdAt
) {
    public static AttachmentResponse from(Attachment a) {
        return new AttachmentResponse(
                a.getId(),
                a.getKind(),
                a.getOriginalFilename(),
                a.getContentType(),
                a.getSizeBytes(),
                a.getUploadedByType(),
                a.getUploadedByRef(),
                a.getCreatedAt());
    }
}
