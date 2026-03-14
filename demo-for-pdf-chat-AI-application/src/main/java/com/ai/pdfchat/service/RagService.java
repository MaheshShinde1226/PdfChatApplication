package com.ai.pdfchat.service;

import com.ai.pdfchat.client.OpenAiClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class RagService {
    private final JdbcTemplate jdbc;
    private final OpenAiClient openAi;
    private final ObjectMapper mapper = new ObjectMapper();
    private final int topK = 6;


    public RagService(JdbcTemplate jdbc, OpenAiClient openAi) {
        this.jdbc = jdbc;
        this.openAi = openAi;
    }


    /**
     * Search for top-k chunks by embedding similarity. If documentId is non-blank, only chunks from that document are considered.
     */
    public List<Map<String, Object>> similaritySearch(List<Double> qEmbedding, int topK, String documentId) {
        String embLiteral = "[" + qEmbedding.stream().map(Object::toString).collect(Collectors.joining(",")) + "]";
        String sql;
        List<Object> args;
        if (documentId != null && !documentId.isBlank()) {
            sql = "SELECT id, content, metadata FROM document_chunks WHERE metadata->>'documentId' = ? ORDER BY embedding <-> (?::vector) LIMIT ?";
            args = List.of(documentId, embLiteral, topK);
        } else {
            sql = "SELECT id, content, metadata FROM document_chunks ORDER BY embedding <-> (?::vector) LIMIT ?";
            args = List.of(embLiteral, topK);
        }
        return jdbc.queryForList(sql, args.toArray());
    }


    /**
     * Answer a question using RAG. If documentId is provided, only chunks from that document are used.
     */
    public String answerQuestion(String question, String documentId) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question must not be null or blank");
        }

        // Try to get embedding, with a small retry logic
        List<Double> qEmb = embedWithRetry(question, 2);
        log.debug("embed() result size={} resp={}", qEmb==null?0:qEmb.size(), qEmb);
        if (qEmb == null || qEmb.isEmpty()) {
            throw new IllegalStateException("Failed to generate query embedding from OpenAI");
        }

        List<Map<String, Object>> chunks = similaritySearch(qEmb, topK, documentId);
        if (chunks == null || chunks.isEmpty()) {
            // optional: return a polite reply rather than ask the LLM with no context
            return "I couldn't find any relevant document excerpts to answer that.";
        }

        String prompt = buildPrompt(chunks, question);
        String answer = openAi.generate(prompt);
        return answer == null ? "" : answer;
    }

    public List<Double> embedWithRetry(String text, int maxAttempts) {
        int attempt = 0;
        while (attempt < maxAttempts) {
            attempt++;
            try {
                List<Double> emb = openAi.embed(text);
                if (emb != null && !emb.isEmpty()) {
                    return emb;
                }
                log.warn("OpenAI embed returned empty on attempt {}", attempt);
            } catch (Exception ex) {
                log.warn("OpenAI embed failed on attempt {}: {}", attempt, ex.getMessage());
            }
            // small backoff
            try { Thread.sleep(300L * attempt); } catch (InterruptedException ignored) {}
        }
        log.error("OpenAI embed failed after {} attempts for text length={}", maxAttempts, text == null ? 0 : text.length());
        return null;
    }


    private String buildPrompt(List<Map<String, Object>> chunks, String question) {
        StringBuilder sb = new StringBuilder();

        sb.append("System: You are an assistant that answers user questions using ONLY the provided document excerpts. ");
        sb.append("If the answer is not found in those excerpts, reply exactly: \"I don't know\".\n\n");

        sb.append("=== DOCUMENT EXCERPTS (use these only) ===\n");
        int idx = 1;
        for (Map<String, Object> c : chunks) {
            sb.append("[").append(idx++).append("] ");
            sb.append(c.get("content")).append("\n\n");
        }

        sb.append("=== USER QUESTION ===\n");
        sb.append(question).append("\n\n");

        sb.append("=== INSTRUCTIONS ===\n");
        sb.append("- Provide one complete answer only. Start your final output with the literal prefix: \"Answer: \" followed by the answer text.\n");
        sb.append("- Do NOT return only the word \"Answer\". The text after the prefix must contain the actual answer.\n");
        sb.append("- If you must cite an excerpt, include its number in square brackets, e.g. [2].\n");
        sb.append("- If no answer is present in the excerpts, output exactly: \"I don't know\"\n\n");

        sb.append("=== EXAMPLE ===\n");
        sb.append("Question: What color is the sky?\n");
        sb.append("Answer: The sky usually appears blue during the day due to Rayleigh scattering [1].\n\n");

        sb.append("Now answer below.\n");
        sb.append("Answer:"); // model should continue after this
        return sb.toString();
    }


}
