package com.ai.pdfchat.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "document_chunks")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentChunk {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;


    private String sourceFilename;
    private Integer chunkIndex;


    @Column(columnDefinition = "text")
    private String content;


    @Column(columnDefinition = "jsonb", insertable = false, updatable = true)
    private String metadata;


    @Column(columnDefinition = "vector(1024)", insertable = false, updatable = true)
    private float[] embedding;

    // embedding column will be handled via native SQL (pgvector)
}
