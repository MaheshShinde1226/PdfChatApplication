-- Enable pgvector extension
CREATE EXTENSION IF NOT EXISTS vector;

-- Create table
CREATE TABLE IF NOT EXISTS document_chunks (
    id BIGSERIAL PRIMARY KEY,
    source_filename TEXT,
    chunk_index INT,
    content TEXT,
    metadata JSONB,
    embedding vector(1024)   -- must match your converter & model embedding dimension
);

-- Create IVFFlat index for cosine similarity search (requires ANALYZE before use)
CREATE INDEX IF NOT EXISTS idx_document_chunks_embedding
ON document_chunks
USING ivfflat (embedding vector_cosine_ops)
WITH (lists = 100);
