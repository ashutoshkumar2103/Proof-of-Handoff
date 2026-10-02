package com.handoffly.auth;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

/**
 * The response headers every API answer carries, applied once to both security chains (customer and support).
 * Spring Security already adds {@code X-Content-Type-Options: nosniff}, {@code X-Frame-Options: DENY} and
 * no-cache headers; this adds a policy that lets nothing in a response load or run anything, a Referrer-Policy
 * so reset and recipient links are never passed on in a Referer header, and HSTS (sent only over HTTPS).
 */
public final class SecurityHeaders {

    private static final long HSTS_SECONDS = 31_536_000L;   // one year

    private SecurityHeaders() {}

    public static void apply(HttpSecurity http) throws Exception {
        http.headers(headers -> headers
                .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                .referrerPolicy(referrer -> referrer.policy(ReferrerPolicy.NO_REFERRER))
                .httpStrictTransportSecurity(hsts -> hsts.includeSubDomains(true).maxAgeInSeconds(HSTS_SECONDS)));
    }
}
