package com.handoffly.ai;

import com.google.genai.Client;
import com.google.genai.errors.ApiException;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import com.google.genai.types.Part;
import com.google.genai.types.ThinkingConfig;
import com.handoffly.common.config.HandOfflyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Supplier;

/**
 * {@link StructuredAiModel} on Google's Gemini API, through the official Google GenAI SDK. The only class that uses the SDK or sees the
 * API key; the key comes from configuration, is never logged and never leaves the backend. Answers are requested as JSON that follows
 * a schema (not free text), with no retries (a customer is waiting, and the free quota is small) and a short timeout. Whatever goes
 * wrong becomes an {@link AiUnavailableException} with a category; the provider's own message is not kept.
 */
@Component
public class GeminiStructuredModel implements StructuredAiModel {

    private static final Logger log = LoggerFactory.getLogger(GeminiStructuredModel.class);
    private static final int TOO_MANY_REQUESTS = 429;
    private static final int OVERLOADED = 503;

    private final HandOfflyProperties.Ai config;
    private volatile Client client;

    public GeminiStructuredModel(HandOfflyProperties properties) {
        this.config = properties.getAi();
    }

    @Override
    public boolean isConfigured() {
        return config.isConfigured();
    }

    @Override
    public String generateJson(String instruction, String data, Map<String, Object> jsonSchema) {
        if (!isConfigured()) {
            throw new AiUnavailableException(AiUnavailableException.Reason.NOT_CONFIGURED);
        }
        try {
            GenerateContentConfig request = GenerateContentConfig.builder()
                    .systemInstruction(Content.fromParts(Part.fromText(instruction)))
                    .responseMimeType("application/json")
                    .responseJsonSchema(jsonSchema)
                    .temperature(0f)
                    // Picking two columns needs no reasoning, and the model's thinking is most of its delay (about 2 s without it, 6-35 s with).
                    .thinkingConfig(ThinkingConfig.builder().thinkingBudget(0).build())
                    .build();
            GenerateContentResponse response = withOneRetry(() -> client().models.generateContent(config.getGeminiModel(), data, request));
            String text = response.text();
            if (text == null || text.isBlank()) {
                throw new AiUnavailableException(AiUnavailableException.Reason.INVALID_RESPONSE);
            }
            return text;
        } catch (AiUnavailableException e) {
            throw e;
        } catch (ApiException e) {
            log.warn("The AI provider refused a request (status {})", e.code());   // the code only: its message is not for the log either
            throw new AiUnavailableException(e.code() == TOO_MANY_REQUESTS
                    ? AiUnavailableException.Reason.RATE_LIMITED : AiUnavailableException.Reason.UNAVAILABLE);
        } catch (RuntimeException e) {
            log.warn("The AI provider could not be reached ({}, caused by {})", e.getClass().getSimpleName(),
                    e.getCause() == null ? "nothing further" : e.getCause().getClass().getSimpleName());
            throw new AiUnavailableException(AiUnavailableException.Reason.UNAVAILABLE);
        }
    }

    /**
     * Runs the call, and once more if it fails the way a passing hiccup does: it timed out or never got through (not an {@link ApiException}),
     * or the provider said it is overloaded (503). The provider's answer time is erratic — most answers take a few seconds, an occasional
     * request stalls — so a second try is likelier to be answered than waiting longer on the first. A refusal (a quota, a key, a bad request)
     * is not retried: it would only be refused again, and would cost quota.
     */
    static <T> T withOneRetry(Supplier<T> call) {
        try {
            return call.get();
        } catch (ApiException e) {
            if (e.code() != OVERLOADED) throw e;
        } catch (RuntimeException e) {
            // a timeout or a connection that never completed: tried once more below
        }
        return call.get();
    }

    private Client client() {
        Client c = client;
        if (c == null) {
            synchronized (this) {
                if (client == null) {
                    client = Client.builder()
                            .apiKey(config.getGeminiApiKey())
                            .httpOptions(HttpOptions.builder()
                                    .timeout(config.getTimeoutSeconds() * 1000)
                                    .retryOptions(HttpRetryOptions.builder().attempts(1).build())
                                    .build())
                            .build();
                }
                c = client;
            }
        }
        return c;
    }
}
