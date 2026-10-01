package com.handoffly.returns.dto;

import com.handoffly.common.domain.ActorType;
import com.handoffly.returns.ReturnEvent;

import java.time.Instant;
import java.util.List;

public record ReturnEventResponse(
        Long id,
        Instant occurredAt,
        ActorType enteredByType,
        String enteredByRef,
        String note,
        boolean confirmed,
        Instant confirmedAt,
        String confirmedByRef,
        Instant createdAt,
        List<ReturnLineResponse> lines
) {
    public static ReturnEventResponse from(ReturnEvent event) {
        return new ReturnEventResponse(
                event.getId(),
                event.getOccurredAt(),
                event.getEnteredByType(),
                event.getEnteredByRef(),
                event.getNote(),
                event.isConfirmed(),
                event.getConfirmedAt(),
                event.getConfirmedByRef(),
                event.getCreatedAt(),
                event.getLines().stream().map(ReturnLineResponse::from).toList());
    }
}
