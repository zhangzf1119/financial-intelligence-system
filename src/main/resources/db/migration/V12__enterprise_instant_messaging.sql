CREATE TABLE sys_im_conversation (
  id BIGSERIAL PRIMARY KEY,
  type VARCHAR(16) NOT NULL,
  name VARCHAR(120),
  direct_key VARCHAR(80) UNIQUE,
  owner_id BIGINT REFERENCES sys_user(id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT sys_im_conversation_type CHECK (type IN ('DIRECT','GROUP'))
);

CREATE TABLE sys_im_member (
  conversation_id BIGINT NOT NULL REFERENCES sys_im_conversation(id) ON DELETE CASCADE,
  user_id BIGINT NOT NULL REFERENCES sys_user(id) ON DELETE CASCADE,
  member_role VARCHAR(16) NOT NULL DEFAULT 'MEMBER',
  last_read_message_id BIGINT NOT NULL DEFAULT 0,
  pinned BOOLEAN NOT NULL DEFAULT FALSE,
  muted BOOLEAN NOT NULL DEFAULT FALSE,
  joined_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (conversation_id,user_id),
  CONSTRAINT sys_im_member_role CHECK (member_role IN ('OWNER','ADMIN','MEMBER'))
);

CREATE TABLE sys_im_message (
  id BIGSERIAL PRIMARY KEY,
  conversation_id BIGINT NOT NULL REFERENCES sys_im_conversation(id) ON DELETE CASCADE,
  sender_id BIGINT REFERENCES sys_user(id) ON DELETE SET NULL,
  type VARCHAR(16) NOT NULL DEFAULT 'TEXT',
  content TEXT,
  file_id BIGINT REFERENCES sys_file(id) ON DELETE SET NULL,
  reply_to_id BIGINT REFERENCES sys_im_message(id) ON DELETE SET NULL,
  recalled_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT sys_im_message_type CHECK (type IN ('TEXT','IMAGE','FILE','SYSTEM'))
);

CREATE INDEX idx_im_member_user ON sys_im_member(user_id,conversation_id);
CREATE INDEX idx_im_message_conversation ON sys_im_message(conversation_id,id DESC);
CREATE INDEX idx_im_conversation_updated ON sys_im_conversation(updated_at DESC);

