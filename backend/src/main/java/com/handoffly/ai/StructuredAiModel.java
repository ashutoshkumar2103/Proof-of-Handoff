package com.handoffly.ai;

import java.util.Map;

/**
 * The one door to an AI model: give it an instruction, some data and the JSON schema the answer must follow, and get the answer's JSON
 * text back. Nothing here knows what the answer means — the caller validates it — so a provider can be swapped, and tests can stand in
 * for it without any network.
 */
public interface StructuredAiModel {

    /** Whether the model is set up at all (a key is present). When it is not, callers do not even count an attempt. */
    boolean isConfigured();

    /**
     * @param instruction  what to do, and that the data is data (never instructions)
     * @param data         the data to work on, already reduced to the minimum needed
     * @param jsonSchema   the JSON schema the answer must follow
     * @return the model's answer as JSON text
     * @throws AiUnavailableException when no usable answer came back (the reason is a category, not the provider's message)
     */
    String generateJson(String instruction, String data, Map<String, Object> jsonSchema);
}
