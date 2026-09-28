ALTER TABLE articles ADD COLUMN content_type VARCHAR(12) NOT NULL DEFAULT 'ARTICLE';
ALTER TABLE articles ADD COLUMN draft_category VARCHAR(80);
ALTER TABLE articles ADD COLUMN public_category VARCHAR(80);
UPDATE articles SET draft_category='未分类', public_category=CASE WHEN status='PUBLISHED' THEN '未分类' ELSE NULL END;
CREATE INDEX idx_content_public ON articles (status, content_type, published_at, id);

CREATE TABLE categories (
  name VARCHAR(80) PRIMARY KEY,
  created_at TIMESTAMP(6) NOT NULL
);
INSERT INTO categories (name, created_at) VALUES ('未分类', CURRENT_TIMESTAMP);

CREATE TABLE tags (
  name VARCHAR(40) PRIMARY KEY,
  created_at TIMESTAMP(6) NOT NULL
);

CREATE TABLE site_settings (
  id INT PRIMARY KEY,
  value_json LONGTEXT NOT NULL,
  version BIGINT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL
);
INSERT INTO site_settings (id, value_json, version, updated_at) VALUES
  (1, '{"siteName":"Raychi","intro":"一个正在生长的个人空间","avatarUrl":null,"contacts":[],"accounts":[],"navigation":[{"label":"首页","href":"/"},{"label":"长文","href":"/writing"},{"label":"帖子","href":"/posts"},{"label":"思考","href":"/thoughts"},{"label":"回顾","href":"/archive"},{"label":"更多","href":"/more"}],"homeSections":[{"id":"feed","visible":true},{"id":"writing","visible":true},{"id":"posts","visible":true},{"id":"thoughts","visible":true}]}', 0, CURRENT_TIMESTAMP);
