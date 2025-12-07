package com.ai.pdfchat.client;

import com.ai.pdfchat.config.OllamaProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class OllamaClient {
    private final WebClient webClient;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String genModel; // inject via properties

    // configurable
    private final Duration GENERATE_TIMEOUT = Duration.ofSeconds(300);
    private final int MAX_POLL_ATTEMPTS = 10;
    private final long POLL_SLEEP_MS = 800L;

    public OllamaClient(OllamaProperties props, WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.baseUrl(props.getBaseUrl()).build();
        this.genModel = props.getGenModel();
    }

    /**
     * Public API: generate final answer text for prompt.
     */
    public String generate(String prompt) {
        String raw = generateRaw(prompt);
        String cleaned = extractFinalAnswer(raw);
        return cleaned;
    }

    /**
     * Low-level: call Ollama generate/chat endpoints, poll if partial, and return the raw textual content (not JSON).
     */
    private String generateRaw(String prompt) {
        if (prompt == null) return "";

        Map<String, Object> body = Map.of(
                "model", "mistral",
                "prompt", prompt,
                "max_tokens", 1024
        );

        String[] endpoints = { "/api/generate", "/api/chat", "/v1/chat/completions" };

        for (String endpoint : endpoints) {
            try {
                log.debug("Trying Ollama endpoint {}", endpoint);

                JsonNode resp = webClient.post()
                        .uri(endpoint)
                        .bodyValue(endpoint.equals("/v1/chat/completions") ? makeOpenAIBody(prompt) : body)
                        .retrieve()
                        .onStatus(s -> s.value() == 404, cr -> Mono.error(new RuntimeException("endpoint-not-found")))
                        .bodyToMono(JsonNode.class)
                        .block(GENERATE_TIMEOUT);

                if (resp == null) {
                    log.warn("Null response from {}. Trying next endpoint.", endpoint);
                    continue;
                }

                log.debug("Initial response from {}: {}", endpoint, resp.toString());

                // 1) If Ollama streaming shape with response + done
                if (resp.has("response") && resp.has("done")) {
                    String accum = resp.get("response").asText("");
                    boolean done = resp.get("done").asBoolean(false);

                    int poll = 0;
                    while (!done && poll < MAX_POLL_ATTEMPTS) {
                        poll++;
                        try { Thread.sleep(POLL_SLEEP_MS * poll); } catch (InterruptedException ignored) {}
                        JsonNode follow = webClient.post()
                                .uri(endpoint)
                                .bodyValue(endpoint.equals("/v1/chat/completions") ? makeOpenAIBody(prompt) : body)
                                .retrieve()
                                .bodyToMono(JsonNode.class)
                                .block(GENERATE_TIMEOUT);
                        if (follow == null) break;
                        log.debug("Poll #{} response from {}: {}", poll, endpoint, follow.toString());
                        if (follow.has("response")) accum = follow.get("response").asText("");
                        if (follow.has("done")) done = follow.get("done").asBoolean(false);
                    }
                    return accum;
                }

                // 2) output array
                if (resp.has("output") && resp.get("output").isArray()) {
                    StringBuilder sb = new StringBuilder();
                    for (JsonNode n : resp.get("output")) sb.append(n.asText(""));
                    String out = sb.toString();
                    if (!out.isBlank()) return out;
                }

                // 3) OpenAI choices style
                if (resp.has("choices") && resp.get("choices").isArray() && resp.get("choices").size() > 0) {
                    JsonNode first = resp.get("choices").get(0);
                    if (first.has("message") && first.get("message").has("content")) {
                        return first.get("message").get("content").asText("");
                    }
                    if (first.has("text")) {
                        return first.get("text").asText("");
                    }
                }

                // 4) results/content
                if (resp.has("results") && resp.get("results").isArray() && resp.get("results").size() > 0) {
                    JsonNode r0 = resp.get("results").get(0);
                    if (r0.has("content")) return r0.get("content").asText("");
                }

                // 5) messages array
                if (resp.has("messages") && resp.get("messages").isArray()) {
                    StringBuilder sb = new StringBuilder();
                    for (JsonNode m : resp.get("messages")) {
                        if (m.has("text")) sb.append(m.get("text").asText(""));
                        if (m.has("content")) sb.append(m.get("content").asText(""));
                    }
                    if (sb.length() > 0) return sb.toString();
                }

                // 6) fallback: if response text is very short, do a few short polls trying to get more
                String asText = resp.toString();
                if (asText.length() < 30) {
                    String last = asText;
                    for (int p = 0; p < 5; p++) {
                        try { Thread.sleep(600L * (p + 1)); } catch (InterruptedException ignored) {}
                        JsonNode follow = webClient.post()
                                .uri(endpoint)
                                .bodyValue(endpoint.equals("/v1/chat/completions") ? makeOpenAIBody(prompt) : body)
                                .retrieve()
                                .bodyToMono(JsonNode.class)
                                .block(GENERATE_TIMEOUT);
                        if (follow == null) break;
                        log.debug("Short-poll #{}: {}", p + 1, follow.toString());
                        if (follow.has("response")) last = follow.get("response").asText("");
                        else if (follow.has("output") && follow.get("output").isArray()) {
                            StringBuilder sb = new StringBuilder();
                            for (JsonNode n : follow.get("output")) sb.append(n.asText(""));
                            last = sb.toString();
                        } else if (follow.has("choices") && follow.get("choices").isArray()) {
                            JsonNode first = follow.get("choices").get(0);
                            if (first.has("message") && first.get("message").has("content")) last = first.get("message").get("content").asText("");
                            else if (first.has("text")) last = first.get("text").asText("");
                        }
                        if (last.length() > 20) return last;
                    }
                    return last;
                }

                // final fallback: return the JSON as text (rare)
                return asText;

            } catch (RuntimeException ex) {
                if (ex.getMessage() != null && ex.getMessage().contains("endpoint-not-found")) {
                    log.info("Endpoint {} not found, trying next", endpoint);
                    continue;
                }
                log.warn("Error calling {}: {}", endpoint, ex.getMessage(), ex);
            } catch (Exception ex) {
                log.warn("Exception calling {}: {}", endpoint, ex.getMessage(), ex);
            }
        }

        throw new IllegalStateException("No working Ollama endpoint found or generation failed");
    }

    /**
     * Extract the final human-readable answer from raw model text.
     * Looks for "Answer:" marker, falls back to heuristics.
     */
    private String extractFinalAnswer(String modelResponseText) {
        if (modelResponseText == null) return "";

        // 1) prefer content after "Answer:" marker
        Pattern p = Pattern.compile("(?is)Answer:\\s*(.+)"); // DOTALL + case-insensitive
        Matcher m = p.matcher(modelResponseText);
        if (m.find()) {
            String after = m.group(1).trim();
            // if model returned only the label, fallback to full text
            if (after.equalsIgnoreCase("") || after.equalsIgnoreCase("Answer")) {
                // fallback to cleaned response if it's longer
                String cleaned = modelResponseText.trim();
                if (cleaned.length() > 10) return cleaned;
                return "I couldn't generate a complete answer. Please try again.";
            }
            return after;
        }

        // 2) If response is very short or just a token, treat as incomplete
        String trimmed = modelResponseText.trim();
        if (trimmed.split("\\s+").length <= 2) {
            return "I couldn't generate a complete answer. Please try again.";
        }

        // 3) Otherwise return trimmed response
        return trimmed;
    }

    /** Make OpenAI-compatible body for fallback endpoint */
    private Map<String, Object> makeOpenAIBody(String prompt) {
        Map<String, Object> message = Map.of("role", "user", "content", prompt);
        return Map.of("model", genModel, "messages", List.of(message), "max_tokens", 1024);
    }

    public List<Double> embed(String text) {
        if (text == null) return null;
        Map<String,Object> body = Map.of("model", "mxbai-embed-large", "input", text);

        JsonNode resp;
        try {
            resp = webClient.post()
                    .uri("/api/embed")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(10)); // avoid infinite block
        } catch (Exception ex) {
            log.error("Ollama embed request failed: {}", ex.getMessage(), ex);
            return null;
        }

        if (resp == null) {
            log.error("Ollama embed returned null response for text len={}", text.length());
            return null;
        }

        // log raw response once for debugging (comment out later)
        log.debug("Raw Ollama embed response: {}", resp.toString());

        // handle common response shapes
        JsonNode embNode = null;
        if (resp.has("embedding") && resp.get("embedding").isArray()) {
            embNode = resp.get("embedding");
        } else if (resp.has("embeddings") && resp.get("embeddings").isArray()) {
            // embeddings might be array-of-arrays
            JsonNode arr = resp.get("embeddings");
            if (arr.size() > 0 && arr.get(0).isArray()) embNode = arr.get(0);
            else embNode = arr;
        } else if (resp.has("data") && resp.get("data").isArray()) {
            // other formats sometimes use data: [{ embedding: [...] }]
            JsonNode d0 = resp.get("data").get(0);
            if (d0 != null && d0.has("embedding")) embNode = d0.get("embedding");
        }

        if (embNode == null || !embNode.isArray()) {
            log.error("Unexpected embed response shape; resp={}", resp.toString());
            return null;
        }

        // convert to List<Double>
        try {
            return mapper.convertValue(embNode, new TypeReference<List<Double>>() {});
        } catch (Exception e) {
            log.error("Failed to convert embedding node to List<Double>: {}", e.getMessage(), e);
            return null;
        }
    }
}
