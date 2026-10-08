package com.handoffly.documentcheck.dto;

import java.util.List;

/**
 * What AI Assist suggests for the item names of two files, for the customer to review. It is only a suggestion: nothing has been compared or
 * changed. Each {@link Match} says that {@code from} (a name in {@code fromFile}) is probably the same item as {@code to} (a name in
 * {@code toFile}), and so would be compared under {@code to}; {@code certain} is false when the AI was less sure, and such a match is
 * only a possible one for the customer to confirm. {@code available} is false when there is no answer (not set up, unreachable, over quota,
 * an unusable answer); the {@code message} then says so in plain words, never in the provider's, and {@code canRetry} says that asking
 * again may well work. When it is available and {@code matches} is empty, the AI found nothing that looks like the same item twice.
 */
public record AiItemMatchResponse(boolean available, String message, List<Match> matches, boolean canRetry) {

    public record Match(String from, String fromFile, String to, String toFile, double confidence, boolean certain, String reason) {}

    public static AiItemMatchResponse found(List<Match> matches) {
        return new AiItemMatchResponse(true, null, matches, false);
    }

    public static AiItemMatchResponse unavailable(String message, boolean canRetry) {
        return new AiItemMatchResponse(false, message, List.of(), canRetry);
    }
}
