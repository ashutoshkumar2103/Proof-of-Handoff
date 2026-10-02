package com.handoffly.support;

import com.handoffly.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Metadata for a file attached to a ticket; the bytes live in the shared blob storage. */
@Entity
@Table(name = "support_ticket_attachment")
public class SupportTicketAttachment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false, updatable = false)
    private SupportTicket ticket;

    @Column(name = "storage_key", nullable = false, length = 100)
    private String storageKey;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    protected SupportTicketAttachment() {
        // JPA
    }

    public SupportTicketAttachment(SupportTicket ticket, String storageKey, String originalFilename,
                                   String contentType, long sizeBytes) {
        this.ticket = ticket;
        this.storageKey = storageKey;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.sizeBytes = sizeBytes;
    }

    public SupportTicket getTicket() { return ticket; }
    public String getStorageKey() { return storageKey; }
    public String getOriginalFilename() { return originalFilename; }
    public String getContentType() { return contentType; }
    public long getSizeBytes() { return sizeBytes; }
}
