package com.handoffly.handoff.dto;

import com.handoffly.handoff.HandoffIntent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * What the AI Handoff Creator is asked: the customer's own words, and — when the customer is answering a question the page asked — the
 * understanding the answer came with, plus their choices (question id to option id). With an {@code intent}, no AI request is made: it is
 * checked exactly like the model's own answer, and the choices are accepted only if they name an option the server itself offered.
 */
public record AssistantRequest(
        @NotBlank @Size(max = 1000) String text,
        HandoffIntent intent,
        @Size(max = 30) Map<@Size(max = 120) String, @Size(max = 120) String> choices
) {}
