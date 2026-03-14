package com.ai.pdfchat.controller;

import com.ai.pdfchat.model.AskRequest;
import com.ai.pdfchat.service.PdfIngestService;
import com.ai.pdfchat.service.RagService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/rag")
@CrossOrigin("*")
public class RagController {
    private final PdfIngestService ingestService;
    private final RagService ragService;


    public RagController(PdfIngestService ingestService, RagService ragService) {
        this.ingestService = ingestService;
        this.ragService = ragService;
    }


    /**
     * Ingest a document (PDF, TXT, or MD). Returns a documentId that identifies this upload.
     */
    @PostMapping("/ingest")
    public ResponseEntity<?> ingestDocument(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "file is required"));
        }
        if (!ingestService.isSupportedFormat(file.getOriginalFilename())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Unsupported file format. Supported: pdf, txt, md, text"));
        }
        String documentId = ingestService.ingest(file);
        return ResponseEntity.ok(Map.of("status", "ingested", "documentId", documentId));
    }


    @PostMapping("/ask")
    public ResponseEntity<?> ask(@RequestBody AskRequest req) {
        if (req == null || req.getQuestion() == null || req.getQuestion().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "question is required"));
        }
        try {
            String ans = ragService.answerQuestion(req.getQuestion(), req.getDocumentId());
            return ResponseEntity.ok(Map.of("answer", ans));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "internal error"));
        }
    }
}
