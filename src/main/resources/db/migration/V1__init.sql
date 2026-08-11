CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE sys_department (
  id BIGSERIAL PRIMARY KEY, parent_id BIGINT, name VARCHAR(100) NOT NULL, leader VARCHAR(100), phone VARCHAR(30),
  sort_order INT NOT NULL DEFAULT 0, status BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE sys_role (
  id BIGSERIAL PRIMARY KEY, name VARCHAR(80) NOT NULL, code VARCHAR(80) NOT NULL UNIQUE, description VARCHAR(255), status BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE sys_menu (
  id BIGSERIAL PRIMARY KEY, parent_id BIGINT, name VARCHAR(80) NOT NULL, path VARCHAR(160), icon VARCHAR(60), permission VARCHAR(120),
  type VARCHAR(20) NOT NULL DEFAULT 'MENU', sort_order INT NOT NULL DEFAULT 0, visible BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE sys_user (
  id BIGSERIAL PRIMARY KEY, username VARCHAR(80) NOT NULL UNIQUE, password VARCHAR(255) NOT NULL, nickname VARCHAR(100) NOT NULL,
  email VARCHAR(160), phone VARCHAR(30), department_id BIGINT REFERENCES sys_department(id) ON DELETE SET NULL,
  status BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE sys_user_role (user_id BIGINT REFERENCES sys_user(id) ON DELETE CASCADE, role_id BIGINT REFERENCES sys_role(id) ON DELETE CASCADE, PRIMARY KEY(user_id, role_id));
CREATE TABLE sys_role_menu (role_id BIGINT REFERENCES sys_role(id) ON DELETE CASCADE, menu_id BIGINT REFERENCES sys_menu(id) ON DELETE CASCADE, PRIMARY KEY(role_id, menu_id));
CREATE TABLE ai_knowledge (
  id BIGSERIAL PRIMARY KEY, title VARCHAR(255) NOT NULL, content TEXT NOT NULL, embedding vector(1536), created_by BIGINT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ai_knowledge_embedding_idx ON ai_knowledge USING hnsw (embedding vector_cosine_ops);

INSERT INTO sys_department(name, leader, sort_order) VALUES ('总部', '系统管理员', 1), ('财务部', '财务负责人', 2), ('技术部', '技术负责人', 3);
INSERT INTO sys_role(name, code, description) VALUES ('超级管理员', 'SUPER_ADMIN', '拥有全部权限'), ('普通用户', 'USER', '基础访问权限');
INSERT INTO sys_menu(parent_id,name,path,icon,permission,type,sort_order) VALUES
 (NULL,'工作台','/dashboard','dashboard','dashboard:view','MENU',1),
 (NULL,'系统管理',NULL,'settings',NULL,'DIRECTORY',10),
 (2,'用户管理','/system/users','users','system:user:list','MENU',11),
 (2,'角色管理','/system/roles','shield','system:role:list','MENU',12),
 (2,'菜单管理','/system/menus','menu','system:menu:list','MENU',13),
 (2,'部门管理','/system/departments','building','system:department:list','MENU',14),
 (NULL,'AI 智能中心',NULL,'sparkles',NULL,'DIRECTORY',20),
 (7,'模型对话','/ai/chat','message','ai:chat','MENU',21),
 (7,'知识库','/ai/knowledge','database','ai:knowledge:list','MENU',22);
-- password: Admin@123
INSERT INTO sys_user(username,password,nickname,email,department_id) VALUES ('admin','$2y$10$cKctjjwV4w/leC1/mQgmwObRz2TMltEk8TP.dRQaSpLZc.6JX0QOC','系统管理员','admin@example.com',1);
INSERT INTO sys_user_role VALUES (1,1);
INSERT INTO sys_role_menu SELECT 1,id FROM sys_menu;
