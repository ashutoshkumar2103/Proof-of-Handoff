package com.handoffly.support.dto;

import java.time.Instant;

public record StaffAuthResponse(String token, Instant expiresAt, StaffResponse staff) {}
