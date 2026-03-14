package com.ai.pdfchat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "openai")
@Data
public class OpenAiProperties {
    /**
     * OpenAI API key (required). Prefer environment variable OPENAI_API_KEY or config.
     */
    private String apiKey;
    /**
     * Embedding model, e.g. text-embedding-3-small, text-embedding-3-large.
     */
    private String embedModel = "text-embedding-3-small";
    /**
     * Chat model for RAG answers, e.g. gpt-4o-mini, gpt-4o.
     */
    private String chatModel = "gpt-4o-mini";
}
