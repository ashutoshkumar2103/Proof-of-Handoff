package com.handoffly.attachment;

import com.handoffly.common.domain.ActorType;
import com.handoffly.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * Metadata for a file stored via {@link com.handoffly.attachment.storage.StorageService}.
 * The binary itself lives in blob storage, never in the database — only the pointer
 * ({@code storageKey}) and descriptive metadata are persisted here.
 */
@Entity
@Table(name = "attachment")
public class Attachment extends BaseEntity {

    @Column(name = "handoff_id", nullable = false)
    private Long handoffId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AttachmentKind kind;

    @Column(name = "storage_key", nullable = false, length = 100)
    private String storageKey;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "uploaded_by_type", nullable = false, length = 20)
    private ActorType uploadedByType;

    @Column(name = "uploaded_by_ref", length = 200)
    private String uploadedByRef;

    protected Attachment() {
        // JPA
    }

    public Attachment(Long handoffId, AttachmentKind kind, String storageKey, String originalFilename,
                      String contentType, long sizeBytes, ActorType uploadedByType, String uploadedByRef) {
        this.handoffId = handoffId;
        this.kind = kind;
        this.storageKey = storageKey;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
        this.uploadedByType = uploadedByType;
        this.uploadedByRef = uploadedByRef;
    }

    public Long getHandoffId() { return handoffId; }
    public AttachmentKind getKind() { return kind; }
    public String getStorageKey() { return storageKey; }
    public String getOriginalFilename() { return originalFilename; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
    public ActorType getUploadedByType() { return uploadedByType; }
    public String getUploadedByRef() { return uploadedByRef; }
}
