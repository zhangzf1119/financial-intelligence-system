ALTER TABLE ai_model_config RENAME COLUMN base_url TO chat_api_url;
ALTER TABLE ai_model_config RENAME COLUMN api_key_encrypted TO chat_api_key_encrypted;
ALTER TABLE ai_model_config ADD COLUMN vector_base_url VARCHAR(500) NOT NULL DEFAULT 'https://api.openai.com';
ALTER TABLE ai_model_config ADD COLUMN vector_api_path VARCHAR(255) NOT NULL DEFAULT '/v1/embeddings';
ALTER TABLE ai_model_config ADD COLUMN vector_api_key_encrypted TEXT;
ALTER TABLE ai_model_config ADD COLUMN vector_concurrency INT NOT NULL DEFAULT 3;
ALTER TABLE ai_model_config ADD COLUMN max_tokens INT NOT NULL DEFAULT 4096;
UPDATE ai_model_config SET chat_api_url=chat_api_url||'/chat/completions' WHERE chat_api_url NOT LIKE '%/chat/completions';

DROP INDEX IF EXISTS ai_knowledge_embedding_idx;
ALTER TABLE ai_knowledge ALTER COLUMN embedding TYPE vector(1024);
CREATE INDEX ai_knowledge_embedding_idx ON ai_knowledge USING hnsw (embedding vector_cosine_ops);
