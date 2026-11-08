-- PostgreSQL baseline equivalent to the MySQL schema through V14.
-- The original MySQL migration files/checksums remain unchanged.
-- Text equality retains MySQL case/accent insensitivity except case-sensitive tags.
CREATE COLLATION raychi_ai_ci (provider = icu, locale = 'und-u-ks-level1', deterministic = false);

-- Schema/initial values from V1__articles_and_assets.sql
CREATE TABLE articles (
  id VARCHAR(36) COLLATE raychi_ai_ci PRIMARY KEY,
  slug VARCHAR(120) COLLATE raychi_ai_ci NOT NULL UNIQUE,
  status VARCHAR(12) COLLATE raychi_ai_ci NOT NULL,
  version BIGINT NOT NULL,
  draft_title VARCHAR(255) COLLATE raychi_ai_ci NOT NULL,
  draft_summary VARCHAR(600) COLLATE raychi_ai_ci NOT NULL,
  draft_body TEXT COLLATE raychi_ai_ci NOT NULL,
  draft_tags TEXT COLLATE raychi_ai_ci NOT NULL,
  draft_cover VARCHAR(1000) COLLATE raychi_ai_ci,
  public_title VARCHAR(255) COLLATE raychi_ai_ci,
  public_summary VARCHAR(600) COLLATE raychi_ai_ci,
  public_body TEXT COLLATE raychi_ai_ci,
  public_tags TEXT COLLATE raychi_ai_ci,
  public_cover VARCHAR(1000) COLLATE raychi_ai_ci,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  published_at TIMESTAMP(6) WITH TIME ZONE,
  public_updated_at TIMESTAMP(6) WITH TIME ZONE
);

CREATE INDEX idx_articles_public ON articles (status, published_at, id);
CREATE INDEX idx_articles_updated ON articles (updated_at, id);

CREATE TABLE assets (
  id VARCHAR(36) COLLATE raychi_ai_ci PRIMARY KEY,
  article_id VARCHAR(36) COLLATE raychi_ai_ci NOT NULL,
  storage_key VARCHAR(100) COLLATE raychi_ai_ci NOT NULL UNIQUE,
  media_type VARCHAR(30) COLLATE raychi_ai_ci NOT NULL,
  byte_size BIGINT NOT NULL,
  width_px INT NOT NULL,
  height_px INT NOT NULL,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  CONSTRAINT fk_assets_article FOREIGN KEY (article_id) REFERENCES articles(id)
);
CREATE INDEX idx_assets_article ON assets (article_id);

CREATE TABLE published_assets (
  article_id VARCHAR(36) COLLATE raychi_ai_ci NOT NULL,
  asset_id VARCHAR(36) COLLATE raychi_ai_ci NOT NULL,
  PRIMARY KEY (article_id, asset_id),
  CONSTRAINT fk_published_assets_article FOREIGN KEY (article_id) REFERENCES articles(id),
  CONSTRAINT fk_published_assets_asset FOREIGN KEY (asset_id) REFERENCES assets(id)
);

-- Schema/initial values from V2__content_and_site.sql
ALTER TABLE articles ADD COLUMN content_type VARCHAR(12) COLLATE raychi_ai_ci NOT NULL DEFAULT 'ARTICLE';
ALTER TABLE articles ADD COLUMN draft_category VARCHAR(80) COLLATE raychi_ai_ci;
ALTER TABLE articles ADD COLUMN public_category VARCHAR(80) COLLATE raychi_ai_ci;
UPDATE articles SET draft_category='未分类', public_category=CASE WHEN status='PUBLISHED' THEN '未分类' ELSE NULL END;
CREATE INDEX idx_content_public ON articles (status, content_type, published_at, id);

CREATE TABLE categories (
  name VARCHAR(80) COLLATE raychi_ai_ci PRIMARY KEY,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);
INSERT INTO categories (name, created_at) VALUES ('未分类', CURRENT_TIMESTAMP);

CREATE TABLE tags (
  name VARCHAR(40) COLLATE "C" PRIMARY KEY,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE TABLE site_settings (
  id INT PRIMARY KEY,
  value_json TEXT COLLATE raychi_ai_ci NOT NULL,
  version BIGINT NOT NULL,
  updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);
INSERT INTO site_settings (id, value_json, version, updated_at) VALUES
  (1, '{"siteName":"Raychi","intro":"一个正在生长的个人空间","avatarUrl":null,"contacts":[],"accounts":[],"navigation":[{"label":"首页","href":"/"},{"label":"长文","href":"/writing"},{"label":"帖子","href":"/posts"},{"label":"思考","href":"/thoughts"},{"label":"回顾","href":"/archive"},{"label":"更多","href":"/more"}],"homeSections":[{"id":"feed","visible":true},{"id":"writing","visible":true},{"id":"posts","visible":true},{"id":"thoughts","visible":true}]}', 0, CURRENT_TIMESTAMP);

-- Schema/initial values from V5__editable_homepage_and_social_accounts.sql
-- Seed presentation copy in the database so the public site does not rely on code fallbacks.
UPDATE site_settings
SET value_json = CONCAT(SUBSTRING(value_json COLLATE "C", 1, CHAR_LENGTH(value_json) - 1),
  ',"homepage":{"focus":"Build with AI Agents","projects":[{"name":"Lantern","description":"面向访客的个人空间，承载首页、文章、帖子与回顾。","status":"持续迭代","href":"https://github.com/raychi-space/lantern"},{"name":"Inkwell","description":"把想法写成内容，并管理草稿、发布与站点展示。","status":"持续迭代","href":"https://github.com/raychi-space/inkwell"},{"name":"Wellspring","description":"为写作和阅读提供内容接口与发布快照。","status":"持续迭代","href":"https://github.com/raychi-space/wellspring"}],"recentSections":[{"id":"featured","visible":true},{"id":"posts","visible":true},{"id":"writing","visible":true}],"bottomSections":[{"id":"projects","visible":true},{"id":"stats","visible":true}]}', '}'),
    version = version + 1, updated_at = CURRENT_TIMESTAMP
WHERE id = 1 AND value_json COLLATE "C" NOT LIKE '%"homepage"%';

UPDATE site_settings
SET value_json = CONCAT(SUBSTRING(value_json COLLATE "C", 1, CHAR_LENGTH(value_json) - 1),
  ',"projectIntro":"一些正在持续打磨的公开项目。","socialAccounts":[]', '}'),
    version = version + 1, updated_at = CURRENT_TIMESTAMP
WHERE id = 1;

-- Correct the old default label in stored data; presentation always reads the saved label.
UPDATE site_settings
SET value_json = REPLACE(value_json COLLATE "C", '{"label":"长文","href":"/writing"}',
    '{"label":"文章","href":"/writing"}'),
    version = version + 1, updated_at = CURRENT_TIMESTAMP
WHERE id = 1 AND value_json COLLATE "C" LIKE '%"label":"长文","href":"/writing"%';

-- Schema/initial values from V6__search_sync_state.sql
-- Retain desired DELETE states after content deletion; intentionally no FK to articles.
CREATE TABLE search_sync_state (
 document_id VARCHAR(160) COLLATE raychi_ai_ci PRIMARY KEY,
 version BIGINT NOT NULL,
 desired_action VARCHAR(8) COLLATE raychi_ai_ci NOT NULL,
 payload_json TEXT COLLATE raychi_ai_ci,
 status VARCHAR(8) COLLATE raychi_ai_ci NOT NULL,
 attempts INT NOT NULL DEFAULT 0,
 next_retry_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
 last_error VARCHAR(120) COLLATE raychi_ai_ci,
 updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_search_due ON search_sync_state(status,next_retry_at);

-- Schema/initial values from V7__publication_summaries.sql
-- One durable job for the latest publication. No credentials or duplicate document bodies.
CREATE TABLE publication_summaries (
  article_id VARCHAR(36) COLLATE raychi_ai_ci PRIMARY KEY,
  job_id VARCHAR(36) COLLATE raychi_ai_ci NOT NULL UNIQUE,
  assistant_id VARCHAR(80) COLLATE raychi_ai_ci,
  publication_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  status VARCHAR(12) COLLATE raychi_ai_ci NOT NULL,
  task_id VARCHAR(80) COLLATE raychi_ai_ci,
  started_at TIMESTAMP(6) WITH TIME ZONE,
  error_code VARCHAR(80) COLLATE raychi_ai_ci,
  CONSTRAINT fk_summary_article FOREIGN KEY (article_id) REFERENCES articles(id)
);
CREATE INDEX idx_summary_status ON publication_summaries(status);

CREATE TABLE public_content_tags (article_id VARCHAR(36) COLLATE raychi_ai_ci NOT NULL, name VARCHAR(40) COLLATE "C" NOT NULL, PRIMARY KEY (article_id,name), FOREIGN KEY (article_id) REFERENCES articles(id) ON DELETE CASCADE);
CREATE INDEX idx_public_content_tag ON public_content_tags(name,article_id);

-- Schema/initial values from V9__asset_deletion_queue.sql
CREATE TABLE asset_deletion_queue (
  storage_key VARCHAR(100) COLLATE raychi_ai_ci PRIMARY KEY,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

-- Schema/initial values from V10__admin_account.sql
CREATE TABLE admin_account (
    id INT PRIMARY KEY,
    username VARCHAR(100) COLLATE raychi_ai_ci NOT NULL,
    password_hash VARCHAR(60) COLLATE raychi_ai_ci NOT NULL,
    credential_version BIGINT NOT NULL
);

-- Schema/initial values from V11__content_revisions.sql
CREATE TABLE content_revisions (
    id VARCHAR(36) COLLATE raychi_ai_ci PRIMARY KEY,
    article_id VARCHAR(36) COLLATE raychi_ai_ci NOT NULL,
    article_version BIGINT NOT NULL,
    operation VARCHAR(16) COLLATE raychi_ai_ci NOT NULL,
    title VARCHAR(255) COLLATE raychi_ai_ci NOT NULL,
    summary VARCHAR(600) COLLATE raychi_ai_ci NOT NULL,
    body_markdown TEXT COLLATE raychi_ai_ci NOT NULL,
    tags_json TEXT COLLATE raychi_ai_ci NOT NULL,
    category VARCHAR(80) COLLATE raychi_ai_ci,
    cover_url VARCHAR(1000) COLLATE raychi_ai_ci,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_revision_article FOREIGN KEY (article_id) REFERENCES articles(id) ON DELETE CASCADE,
    CONSTRAINT uq_revision_version UNIQUE (article_id, article_version)
);

-- Schema/initial values from V12__content_trash.sql
ALTER TABLE articles ADD COLUMN trashed_at TIMESTAMP(6) WITH TIME ZONE;
CREATE INDEX idx_article_trash ON articles(status,trashed_at,id);

-- Schema/initial values from V13__reading_shelf.sql
CREATE TABLE reading_settings (
  id INT PRIMARY KEY,
  display_days INT NOT NULL,
  translation_assistant_id VARCHAR(80) COLLATE raychi_ai_ci,
  version BIGINT NOT NULL,
  updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

INSERT INTO reading_settings (id, display_days, translation_assistant_id, version, updated_at)
VALUES (1, 90, NULL, 0, CURRENT_TIMESTAMP);

CREATE TABLE reading_sources (
  id VARCHAR(80) COLLATE raychi_ai_ci PRIMARY KEY,
  name VARCHAR(120) COLLATE raychi_ai_ci NOT NULL,
  description VARCHAR(240) COLLATE raychi_ai_ci NOT NULL,
  site_url VARCHAR(1000) COLLATE raychi_ai_ci NOT NULL,
  listing_url VARCHAR(1000) COLLATE raychi_ai_ci NOT NULL,
  adapter VARCHAR(40) COLLATE raychi_ai_ci NOT NULL,
  enabled BOOLEAN NOT NULL,
  position INT NOT NULL,
  last_checked_at TIMESTAMP(6) WITH TIME ZONE,
  last_success_at TIMESTAMP(6) WITH TIME ZONE,
  last_error VARCHAR(80) COLLATE raychi_ai_ci,
  created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_reading_sources_order ON reading_sources (enabled, position, id);

INSERT INTO reading_sources
  (id, name, description, site_url, listing_url, adapter, enabled, position, created_at, updated_at)
VALUES
  ('openai-developers', 'OpenAI Developer Blog', '面向开发者的产品实践与工程经验',
   'https://developers.openai.com/blog', 'https://developers.openai.com/blog', 'OPENAI_DEVELOPERS', TRUE, 10,
   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
  ('anthropic-engineering', 'Anthropic Engineering', '可靠 AI 系统背后的工程设计与实践',
   'https://www.anthropic.com/engineering', 'https://www.anthropic.com/engineering', 'ANTHROPIC_ENGINEERING', TRUE, 20,
   CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

CREATE TABLE reading_articles (
  id VARCHAR(36) COLLATE raychi_ai_ci PRIMARY KEY,
  source_id VARCHAR(80) COLLATE raychi_ai_ci NOT NULL,
  external_url VARCHAR(1000) COLLATE raychi_ai_ci NOT NULL,
  external_url_hash VARCHAR(64) COLLATE raychi_ai_ci NOT NULL,
  original_title VARCHAR(500) COLLATE raychi_ai_ci NOT NULL,
  translated_title VARCHAR(500) COLLATE raychi_ai_ci,
  published_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  discovered_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
  title_hash VARCHAR(64) COLLATE raychi_ai_ci NOT NULL,
  translation_status VARCHAR(16) COLLATE raychi_ai_ci NOT NULL,
  translation_task_id VARCHAR(80) COLLATE raychi_ai_ci,
  translation_attempts INT NOT NULL,
  next_translation_at TIMESTAMP(6) WITH TIME ZONE,
  translation_revision INT NOT NULL,
  CONSTRAINT fk_reading_articles_source FOREIGN KEY (source_id) REFERENCES reading_sources(id) ON DELETE CASCADE,
  CONSTRAINT uq_reading_article_url_hash UNIQUE (external_url_hash)
);

CREATE INDEX idx_reading_articles_public ON reading_articles (source_id, published_at, id);
CREATE INDEX idx_reading_articles_translation ON reading_articles (translation_status, next_translation_at, discovered_at);

-- Schema/initial values from V14__analytics_settings.sql
CREATE TABLE analytics_settings (
  id INT PRIMARY KEY,
  collection_enabled BOOLEAN NOT NULL,
  version BIGINT NOT NULL,
  updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

-- Preserve the deployment's existing opt-in: this alone never enables analytics.
INSERT INTO analytics_settings (id, collection_enabled, version, updated_at)
VALUES (1, TRUE, 0, CURRENT_TIMESTAMP);
