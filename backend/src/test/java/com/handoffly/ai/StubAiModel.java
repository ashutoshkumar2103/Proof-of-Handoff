package com.handoffly.ai;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

/**
 * Stands in for the AI provider in tests: no network, no key. A test sets what it answers (or that it fails), and afterwards reads how
 * often it was asked and exactly what it was sent.
 */
public class StubAiModel implements StructuredAiModel {

    /** One request the model received. */
    public record Call(String instruction, String data) {}

    private volatile boolean configured = true;
    private volatile String answer = "{\"headerRow\":-1,\"suggestions\":[]}";
    private volatile AiUnavailableException.Reason failure;
    private final List<Call> calls = new CopyOnWriteArrayList<>();

    @Override
    public boolean isConfigured() {
        return configured;
    }

    @Override
    public String generateJson(String instruction, String data, Map<String, Object> jsonSchema) {
        calls.add(new Call(instruction, data));
        if (failure != null) {
            throw new AiUnavailableException(failure);
        }
        return answer;
    }

    public void answer(String json) { this.answer = json; this.failure = null; }
    public void fail(AiUnavailableException.Reason reason) { this.failure = reason; }
    public void configured(boolean configured) { this.configured = configured; }
    public List<Call> calls() { return List.copyOf(calls); }

    /** Back to the starting state, between tests. */
    public void reset() {
        configured = true;
        failure = null;
        answer = "{\"headerRow\":-1,\"suggestions\":[]}";
        calls.clear();
    }

    @TestConfiguration
    public static class Config {
        @Bean
        @Primary
        public StubAiModel stubAiModel() {
            return new StubAiModel();
        }
    }
}
