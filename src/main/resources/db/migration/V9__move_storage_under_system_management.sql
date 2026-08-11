UPDATE sys_menu
SET parent_id=(SELECT id FROM sys_menu WHERE name='系统管理' AND parent_id IS NULL ORDER BY id LIMIT 1),
    sort_order=16,
    updated_at=now()
WHERE permission='storage:settings';

DELETE FROM sys_role_menu WHERE menu_id IN (SELECT id FROM sys_menu WHERE permission='system:settings');
DELETE FROM sys_menu WHERE permission='system:settings';
