package com.handoffly.handoff.dto;

/**
 * Where the Proof-of-Handoff PDF was emailed. {@code delivered} is false when the server is in
 * development mode and only logged the message, so the UI never claims an email that wasn't sent.
 */
public record EmailPdfResponse(String sentTo, boolean delivered) {}
