package com.handoffly.support.dto;

import com.handoffly.support.TicketStatus;
import jakarta.validation.constraints.NotNull;

public record StatusRequest(@NotNull TicketStatus status) {}
