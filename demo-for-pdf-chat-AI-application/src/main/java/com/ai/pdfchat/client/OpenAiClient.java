package com.ai.pdfchat.client;

import com.ai.pdfchat.config.OpenAiProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class OpenAiClient {

    private static final String BASE_URL = "https://api.openai.com";

    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String embedModel;
    private final String chatModel;

    public OpenAiClient(OpenAiProperties props, WebClient.Builder webClientBuilder) {
        this.embedModel = props.getEmbedModel() != null ? props.getEmbedModel() : "text-embedding-3-small";
        this.chatModel = props.getChatModel() != null ? props.getChatModel() : "gpt-4o-mini";
        String apiKey = props.getApiKey();
        System.err.print("openai key : "+apiKey);
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = System.getenv("OPENAI_API_KEY");
        }
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("OpenAI API key not set (openai.api-key or OPENAI_API_KEY). Embed and chat calls will fail.");
        }
        final String bearer = apiKey != null ? apiKey : "";
        this.webClient = webClientBuilder
                .baseUrl(BASE_URL)
                .defaultHeader("Authorization", "Bearer " + bearer)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    /**
     * Get embedding vector for the given text using OpenAI Embeddings API.
     *
     * @param text input text
     * @return list of embedding dimensions, or null on failure
     */
    public List<Double> embed(String text) {
        if (text == null) return null;

        Map<String, Object> body = Map.of(
                "model", embedModel,
                "input", text
        );

        JsonNode resp;
        try {
            resp = webClient.post()
                    .uri("/v1/embeddings")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(30));
        } catch (Exception ex) {
            log.error("OpenAI embed request failed: {}", ex.getMessage(), ex);
            return null;
        }

        if (resp == null) {
            log.error("OpenAI embed returned null response for text len={}", text.length());
            return null;
        }

        JsonNode data = resp.has("data") && resp.get("data").isArray() && resp.get("data").size() > 0
                ? resp.get("data").get(0)
                : null;
        if (data == null || !data.has("embedding")) {
            log.error("Unexpected OpenAI embed response; resp={}", resp.toString());
            return null;
        }

        JsonNode embNode = data.get("embedding");
        if (!embNode.isArray()) {
            log.error("OpenAI embedding is not an array");
            return null;
        }

        try {
            return mapper.convertValue(embNode, new TypeReference<List<Double>>() {});
        } catch (Exception e) {
            log.error("Failed to convert embedding to List<Double>: {}", e.getMessage(), e);
            return null;
        }
    }

    /**
     * Generate chat completion for the given prompt (single user message).
     *
     * @param prompt user message
     * @return assistant reply text, or empty string on failure
     */
    public String generate(String prompt) {
        if (prompt == null) return "";

        Map<String, Object> message = Map.of("role", "user", "content", prompt);
        Map<String, Object> body = Map.of(
                "model", chatModel,
                "messages", List.of(message),
                "max_tokens", 1024
        );

        JsonNode resp;
        try {
            resp = webClient.post()
                    .uri("/v1/chat/completions")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(120));
        } catch (Exception ex) {
            log.error("OpenAI chat request failed: {}", ex.getMessage(), ex);
            return "";
        }

        if (resp == null || !resp.has("choices") || !resp.get("choices").isArray() || resp.get("choices").size() == 0) {
            log.error("OpenAI chat returned no choices; resp={}", resp != null ? resp.toString() : "null");
            return "";
        }

        JsonNode first = resp.get("choices").get(0);
        if (first.has("message") && first.get("message").has("content")) {
            return first.get("message").get("content").asText("");
        }
        if (first.has("text")) {
            return first.get("text").asText("");
        }
        return "";
    }
}
