package com.handoffly.auth;

import com.handoffly.testsupport.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The limits as a caller meets them: 429 with Retry-After, counted per address and per email so that one
 * caller can neither be locked out by another nor learn anything from being limited.
 */
@TestPropertySource(properties = {
        "handoffly.rate-limit.enabled=true",
        "handoffly.rate-limit.login-failures-per-account=3",
        "handoffly.rate-limit.forgot-per-email=2",
        "handoffly.rate-limit.forgot-per-ip=5",
        "handoffly.rate-limit.password-change-failures=3",
        "handoffly.rate-limit.login-per-ip=1000",
        "handoffly.rate-limit.register-per-ip=1000"})
class AuthRateLimitTest extends ApiTestBase {

    private static RequestPostProcessor from(String address) {
        return request -> {
            request.setRemoteAddr(address);
            return request;
        };
    }

    private ResultActions login(String email, String password, String address) throws Exception {
        return mvc.perform(post("/api/v1/auth/login").with(from(address)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"));
    }

    private ResultActions forgot(String email, String address) throws Exception {
        return mvc.perform(post("/api/v1/auth/forgot-password").with(from(address))
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + email + "\"}"));
    }

    @Test
    void repeatedFailedSignInsAreRefusedForThatEmailFromThatAddressOnly() throws Exception {
        Account victim = register();
        Account other = register();

        for (int i = 0; i < 3; i++) {
            login(victim.email(), "wrong-password-" + i, "10.9.0.1").andExpect(status().isUnauthorized());
        }
        // The fourth try is refused - even with the right password - and says how long to wait.
        login(victim.email(), PASSWORD, "10.9.0.1").andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("rate_limited"));
        // The real owner, from elsewhere, is not locked out by someone else's guesses...
        login(victim.email(), PASSWORD, "10.9.0.2").andExpect(status().isOk());
        // ...and the same address can still sign in to a different account.
        login(other.email(), PASSWORD, "10.9.0.1").andExpect(status().isOk());
    }

    @Test
    void unknownEmailsAreLimitedExactlyLikeKnownOnesSoTheLimitRevealsNothing() throws Exception {
        String stranger = "nobody-" + System.nanoTime() + "@example.test";
        for (int i = 0; i < 3; i++) {
            login(stranger, "whatever-123", "10.9.1.1").andExpect(status().isUnauthorized());
        }
        login(stranger, "whatever-123", "10.9.1.1").andExpect(status().isTooManyRequests());
    }

    @Test
    void aSuccessfulSignInClearsTheFailureCount() throws Exception {
        Account customer = register();
        login(customer.email(), "wrong-1", "10.9.2.1").andExpect(status().isUnauthorized());
        login(customer.email(), "wrong-2", "10.9.2.1").andExpect(status().isUnauthorized());
        login(customer.email(), PASSWORD, "10.9.2.1").andExpect(status().isOk());
        for (int i = 0; i < 3; i++) {
            login(customer.email(), "wrong-again-" + i, "10.9.2.1").andExpect(status().isUnauthorized());   // a fresh allowance
        }
    }

    @Test
    void resetLinksForOneEmailAreLimitedWhetherOrNotTheAccountExists() throws Exception {
        Account customer = register();
        String stranger = "nobody-" + System.nanoTime() + "@example.test";
        for (String email : new String[]{customer.email(), stranger}) {
            forgot(email, "10.9.3.1").andExpect(status().isNoContent());
            forgot(email, "10.9.3.1").andExpect(status().isNoContent());
            forgot(email, "10.9.3.1").andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        }
    }

    @Test
    void resetRequestsFromOneAddressAreLimitedAcrossDifferentEmails() throws Exception {
        for (int i = 0; i < 5; i++) {
            forgot("person-" + i + "-" + System.nanoTime() + "@example.test", "10.9.4.1").andExpect(status().isNoContent());
        }
        forgot("one-more-" + System.nanoTime() + "@example.test", "10.9.4.1").andExpect(status().isTooManyRequests());
        forgot("someone-else-" + System.nanoTime() + "@example.test", "10.9.4.2").andExpect(status().isNoContent());
    }

    @Test
    void guessingTheCurrentPasswordIsLimitedPerAccountAndAnswersTheSameOtherwise() throws Exception {
        Account customer = register();
        for (int i = 0; i < 3; i++) {
            mvc.perform(com.handoffly.testsupport.ApiTestBase.as(customer, post("/api/v1/auth/change-password")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"currentPassword\":\"guess-" + i + "\",\"newPassword\":\"brand-new-password\",\"confirmPassword\":\"brand-new-password\"}")))
                    .andExpect(status().isBadRequest());
        }
        // Now even the right current password is refused for a while.
        var refused = mvc.perform(com.handoffly.testsupport.ApiTestBase.as(customer, post("/api/v1/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + PASSWORD + "\",\"newPassword\":\"brand-new-password\",\"confirmPassword\":\"brand-new-password\"}")))
                .andExpect(status().isTooManyRequests()).andReturn();
        assertThat(refused.getResponse().getHeader("Retry-After")).isNotBlank();
        login(customer.email(), PASSWORD, "10.9.5.1").andExpect(status().isOk());   // the password was never changed
    }
}
