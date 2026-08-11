UPDATE sys_menu
SET parent_id = (
  SELECT id FROM sys_menu
  WHERE name = '系统管理' AND parent_id IS NULL
  ORDER BY id LIMIT 1
), sort_order = 15, updated_at = now()
WHERE permission = 'system:monitor:list';
