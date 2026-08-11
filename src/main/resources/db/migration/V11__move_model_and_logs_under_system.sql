UPDATE sys_menu
SET parent_id=(SELECT id FROM sys_menu WHERE name='系统管理' AND parent_id IS NULL ORDER BY id LIMIT 1),
    sort_order=17,
    updated_at=now()
WHERE permission='ai:settings';

UPDATE sys_menu
SET parent_id=(SELECT id FROM sys_menu WHERE name='系统管理' AND parent_id IS NULL ORDER BY id LIMIT 1),
    sort_order=18,
    updated_at=now()
WHERE name='日志管理' AND permission='system:log:list' AND type='DIRECTORY';
