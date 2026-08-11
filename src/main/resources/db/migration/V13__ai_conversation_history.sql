CREATE TABLE ai_conversation (
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES sys_user(id) ON DELETE CASCADE,
  title VARCHAR(160) NOT NULL DEFAULT '新会话',
  model VARCHAR(160),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_ai_conversation_user_updated ON ai_conversation(user_id,updated_at DESC);

CREATE TABLE ai_conversation_message (
  id BIGSERIAL PRIMARY KEY,
  conversation_id BIGINT NOT NULL REFERENCES ai_conversation(id) ON DELETE CASCADE,
  role VARCHAR(16) NOT NULL,
  content TEXT NOT NULL DEFAULT '',
  model VARCHAR(160),
  prompt_tokens INTEGER,
  completion_tokens INTEGER,
  total_tokens INTEGER,
  status VARCHAR(16) NOT NULL DEFAULT 'SUCCESS',
  error_message VARCHAR(1000),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT ai_conversation_message_role CHECK (role IN ('user','assistant','system')),
  CONSTRAINT ai_conversation_message_status CHECK (status IN ('SUCCESS','FAILED'))
);

CREATE INDEX idx_ai_conversation_message_order ON ai_conversation_message(conversation_id,id);
