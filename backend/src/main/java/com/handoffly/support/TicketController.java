package com.handoffly.support;

import com.handoffly.attachment.AttachmentController;
import com.handoffly.auth.UserPrincipal;
import com.handoffly.common.web.PageResponse;
import com.handoffly.support.dto.CreateTicketRequest;
import com.handoffly.support.dto.ReplyRequest;
import com.handoffly.support.dto.TicketAttachmentResponse;
import com.handoffly.support.dto.TicketDetailResponse;
import com.handoffly.support.dto.TicketSummaryResponse;
import jakarta.validation.Valid;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * A customer's own support tickets. Every call is scoped to the signed-in customer and refused
 * unless their plan includes tickets.
 */
@RestController
@RequestMapping("/api/v1/tickets")
public class TicketController {

    private final SupportTicketService service;

    public TicketController(SupportTicketService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TicketDetailResponse create(@AuthenticationPrincipal UserPrincipal principal,
                                       @Valid @RequestBody CreateTicketRequest request) {
        return service.create(principal.id(), request);
    }

    @GetMapping
    public PageResponse<TicketSummaryResponse> list(@AuthenticationPrincipal UserPrincipal principal,
                                                    @PageableDefault(size = 20) Pageable pageable) {
        return service.listMine(principal.id(), pageable);
    }

    @GetMapping("/{ticketCode}")
    public TicketDetailResponse get(@AuthenticationPrincipal UserPrincipal principal,
                                    @PathVariable String ticketCode) {
        return service.getMine(principal.id(), ticketCode);
    }

    @PostMapping("/{ticketCode}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public TicketDetailResponse reply(@AuthenticationPrincipal UserPrincipal principal,
                                      @PathVariable String ticketCode,
                                      @Valid @RequestBody ReplyRequest request) {
        return service.replyAsCustomer(principal.id(), ticketCode, request.body());
    }

    @PostMapping("/{ticketCode}/attachments")
    @ResponseStatus(HttpStatus.CREATED)
    public TicketAttachmentResponse upload(@AuthenticationPrincipal UserPrincipal principal,
                                           @PathVariable String ticketCode,
                                           @RequestParam("file") MultipartFile file) {
        return service.addAttachment(principal.id(), ticketCode, file);
    }

    @GetMapping("/{ticketCode}/attachments/{attachmentId}/content")
    public ResponseEntity<Resource> download(@AuthenticationPrincipal UserPrincipal principal,
                                             @PathVariable String ticketCode,
                                             @PathVariable Long attachmentId) {
        return toDownloadResponse(service.downloadMine(principal.id(), ticketCode, attachmentId));
    }

    static ResponseEntity<Resource> toDownloadResponse(SupportTicketService.LoadedTicketAttachment loaded) {
        SupportTicketAttachment meta = loaded.attachment();
        return AttachmentController.toDownloadResponse(
                meta.getOriginalFilename(), meta.getContentType(), meta.getSizeBytes(), loaded.resource());
    }
}
