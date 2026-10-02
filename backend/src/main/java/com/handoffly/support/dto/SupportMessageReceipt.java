package com.handoffly.support.dto;

/** Confirms a support message was received; {@code reference} is what to quote if the customer follows up. */
public record SupportMessageReceipt(String reference) {}
