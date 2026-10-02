package com.handoffly.support.dto;

import com.handoffly.user.User;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record PrefixRequest(
        @NotNull @Pattern(regexp = User.HANDOFF_PREFIX_REGEX,
                message = "A prefix is 2-5 upper-case letters (A-Z), with no spaces or symbols.") String prefix
) {}
