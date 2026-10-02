package com.handoffly.support;

import com.handoffly.auth.UserPrincipal;
import com.handoffly.support.dto.SupportMessageReceipt;
import com.handoffly.support.dto.SupportMessageRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Sending support a message from the customer app. Refused unless the customer's plan includes Contact
 * Support; it never gives access to tickets (that is {@link TicketController}, which needs a ticket plan).
 */
@RestController
@RequestMapping("/api/v1/support-messages")
public class SupportMessageController {

    private final SupportTicketService service;

    public SupportMessageController(SupportTicketService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public SupportMessageReceipt send(@AuthenticationPrincipal UserPrincipal principal,
                                      @Valid @ModelAttribute SupportMessageRequest request,
                                      @RequestParam(name = "file", required = false) MultipartFile file) {
        return new SupportMessageReceipt(service.sendMessage(principal.id(), request, file));
    }
}
