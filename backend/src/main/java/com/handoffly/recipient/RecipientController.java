package com.handoffly.recipient;

import com.handoffly.attachment.AttachmentController;
import com.handoffly.attachment.AttachmentService;
import com.handoffly.recipient.dto.AcceptHandoffRequest;
import com.handoffly.recipient.dto.RecipientHandoffView;
import com.handoffly.recipient.dto.RejectHandoffRequest;
import com.handoffly.recipient.dto.ReturnWaitRequest;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, token-scoped recipient endpoints. No authentication; the unguessable token in
 * the path is the sole, single-handoff access grant. Registered as public in SecurityConfig.
 */
@RestController
@RequestMapping("/api/v1/r/{token}")
public class RecipientController {

    private final RecipientService recipientService;

    public RecipientController(RecipientService recipientService) {
        this.recipientService = recipientService;
    }

    @GetMapping
    public RecipientHandoffView view(@PathVariable String token) {
        return recipientService.view(token);
    }

    @PostMapping("/accept")
    public RecipientHandoffView accept(@PathVariable String token,
                                       @Valid @RequestBody AcceptHandoffRequest request) {
        return recipientService.accept(token, request);
    }

    @PostMapping("/reject")
    public RecipientHandoffView reject(@PathVariable String token,
                                       @Valid @RequestBody RejectHandoffRequest request) {
        return recipientService.reject(token, request);
    }

    @PostMapping("/confirm-missing")
    public RecipientHandoffView confirmMissing(@PathVariable String token,
                                               @Valid @RequestBody AcceptHandoffRequest request) {
        return recipientService.confirmMissing(token, request);
    }

    @PostMapping("/request-return-wait")
    public RecipientHandoffView requestReturnWait(@PathVariable String token,
                                                  @Valid @RequestBody ReturnWaitRequest request) {
        return recipientService.requestReturnWait(token, request);
    }

    @GetMapping("/attachments/{attachmentId}/content")
    public ResponseEntity<Resource> downloadAttachment(@PathVariable String token,
                                                       @PathVariable Long attachmentId) {
        AttachmentService.LoadedAttachment loaded =
                recipientService.downloadAttachment(token, attachmentId);
        return AttachmentController.toDownloadResponse(loaded);
    }
}
