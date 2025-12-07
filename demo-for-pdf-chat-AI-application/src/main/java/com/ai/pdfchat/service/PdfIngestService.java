package com.ai.pdfchat.service;

import com.ai.pdfchat.client.OllamaClient;
import com.ai.pdfchat.model.DocumentChunk;
import com.ai.pdfchat.repo.DocumentChunkRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PdfIngestService {

    private final DocumentChunkRepository repo;
    private final JdbcTemplate jdbc;
    private final OllamaClient ollama;
    private final ObjectMapper mapper = new ObjectMapper();


    private final int chunkSize = 800; // you can inject via properties
    private final int chunkOverlap = 150;


    public PdfIngestService(DocumentChunkRepository repo, JdbcTemplate jdbc, OllamaClient ollama) {
        this.repo = repo;
        this.jdbc = jdbc;
        this.ollama = ollama;
    }


    // ingest method (instrumented)
    public void ingest(MultipartFile file) throws IOException {
        String text = extractText(file);
        List<String> chunks = chunkText(text, chunkSize, chunkOverlap);

        for (int i = 0; i < chunks.size(); i++) {
            String chunk = chunks.get(i);
            log.info("Processing chunk #{} (len={})", i, chunk == null ? 0 : chunk.length());

            // 1) get embedding from Ollama (with single retry)
            List<Double> emb = null;
            try {
                emb = ollama.embed(chunk);
                if (emb == null || emb.isEmpty()) {
                    log.warn("First embed attempt returned null/empty for chunk {}", i);
                    emb = ollama.embed(chunk); // retry once
                }
            } catch (Exception e) {
                log.error("Exception while calling ollama.embed for chunk {}: {}", i, e.getMessage(), e);
            }

            log.info("Embedding result for chunk {}: {}", i, emb == null ? "null" : ("size=" + emb.size()));

            // 2) save entity WITHOUT metadata/embedding
            DocumentChunk dc = new DocumentChunk();
            dc.setSourceFilename(file.getOriginalFilename());
            dc.setChunkIndex(i);
            dc.setContent(chunk);
            dc = repo.save(dc);
            log.info("Saved DocumentChunk id={}", dc.getId());

            // 3) save metadata (always)
            String metadataJson = mapper.writeValueAsString(Map.of("source", file.getOriginalFilename(), "chunkIndex", i));
            try {
                saveMetadata(dc.getId(), metadataJson);
                log.info("Saved metadata for id={}", dc.getId());
            } catch (Exception e) {
                log.error("Failed to save metadata for id={}: {}", dc.getId(), e.getMessage(), e);
            }

            // 4) save embedding if available
            if (emb == null || emb.isEmpty()) {
                log.warn("Embedding is missing for id={} chunk={} — skipping embedding update", dc.getId(), i);
                continue;
            }

            try {
                int updated = saveEmbedding(dc.getId(), emb);
                log.info("saveEmbedding returned updatedCount={} for id={}", updated, dc.getId());
                if (updated == 0) {
                    log.warn("Embedding update affected 0 rows for id={}", dc.getId());
                }
            } catch (Exception e) {
                log.error("Failed to save embedding for id={}: {}", dc.getId(), e.getMessage(), e);
            }
        }
    }

    /** returns rows updated */
    private int saveEmbedding(Long chunkId, List<Double> embedding) {
        if (embedding == null || embedding.isEmpty()) return 0;

        // validate elements (no NaN/Infinity/null)
        for (Double d : embedding) {
            if (d == null || d.isNaN() || d.isInfinite()) {
                throw new IllegalArgumentException("Embedding contains invalid value (null/NaN/Infinite)");
            }
        }

        String embLiteral = "[" + embedding.stream()
                .map(d -> {
                    // ensure consistent decimal separator
                    return String.format(Locale.US, "%.12f", d);
                })
                .collect(Collectors.joining(",")) + "]";

        log.debug("Embedding literal for id {} preview: {}", chunkId, embLiteral.length() > 200 ? embLiteral.substring(0,200) + "..." : embLiteral);

        String sql = "UPDATE document_chunks SET embedding = ?::vector WHERE id = ?";
        // returns number of rows updated
        return jdbc.update(sql, embLiteral, chunkId);
    }

    // metadata helper
    private int saveMetadata(Long chunkId, String metadataJson) {
        String sql = "UPDATE document_chunks SET metadata = ?::jsonb WHERE id = ?";
        return jdbc.update(sql, metadataJson, chunkId);
    }


    private String extractText(MultipartFile file) throws IOException {
        try (PDDocument doc = Loader.loadPDF(file.getInputStream().readAllBytes())) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(doc);
        }
    }


    private List<String> chunkText(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(text.length(), start + chunkSize);
            String c = text.substring(start, end).trim();
            if (!c.isEmpty()) chunks.add(c);
            if (end == text.length()) break;
            start = Math.max(end - overlap, end);
        }
        return chunks;
    }


//    private void saveEmbedding(Long chunkId, List<Double> embedding) {
//        if (embedding == null || embedding.isEmpty()) {
//            log.warn("saveEmbedding called with null/empty embedding for chunkId {}", chunkId);
//            return;
//        }
//
//        // build Postgres vector literal: [0.12,0.34,...]
//        String embLiteral = "[" + embedding.stream()
//                .map(Object::toString)
//                .collect(Collectors.joining(",")) + "]";
//
//        // explicit ::vector cast ensures Postgres interprets text as vector
//        String sql = "UPDATE document_chunks SET embedding = ?::vector WHERE id = ?";
//        jdbc.update(sql, embLiteral, chunkId);
//    }


}
