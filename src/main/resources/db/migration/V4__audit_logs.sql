CREATE TABLE sys_login_log (
  id BIGSERIAL PRIMARY KEY, username VARCHAR(80), ip_address VARCHAR(64), user_agent VARCHAR(500),
  status BOOLEAN NOT NULL, message VARCHAR(300), login_time TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_login_log_time ON sys_login_log(login_time DESC);
CREATE INDEX idx_login_log_user ON sys_login_log(username);

CREATE TABLE sys_operation_log (
  id BIGSERIAL PRIMARY KEY, username VARCHAR(80), module VARCHAR(80), operation VARCHAR(30),
  method VARCHAR(10), request_uri VARCHAR(500), ip_address VARCHAR(64), status_code INTEGER,
  success BOOLEAN NOT NULL, duration_ms BIGINT, error_message VARCHAR(1000), operation_time TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_operation_log_time ON sys_operation_log(operation_time DESC);
CREATE INDEX idx_operation_log_user ON sys_operation_log(username);

CREATE TABLE ai_chat_log (
  id BIGSERIAL PRIMARY KEY, username VARCHAR(80), model VARCHAR(160), request_content TEXT,
  response_content TEXT, prompt_tokens INTEGER DEFAULT 0, completion_tokens INTEGER DEFAULT 0,
  total_tokens INTEGER DEFAULT 0, duration_ms BIGINT, status VARCHAR(20) NOT NULL,
  error_message VARCHAR(1000), ip_address VARCHAR(64), created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_chat_log_time ON ai_chat_log(created_at DESC);
CREATE INDEX idx_chat_log_user ON ai_chat_log(username);

INSERT INTO sys_menu(parent_id,name,path,icon,permission,type,sort_order)
VALUES (NULL,'日志管理',NULL,'Document','system:log:list','DIRECTORY',30);
INSERT INTO sys_menu(parent_id,name,path,icon,permission,type,sort_order)
SELECT id,'登录日志','/logs/login','UserFilled','system:log:list','MENU',31 FROM sys_menu WHERE permission='system:log:list' AND parent_id IS NULL;
INSERT INTO sys_menu(parent_id,name,path,icon,permission,type,sort_order)
SELECT id,'操作日志','/logs/operation','Tickets','system:log:list','MENU',32 FROM sys_menu WHERE permission='system:log:list' AND parent_id IS NULL;
INSERT INTO sys_menu(parent_id,name,path,icon,permission,type,sort_order)
SELECT id,'模型对话日志','/logs/chat','ChatLineSquare','system:log:list','MENU',33 FROM sys_menu WHERE permission='system:log:list' AND parent_id IS NULL;
INSERT INTO sys_role_menu(role_id,menu_id) SELECT 1,id FROM sys_menu WHERE permission='system:log:list' ON CONFLICT DO NOTHING;
