package com.handoffly.ai;

import com.google.genai.errors.ApiException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The one retry the Gemini call makes: for a hiccup (a timeout, an overload), never for a refusal. */
class GeminiRetryTest {

    private final AtomicInteger calls = new AtomicInteger();

    @Test
    void aCallThatWorksIsMadeOnce() {
        assertThat(GeminiStructuredModel.withOneRetry(() -> { calls.incrementAndGet(); return "ok"; })).isEqualTo("ok");
        assertThat(calls).hasValue(1);
    }

    @Test
    void aTimeoutIsTriedOnceMoreAndTheSecondAnswerIsUsed() {
        String answer = GeminiStructuredModel.withOneRetry(() -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("timed out");
            return "second try";
        });
        assertThat(answer).isEqualTo("second try");
        assertThat(calls).hasValue(2);
    }

    @Test
    void anOverloadIsTriedOnceMore() {
        String answer = GeminiStructuredModel.withOneRetry(() -> {
            if (calls.incrementAndGet() == 1) throw new ApiException(503, "UNAVAILABLE", "high demand");
            return "second try";
        });
        assertThat(answer).isEqualTo("second try");
        assertThat(calls).hasValue(2);
    }

    @Test
    void itGivesUpAfterTheSecondFailure() {
        assertThatThrownBy(() -> GeminiStructuredModel.withOneRetry(() -> { calls.incrementAndGet(); throw new IllegalStateException("timed out"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(calls).hasValue(2);
    }

    @Test
    void aRefusalIsNeverRetried() {
        for (int code : new int[]{400, 401, 403, 404, 429}) {
            calls.set(0);
            assertThatThrownBy(() -> GeminiStructuredModel.withOneRetry(() -> { calls.incrementAndGet(); throw new ApiException(code, "REFUSED", "no"); }))
                    .isInstanceOf(ApiException.class);
            assertThat(calls).as("status " + code).hasValue(1);
        }
    }
}
