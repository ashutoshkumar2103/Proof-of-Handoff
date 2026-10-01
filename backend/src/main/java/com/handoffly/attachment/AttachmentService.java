package com.handoffly.attachment;

import com.handoffly.attachment.dto.AttachmentResponse;
import com.handoffly.attachment.storage.StorageService;
import com.handoffly.audit.AuditEventType;
import com.handoffly.audit.AuditService;
import com.handoffly.common.config.HandOfflyProperties;
import com.handoffly.common.domain.ActorType;
import com.handoffly.common.error.BadRequestException;
import com.handoffly.common.error.ConflictException;
import com.handoffly.common.error.NotFoundException;
import com.handoffly.handoff.Handoff;
import com.handoffly.handoff.HandoffService;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Set;

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
    private final Set<String> allowedContentTypes;
    private final long maxFileSizeBytes;

    public AttachmentService(AttachmentRepository attachmentRepository,
                             StorageService storageService,
                             HandoffService handoffService,
                             AuditService auditService,
                             HandOfflyProperties properties) {
        this.attachmentRepository = attachmentRepository;
        this.storageService = storageService;
        this.handoffService = handoffService;
        this.auditService = auditService;
        this.allowedContentTypes = Set.copyOf(properties.getStorage().getAllowedContentTypes());
        this.maxFileSizeBytes = properties.getStorage().getMaxFileSizeBytes();
    }

    @Transactional
    public AttachmentResponse upload(Long userId, Long handoffId, AttachmentKind kind, MultipartFile file) {
        Handoff handoff = handoffService.getOwnedHandoff(handoffId, userId);
        if (handoff.getStatus().isTerminal()) {
            throw new ConflictException("Attachments cannot be changed on a closed handoff.");
        }
        validate(file);

        String contentType = normalize(file.getContentType());
        byte[] data = readBytes(file);
        String storageKey = storageService.store(data, contentType);

        Attachment attachment = new Attachment(
                handoffId, kind == null ? AttachmentKind.EVIDENCE : kind, storageKey,
                sanitizeFilename(file.getOriginalFilename()), contentType, file.getSize(),
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

    private void validate(MultipartFile file) {
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
    }

    private byte[] readBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read uploaded file", e);
        }
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

    /** An attachment's metadata paired with its streamable content. */
    public record LoadedAttachment(Attachment attachment, Resource resource) {}
}
