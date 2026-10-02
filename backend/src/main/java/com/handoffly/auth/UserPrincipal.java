package com.handoffly.auth;

/**
 * The signed-in CUSTOMER, derived from the JWT (which the filter has just checked against the account). Exposed to controllers via
 * {@code @AuthenticationPrincipal}. Support staff are a different identity ({@code StaffPrincipal}).
 */
public record UserPrincipal(Long id, String email) {
}
