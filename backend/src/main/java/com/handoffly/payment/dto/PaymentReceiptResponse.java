package com.handoffly.payment.dto;

import com.handoffly.payment.PaymentService;
import com.handoffly.user.SubscriptionPlan;

import java.time.Instant;

/**
 * Proof that a plan was paid for. {@code token} is shown only here: it applies the plan to an account (once,
 * before {@code expiresAt}) and is never retrievable again.
 */
public record PaymentReceiptResponse(String token, SubscriptionPlan plan, int amount, String currency, Instant expiresAt) {
    public static PaymentReceiptResponse from(PaymentService.PaidPlan paid) {
        return new PaymentReceiptResponse(paid.token(), paid.payment().getPlan(), paid.payment().getAmount(),
                paid.payment().getCurrency(), paid.payment().getExpiresAt());
    }
}
