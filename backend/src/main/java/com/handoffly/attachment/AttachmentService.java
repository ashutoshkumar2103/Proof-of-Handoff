package com.handoffly.attachment;

import com.handoffly.attachment.dto.AttachmentResponse;
import com.handoffly.attachment.storage.StorageService;
import com.handoffly.audit.AuditEventType;
import com.handoffly.audit.AuditService;
import com.handoffly.common.domain.ActorType;
import com.handoffly.common.error.ConflictException;
import com.handoffly.common.error.NotFoundException;
import com.handoffly.handoff.Handoff;
import com.handoffly.handoff.HandoffService;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Handles evidence and reference-document uploads: validates type and size, stores the
 * blob via {@link StorageService}, and persists only metadata. Enforces owner access and
 * blocks changes on read-only (terminal) handoffs.
 */
@Service
public class AttachmentService {

    private final AttachmentRepository attachmentRepository;
    private final StorageService storageService;
    private final HandoffService handoffService;
    private final AuditService auditService;
    private final UploadPolicy uploadPolicy;

    public AttachmentService(AttachmentRepository attachmentRepository,
                             StorageService storageService,
                             HandoffService handoffService,
                             AuditService auditService,
                             UploadPolicy uploadPolicy) {
        this.attachmentRepository = attachmentRepository;
        this.storageService = storageService;
        this.handoffService = handoffService;
        this.auditService = auditService;
        this.uploadPolicy = uploadPolicy;
    }

    @Transactional
    public AttachmentResponse upload(Long userId, Long handoffId, AttachmentKind kind, MultipartFile file) {
        Handoff handoff = handoffService.getOwnedHandoff(handoffId, userId);
        if (handoff.getStatus().isTerminal()) {
            throw new ConflictException("Attachments cannot be changed on a closed handoff.");
        }
        UploadPolicy.CheckedUpload upload = uploadPolicy.check(file);
        String storageKey = storageService.store(upload.data(), upload.contentType());

        Attachment attachment = new Attachment(
                handoffId, kind == null ? AttachmentKind.EVIDENCE : kind, storageKey,
                upload.filename(), upload.contentType(), upload.size(),
                ActorType.USER, handoff.getOwner().getEmail());
        attachment = attachmentRepository.save(attachment);
        auditService.record(handoffId, AuditEventType.ATTACHMENT_ADDED, ActorType.USER,
                handoff.getOwner().getEmail(),
                "Attachment added: " + attachment.getOriginalFilename() + ".");
        return AttachmentResponse.from(attachment);
    }

    @Transactional(readOnly = true)
    public List<AttachmentResponse> list(Long userId, Long handoffId) {
        handoffService.getOwnedHandoff(handoffId, userId); // authorize
        return attachmentRepository.findByHandoffIdOrderByCreatedAtAscIdAsc(handoffId).stream()
                .map(AttachmentResponse::from).toList();
    }

    /** Loads an attachment for an owner and returns its metadata plus streamable content. */
    @Transactional(readOnly = true)
    public LoadedAttachment download(Long userId, Long handoffId, Long attachmentId) {
        handoffService.getOwnedHandoff(handoffId, userId); // authorize
        return load(handoffId, attachmentId);
    }

    /** Loads an attachment already authorized for a given handoff (e.g. recipient flow). */
    @Transactional(readOnly = true)
    public LoadedAttachment load(Long handoffId, Long attachmentId) {
        Attachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new NotFoundException("Attachment not found."));
        if (!attachment.getHandoffId().equals(handoffId)) {
            throw new NotFoundException("Attachment not found.");
        }
        return new LoadedAttachment(attachment, storageService.load(attachment.getStorageKey()));
    }

    @Transactional
    public void delete(Long userId, Long handoffId, Long attachmentId) {
        Handoff handoff = handoffService.getOwnedHandoff(handoffId, userId);
        if (handoff.getStatus().isTerminal()) {
            throw new ConflictException("Attachments cannot be changed on a closed handoff.");
        }
        Attachment attachment = attachmentRepository.findById(attachmentId)
                .orElseThrow(() -> new NotFoundException("Attachment not found."));
        if (!attachment.getHandoffId().equals(handoffId)) {
            throw new NotFoundException("Attachment not found.");
        }
        attachmentRepository.delete(attachment);
        storageService.delete(attachment.getStorageKey());
        auditService.record(handoffId, AuditEventType.ATTACHMENT_REMOVED, ActorType.USER,
                handoff.getOwner().getEmail(),
                "Attachment removed: " + attachment.getOriginalFilename() + ".");
    }

    // --------------------------------------------------------------- Internals

    /** An attachment's metadata paired with its streamable content. */
    public record LoadedAttachment(Attachment attachment, Resource resource) {}
}
