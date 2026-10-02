package com.handoffly.payment.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Paying the difference to move up to {@code payment.plan}: the demo test card (the same details as paying for a plan outright)
 * and the amount the customer was shown. The amount charged is never taken from here — the backend works it out, and refuses
 * the payment, charging nothing, if it is no longer what the customer saw.
 */
public record UpgradePaymentRequest(
        @Min(1) int expectedAmount,
        @NotNull @Valid DemoPaymentRequest payment
) {}
