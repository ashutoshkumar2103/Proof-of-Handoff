package com.handoffly.payment.dto;

import com.handoffly.user.SubscriptionPlan;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The plan the customer chose to pay for, and a demo test card to pay with. What it costs is the plan's list
 * price, never taken from the request. The card details are checked and then discarded — never stored, returned
 * or logged (hence the {@code toString} that leaves them out).
 */
public record DemoPaymentRequest(
        @NotNull SubscriptionPlan plan,
        @NotBlank @Size(max = 32) String cardNumber,
        @NotBlank @Size(max = 10) String expiry,
        @NotBlank @Size(max = 8) String cvc
) {
    @Override
    public String toString() {
        return "DemoPaymentRequest[plan=" + plan + ", card=***]";
    }
}
