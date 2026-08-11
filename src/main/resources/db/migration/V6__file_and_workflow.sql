CREATE TABLE sys_storage_config (
  id BIGSERIAL PRIMARY KEY, storage_type VARCHAR(20) NOT NULL DEFAULT 'LOCAL',
  local_path VARCHAR(500) NOT NULL DEFAULT 'data/uploads', minio_endpoint VARCHAR(500),
  minio_access_key VARCHAR(500), minio_secret_key_encrypted TEXT, minio_bucket VARCHAR(100),
  minio_region VARCHAR(100), updated_by BIGINT REFERENCES sys_user(id), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT storage_type_check CHECK(storage_type IN ('LOCAL','MINIO'))
);
INSERT INTO sys_storage_config(storage_type,local_path) VALUES('LOCAL','data/uploads');

CREATE TABLE sys_file (
  id BIGSERIAL PRIMARY KEY, original_name VARCHAR(500) NOT NULL, object_key VARCHAR(800) NOT NULL,
  storage_type VARCHAR(20) NOT NULL, bucket VARCHAR(100), content_type VARCHAR(200), size_bytes BIGINT NOT NULL,
  uploader_id BIGINT REFERENCES sys_user(id), status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_sys_file_created ON sys_file(created_at DESC);

CREATE TABLE wf_definition (
  id BIGSERIAL PRIMARY KEY, process_key VARCHAR(100) NOT NULL UNIQUE, name VARCHAR(160) NOT NULL,
  description VARCHAR(500), nodes JSONB NOT NULL DEFAULT '[]', enabled BOOLEAN NOT NULL DEFAULT TRUE,
  created_by BIGINT REFERENCES sys_user(id), created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE wf_instance (
  id BIGSERIAL PRIMARY KEY, definition_id BIGINT NOT NULL REFERENCES wf_definition(id), business_title VARCHAR(300) NOT NULL,
  business_data JSONB NOT NULL DEFAULT '{}', starter_username VARCHAR(80) NOT NULL, current_node INTEGER NOT NULL DEFAULT 0,
  status VARCHAR(30) NOT NULL DEFAULT 'RUNNING', started_at TIMESTAMPTZ NOT NULL DEFAULT now(), finished_at TIMESTAMPTZ
);
CREATE TABLE wf_task (
  id BIGSERIAL PRIMARY KEY, instance_id BIGINT NOT NULL REFERENCES wf_instance(id) ON DELETE CASCADE,
  node_index INTEGER NOT NULL, node_name VARCHAR(160) NOT NULL, assignee_username VARCHAR(80) NOT NULL,
  status VARCHAR(30) NOT NULL DEFAULT 'PENDING', comment VARCHAR(1000), created_at TIMESTAMPTZ NOT NULL DEFAULT now(), handled_at TIMESTAMPTZ
);
CREATE INDEX idx_wf_task_assignee ON wf_task(assignee_username,status);

INSERT INTO sys_menu(parent_id,name,path,icon,permission,type,sort_order) VALUES(NULL,'文件中心',NULL,'FolderOpened','file:list','DIRECTORY',50);
INSERT INTO sys_menu(parent_id,name,path,icon,permission,type,sort_order) SELECT id,'文件管理','/files','Files','file:list','MENU',51 FROM sys_menu WHERE permission='file:list' AND parent_id IS NULL;
INSERT INTO sys_menu(parent_id,name,path,icon,permission,type,sort_order) SELECT id,'文件设置','/settings/storage','Setting','storage:settings','MENU',52 FROM sys_menu WHERE permission='file:list' AND parent_id IS NULL;
INSERT INTO sys_menu(parent_id,name,path,icon,permission,type,sort_order) VALUES(NULL,'工作流','/workflow','Connection','workflow:list','DIRECTORY',60);
INSERT INTO sys_menu(parent_id,name,path,icon,permission,type,sort_order) SELECT id,'流程定义','/workflow/definitions','Operation','workflow:list','MENU',61 FROM sys_menu WHERE permission='workflow:list' AND parent_id IS NULL;
INSERT INTO sys_menu(parent_id,name,path,icon,permission,type,sort_order) SELECT id,'流程实例','/workflow/instances','List','workflow:list','MENU',62 FROM sys_menu WHERE permission='workflow:list' AND parent_id IS NULL;
INSERT INTO sys_menu(parent_id,name,path,icon,permission,type,sort_order) SELECT id,'我的待办','/workflow/tasks','Finished','workflow:task','MENU',63 FROM sys_menu WHERE permission='workflow:list' AND parent_id IS NULL;
INSERT INTO sys_role_menu(role_id,menu_id) SELECT 1,id FROM sys_menu WHERE permission IN ('file:list','storage:settings','workflow:list','workflow:task') ON CONFLICT DO NOTHING;
