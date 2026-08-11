INSERT INTO sys_menu(parent_id,name,path,icon,permission,type,sort_order,visible)
SELECT NULL,'系统设置',NULL,'Setting','system:settings','DIRECTORY',45,TRUE
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE permission='system:settings');

UPDATE sys_menu
SET parent_id=(SELECT id FROM sys_menu WHERE permission='system:settings' ORDER BY id LIMIT 1),
    sort_order=46,
    updated_at=now()
WHERE permission='storage:settings';

INSERT INTO sys_role_menu(role_id,menu_id)
SELECT 1,id FROM sys_menu WHERE permission='system:settings'
ON CONFLICT DO NOTHING;
