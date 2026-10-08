package com.handoffly.documentcheck.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/** The item names of the two files, as reviewed on screen: all AI Assist is given to look for spelling variations. */
public record AiItemMatchRequest(
        @NotNull @Size(max = AiItemMatchRequest.MAX_NAMES) List<@NotBlank @Size(max = 300) String> fileA,
        @NotNull @Size(max = AiItemMatchRequest.MAX_NAMES) List<@NotBlank @Size(max = 300) String> fileB
) {
    /** The most names of one file that are looked at in one request. */
    public static final int MAX_NAMES = 200;
}
