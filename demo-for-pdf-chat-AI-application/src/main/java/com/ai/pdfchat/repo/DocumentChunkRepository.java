package com.ai.pdfchat.repo;

import com.ai.pdfchat.model.DocumentChunk;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DocumentChunkRepository extends CrudRepository<DocumentChunk, Long> {
}
