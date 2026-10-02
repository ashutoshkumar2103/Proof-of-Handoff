package com.handoffly.attachment;

import com.handoffly.attachment.dto.AttachmentResponse;
import com.handoffly.auth.UserPrincipal;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/handoffs/{handoffId}/attachments")
public class AttachmentController {

    private final AttachmentService attachmentService;

    public AttachmentController(AttachmentService attachmentService) {
        this.attachmentService = attachmentService;
    }

    @GetMapping
    public List<AttachmentResponse> list(@AuthenticationPrincipal UserPrincipal principal,
                                         @PathVariable Long handoffId) {
        return attachmentService.list(principal.id(), handoffId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AttachmentResponse upload(@AuthenticationPrincipal UserPrincipal principal,
                                     @PathVariable Long handoffId,
                                     @RequestParam(name = "kind", defaultValue = "EVIDENCE") AttachmentKind kind,
                                     @RequestParam("file") MultipartFile file) {
        return attachmentService.upload(principal.id(), handoffId, kind, file);
    }

    @GetMapping("/{attachmentId}/content")
    public ResponseEntity<Resource> download(@AuthenticationPrincipal UserPrincipal principal,
                                             @PathVariable Long handoffId,
                                             @PathVariable Long attachmentId) {
        AttachmentService.LoadedAttachment loaded =
                attachmentService.download(principal.id(), handoffId, attachmentId);
        return toDownloadResponse(loaded);
    }

    @DeleteMapping("/{attachmentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal UserPrincipal principal,
                       @PathVariable Long handoffId,
                       @PathVariable Long attachmentId) {
        attachmentService.delete(principal.id(), handoffId, attachmentId);
    }

    public static ResponseEntity<Resource> toDownloadResponse(AttachmentService.LoadedAttachment loaded) {
        Attachment meta = loaded.attachment();
        return toDownloadResponse(meta.getOriginalFilename(), meta.getContentType(), meta.getSizeBytes(), loaded.resource());
    }

    /** The one way stored files are sent back: always as a download, with the stored type and exact length. */
    public static ResponseEntity<Resource> toDownloadResponse(String filename, String contentType, long sizeBytes,
                                                              Resource resource) {
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(filename)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(sizeBytes)
                .body(resource);
    }
}
