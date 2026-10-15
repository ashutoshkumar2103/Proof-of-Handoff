package com.handoffly.handoff;

import com.handoffly.auth.UserPrincipal;
import com.handoffly.handoff.dto.AiDraftRequest;
import com.handoffly.handoff.dto.AssistantPreviewResponse;
import com.handoffly.handoff.dto.AssistantRequest;
import com.handoffly.handoff.dto.HandoffDetailResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The AI Handoff Creator. Two calls and no more: understand a message into a preview (nothing is saved), and save the reviewed draft (always a
 * DRAFT). There is no submit, send, edit or delete here. Open to every plan with an active subscription.
 */
@RestController
@RequestMapping("/api/v1/handoffs/assistant")
public class HandoffAssistantController {

    private final HandoffAssistantService assistant;

    public HandoffAssistantController(HandoffAssistantService assistant) {
        this.assistant = assistant;
    }

    @PostMapping("/interpret")
    public AssistantPreviewResponse interpret(@AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody AssistantRequest request) {
        return assistant.interpret(principal.id(), request);
    }

    @PostMapping("/drafts")
    @ResponseStatus(HttpStatus.CREATED)
    public HandoffDetailResponse save(@AuthenticationPrincipal UserPrincipal principal, @Valid @RequestBody AiDraftRequest request) {
        return assistant.save(principal.id(), request);
    }
}
