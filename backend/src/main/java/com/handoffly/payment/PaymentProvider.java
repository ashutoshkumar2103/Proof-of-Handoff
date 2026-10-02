package com.handoffly.payment;

/**
 * Who took the payment. Only the DEMO provider exists today: it takes no money and is for testing. A real
 * provider is added here, together with whatever confirms its payments, and everything after a payment is
 * confirmed (the one-time token, redeeming it, applying the plan) stays the same.
 */
public enum PaymentProvider {
    DEMO
}
