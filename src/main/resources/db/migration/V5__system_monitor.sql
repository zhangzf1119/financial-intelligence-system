INSERT INTO sys_menu(parent_id,name,path,icon,permission,type,sort_order)
VALUES (NULL,'系统监控','/monitor/system','Monitor','system:monitor:list','MENU',40);
INSERT INTO sys_role_menu(role_id,menu_id)
SELECT 1,id FROM sys_menu WHERE permission='system:monitor:list' ON CONFLICT DO NOTHING;
