CREATE TABLE document_page_images (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    knowledge_base_id VARCHAR(36) NOT NULL,
    knowledge_id VARCHAR(36) NOT NULL,
    source_hash TEXT NOT NULL,
    page_number INTEGER NOT NULL CHECK (page_number > 0),
    storage_key TEXT NOT NULL DEFAULT '',
    mime_type TEXT NOT NULL DEFAULT 'image/jpeg',
    width INTEGER NOT NULL DEFAULT 0,
    height INTEGER NOT NULL DEFAULT 0,
    size BIGINT NOT NULL DEFAULT 0,
    render_profile TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'pending',
    error_message TEXT NOT NULL DEFAULT '',
    task_token TEXT NOT NULL DEFAULT '',
    lease_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, knowledge_id, source_hash, page_number, render_profile)
);
CREATE INDEX idx_document_page_images_document ON document_page_images (tenant_id, knowledge_id, source_hash, page_number);
CREATE TABLE chunk_page_image_links (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    knowledge_base_id VARCHAR(36) NOT NULL,
    knowledge_id VARCHAR(36) NOT NULL,
    chunk_id VARCHAR(36) NOT NULL,
    content_revision INTEGER NOT NULL,
    source_hash TEXT NOT NULL,
    page_number INTEGER NOT NULL CHECK (page_number > 0),
    created_by VARCHAR(36) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (tenant_id, chunk_id, content_revision, source_hash, page_number)
);
CREATE INDEX idx_chunk_page_image_links_document ON chunk_page_image_links (tenant_id, knowledge_id);
