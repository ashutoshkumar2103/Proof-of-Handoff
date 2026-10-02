package com.handoffly.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RedeemPaymentRequest(@NotBlank @Size(max = 200) String token) {}
