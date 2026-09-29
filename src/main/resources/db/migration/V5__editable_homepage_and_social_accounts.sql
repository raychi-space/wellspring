-- Seed presentation copy in the database so the public site does not rely on code fallbacks.
UPDATE site_settings
SET value_json = CONCAT(SUBSTRING(value_json, 1, CHAR_LENGTH(value_json) - 1),
  ',"homepage":{"focus":"Build with AI Agents","projects":[{"name":"Lantern","description":"面向访客的个人空间，承载首页、文章、帖子与回顾。","status":"持续迭代","href":"https://github.com/raychi-space/lantern"},{"name":"Inkwell","description":"把想法写成内容，并管理草稿、发布与站点展示。","status":"持续迭代","href":"https://github.com/raychi-space/inkwell"},{"name":"Wellspring","description":"为写作和阅读提供内容接口与发布快照。","status":"持续迭代","href":"https://github.com/raychi-space/wellspring"}],"recentSections":[{"id":"featured","visible":true},{"id":"posts","visible":true},{"id":"writing","visible":true}],"bottomSections":[{"id":"projects","visible":true},{"id":"stats","visible":true}]}', '}'),
    version = version + 1, updated_at = CURRENT_TIMESTAMP
WHERE id = 1 AND value_json NOT LIKE '%"homepage"%';

UPDATE site_settings
SET value_json = CONCAT(SUBSTRING(value_json, 1, CHAR_LENGTH(value_json) - 1),
  ',"projectIntro":"一些正在持续打磨的公开项目。","socialAccounts":[]', '}'),
    version = version + 1, updated_at = CURRENT_TIMESTAMP
WHERE id = 1;

-- Correct the old default label in stored data; presentation always reads the saved label.
UPDATE site_settings
SET value_json = REPLACE(value_json, '{"label":"长文","href":"/writing"}',
    '{"label":"文章","href":"/writing"}'),
    version = version + 1, updated_at = CURRENT_TIMESTAMP
WHERE id = 1 AND value_json LIKE '%"label":"长文","href":"/writing"%';
