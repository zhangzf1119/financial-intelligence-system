CREATE TABLE knowledge_base (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(120) NOT NULL,
  description VARCHAR(500),
  permission_type VARCHAR(20) NOT NULL DEFAULT 'PRIVATE',
  created_by BIGINT REFERENCES sys_user(id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT knowledge_base_permission_check CHECK (permission_type IN ('PRIVATE','DEPARTMENT','PUBLIC'))
);

CREATE INDEX idx_knowledge_base_created_by ON knowledge_base(created_by);

CREATE TABLE knowledge_document (
  id BIGSERIAL PRIMARY KEY,
  knowledge_base_id BIGINT NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
  file_id BIGINT NOT NULL REFERENCES sys_file(id) ON DELETE CASCADE,
  original_name VARCHAR(255) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  parser_type VARCHAR(30),
  error_message VARCHAR(1000),
  chunk_total INT NOT NULL DEFAULT 0,
  parent_chunk_total INT NOT NULL DEFAULT 0,
  child_chunk_total INT NOT NULL DEFAULT 0,
  created_by BIGINT REFERENCES sys_user(id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT knowledge_document_status_check CHECK (status IN ('PENDING','PROCESSING','COMPLETED','FAILED')),
  CONSTRAINT knowledge_document_file_unique UNIQUE (knowledge_base_id,file_id)
);

CREATE INDEX idx_knowledge_document_base_status ON knowledge_document(knowledge_base_id,status,updated_at DESC);

CREATE TABLE knowledge_chunk (
  id BIGSERIAL PRIMARY KEY,
  document_id BIGINT NOT NULL REFERENCES knowledge_document(id) ON DELETE CASCADE,
  parent_chunk_id BIGINT REFERENCES knowledge_chunk(id) ON DELETE CASCADE,
  chunk_type VARCHAR(12) NOT NULL,
  chunk_index INT NOT NULL,
  page_number INT,
  content TEXT NOT NULL,
  token_count INT NOT NULL DEFAULT 0,
  embedding vector(1024),
  search_vector TSVECTOR GENERATED ALWAYS AS (to_tsvector('simple', coalesce(content,''))) STORED,
  metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT knowledge_chunk_type_check CHECK (chunk_type IN ('PARENT','CHILD')),
  CONSTRAINT knowledge_chunk_index_check CHECK (chunk_index >= 0)
);

CREATE INDEX idx_knowledge_chunk_document_order ON knowledge_chunk(document_id,chunk_type,chunk_index);
CREATE INDEX idx_knowledge_chunk_parent ON knowledge_chunk(parent_chunk_id);
CREATE INDEX idx_knowledge_chunk_search_vector ON knowledge_chunk USING GIN(search_vector);
CREATE INDEX idx_knowledge_chunk_embedding_hnsw ON knowledge_chunk USING HNSW (embedding vector_cosine_ops) WHERE embedding IS NOT NULL;
