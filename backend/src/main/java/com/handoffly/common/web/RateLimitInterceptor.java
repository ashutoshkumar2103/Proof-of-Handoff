package com.handoffly.common.web;

import com.handoffly.common.config.HandOfflyProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;

/**
 * Per-address request limits on the endpoints anyone can reach without signing in — the ones a script would
 * hammer: signing in, registering, password reset, recipient links and the demo payment. A refused request
 * ends in the normal central error response (429 with {@code Retry-After}). Limits that depend on who is
 * being targeted (one email, one account) live next to the code that knows that, in the auth service.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    /** The paths this applies to (registered in {@link WebConfig}). */
    static final String[] PATHS = {
            "/api/v1/auth/login", "/api/v1/auth/register", "/api/v1/auth/forgot-password", "/api/v1/auth/reset-password",
            "/api/v1/support/auth/login", "/api/v1/r/**", "/api/v1/public/payments/demo"};

    private static final Duration TEN_MINUTES = Duration.ofMinutes(10);
    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Duration HOUR = Duration.ofHours(1);

    private final RateLimiter limiter;
    private final HandOfflyProperties.RateLimit limits;

    public RateLimitInterceptor(RateLimiter limiter, HandOfflyProperties properties) {
        this.limiter = limiter;
        this.limits = properties.getRateLimit();
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;   // CORS preflight carries nothing to abuse
        }
        String path = request.getRequestURI();
        String ip = request.getRemoteAddr();
        switch (path) {
            case "/api/v1/auth/login" -> limiter.hit("login:" + ip, limits.getLoginPerIp(), TEN_MINUTES);
            case "/api/v1/auth/register" -> limiter.hit("register:" + ip, limits.getRegisterPerIp(), HOUR);
            case "/api/v1/auth/forgot-password" -> limiter.hit("forgot:" + ip, limits.getForgotPerIp(), HOUR);
            case "/api/v1/auth/reset-password" -> limiter.hit("reset:" + ip, limits.getResetPerIp(), HOUR);
            case "/api/v1/support/auth/login" -> limiter.hit("staff-login:" + ip, limits.getStaffLoginPerIp(), TEN_MINUTES);
            case "/api/v1/public/payments/demo" -> limiter.hit("payment:" + ip, limits.getPaymentPerIp(), HOUR);
            default -> {
                if (path.startsWith("/api/v1/r/")) {
                    limiter.hit("recipient:" + ip, limits.getRecipientPerIp(), MINUTE);
                }
            }
        }
        return true;
    }
}
