package com.handoffly.common.web;

import com.handoffly.common.config.HandOfflyProperties;
import com.handoffly.common.error.RateLimitedException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The limiter's own behaviour, and which requests the public-endpoint interceptor counts. */
class RateLimiterTest {

    private static HandOfflyProperties properties(boolean enabled) {
        HandOfflyProperties p = new HandOfflyProperties();
        p.getRateLimit().setEnabled(enabled);
        p.getRateLimit().setLoginPerIp(3);
        p.getRateLimit().setRegisterPerIp(2);
        p.getRateLimit().setForgotPerIp(2);
        p.getRateLimit().setResetPerIp(2);
        p.getRateLimit().setStaffLoginPerIp(2);
        p.getRateLimit().setRecipientPerIp(4);
        p.getRateLimit().setPaymentPerIp(2);
        return p;
    }

    private static boolean pass(RateLimitInterceptor interceptor, String method, String path, String ip) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setRemoteAddr(ip);
        return interceptor.preHandle(request, new MockHttpServletResponse(), new Object());
    }

    // ------------------------------------------------------------------ the limiter

    @Test
    void anAttemptBeyondTheAllowanceIsRefusedWithHowLongToWait() {
        RateLimiter limiter = new RateLimiter(properties(true));
        Duration window = Duration.ofMinutes(10);
        limiter.hit("k", 2, window);
        limiter.hit("k", 2, window);
        assertThatThrownBy(() -> limiter.hit("k", 2, window)).isInstanceOfSatisfying(RateLimitedException.class, e -> {
            assertThat(e.getStatus().value()).isEqualTo(429);
            assertThat(e.getRetryAfterSeconds()).isBetween(1L, 600L);
        });
    }

    @Test
    void keysDoNotShareAnAllowance() {
        RateLimiter limiter = new RateLimiter(properties(true));
        limiter.hit("a", 1, Duration.ofMinutes(1));
        assertThatThrownBy(() -> limiter.hit("a", 1, Duration.ofMinutes(1))).isInstanceOf(RateLimitedException.class);
        assertThatCode(() -> limiter.hit("b", 1, Duration.ofMinutes(1))).doesNotThrowAnyException();
    }

    @Test
    void theAllowanceComesBackWhenTheWindowHasPassed() throws Exception {
        RateLimiter limiter = new RateLimiter(properties(true));
        Duration window = Duration.ofMillis(300);
        limiter.hit("k", 1, window);
        assertThatThrownBy(() -> limiter.hit("k", 1, window)).isInstanceOf(RateLimitedException.class);
        Thread.sleep(400);
        assertThatCode(() -> limiter.hit("k", 1, window)).doesNotThrowAnyException();
    }

    @Test
    void checkingWithoutCountingAndResettingBehave() {
        RateLimiter limiter = new RateLimiter(properties(true));
        Duration window = Duration.ofMinutes(1);
        assertThat(limiter.isLimited("k", 1, window)).isFalse();
        limiter.hit("k", 1, window);
        assertThat(limiter.isLimited("k", 1, window)).isTrue();
        assertThat(limiter.isLimited("k", 1, window)).isTrue();   // looking does not use anything up
        limiter.reset("k");
        assertThat(limiter.isLimited("k", 1, window)).isFalse();
    }

    @Test
    void whenSwitchedOffNothingIsEverLimited() {
        RateLimiter limiter = new RateLimiter(properties(false));
        for (int i = 0; i < 100; i++) {
            limiter.hit("k", 1, Duration.ofMinutes(1));
        }
        assertThat(limiter.isLimited("k", 1, Duration.ofMinutes(1))).isFalse();
    }

    // ------------------------------------------------------------------ the public-endpoint interceptor

    @Test
    void everyPublicEndpointIsLimitedPerCallerAddress() {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(new RateLimiter(properties(true)), properties(true));
        String[][] cases = {
                {"POST", "/api/v1/auth/login", "3"},
                {"POST", "/api/v1/auth/register", "2"},
                {"POST", "/api/v1/auth/forgot-password", "2"},
                {"POST", "/api/v1/auth/reset-password", "2"},
                {"POST", "/api/v1/support/auth/login", "2"},
                {"POST", "/api/v1/public/payments/demo", "2"},
                {"GET", "/api/v1/r/some-token", "4"},
                {"POST", "/api/v1/r/some-token/accept", "4"}};
        int n = 0;
        for (String[] c : cases) {
            String ip = "10.1.0." + (++n);
            int allowed = Integer.parseInt(c[2]);
            // Both recipient paths share one allowance per address; give each its own address here.
            for (int i = 0; i < allowed; i++) {
                assertThat(pass(interceptor, c[0], c[1], ip)).as(c[1] + " attempt " + (i + 1)).isTrue();
            }
            assertThatThrownBy(() -> pass(interceptor, c[0], c[1], ip)).as(c[1])
                    .isInstanceOf(RateLimitedException.class);
            assertThat(pass(interceptor, c[0], c[1], "10.2.0." + n)).as(c[1] + " from another address").isTrue();
        }
    }

    @Test
    void oneEndpointsAttemptsDoNotUseUpAnothers() {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(new RateLimiter(properties(true)), properties(true));
        for (int i = 0; i < 2; i++) pass(interceptor, "POST", "/api/v1/auth/register", "10.3.0.1");
        assertThatThrownBy(() -> pass(interceptor, "POST", "/api/v1/auth/register", "10.3.0.1"))
                .isInstanceOf(RateLimitedException.class);
        assertThat(pass(interceptor, "POST", "/api/v1/auth/login", "10.3.0.1")).isTrue();   // same address, other kind
    }

    @Test
    void preflightRequestsAndOtherPathsAreNeverCounted() {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(new RateLimiter(properties(true)), properties(true));
        for (int i = 0; i < 50; i++) {
            assertThat(pass(interceptor, "OPTIONS", "/api/v1/auth/login", "10.4.0.1")).isTrue();
            assertThat(pass(interceptor, "GET", "/api/v1/handoffs", "10.4.0.1")).isTrue();
        }
    }
}
