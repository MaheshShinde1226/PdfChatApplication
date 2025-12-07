package com.ai.pdfchat.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "ollama")
@Data
public class OllamaProperties {
    private String baseUrl;
    private String embedModel;
    private String genModel;

}
