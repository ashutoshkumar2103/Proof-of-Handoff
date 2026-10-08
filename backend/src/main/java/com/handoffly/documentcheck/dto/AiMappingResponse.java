package com.handoffly.documentcheck.dto;

import java.util.List;

/**
 * What AI Assist suggests for a file's columns, for the customer to review. It is only a suggestion: nothing has been read, stored or
 * changed, and no lines are returned — they come from the ordinary file reading, only after the customer accepts, using the columns here.
 * {@code available} is false whenever there is no usable suggestion (not set up, unreachable, over quota, an unusable answer); the
 * {@code message} then says so in plain words, never in the provider's. {@code itemsFound} is how many items the ordinary reading
 * finds in the file with these columns, counted by the same routine that would read them. {@code canRetry} says that asking again may
 * well work (the provider was slow or its answer was unusable), as opposed to a key that is not set up or a file the AI cannot read.
 */
public record AiMappingResponse(boolean available, String message, Suggestion item, Suggestion quantity,
                                Integer headerRow, int itemsFound, boolean canRetry) {

    /** One suggested column: its 0-based place, its heading (or "Column C"), how sure the AI says it is, why, and a few values from it. */
    public record Suggestion(int column, String columnName, double confidence, String reason, List<String> sampleValues) {}

    public static AiMappingResponse unavailable(String message, boolean canRetry) {
        return new AiMappingResponse(false, message, null, null, null, 0, canRetry);
    }
}
