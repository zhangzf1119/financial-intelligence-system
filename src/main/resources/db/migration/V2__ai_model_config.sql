CREATE TABLE ai_model_config (
  id BIGSERIAL PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  provider VARCHAR(50) NOT NULL DEFAULT 'OPENAI_COMPATIBLE',
  base_url VARCHAR(500) NOT NULL,
  api_key_encrypted TEXT,
  chat_model VARCHAR(160) NOT NULL,
  embedding_model VARCHAR(160) NOT NULL,
  embedding_dimensions INT NOT NULL DEFAULT 1536,
  temperature NUMERIC(3,2) NOT NULL DEFAULT 0.70,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO ai_model_config(name,provider,base_url,chat_model,embedding_model,embedding_dimensions,temperature,enabled)
VALUES ('默认模型服务','OPENAI_COMPATIBLE','https://api.openai.com/v1','gpt-4.1-mini','text-embedding-3-small',1536,0.70,TRUE);

INSERT INTO sys_menu(parent_id,name,path,icon,permission,type,sort_order)
VALUES (7,'模型设置','/ai/settings','settings','ai:settings','MENU',23);
INSERT INTO sys_role_menu(role_id,menu_id) SELECT 1,id FROM sys_menu WHERE permission='ai:settings';
