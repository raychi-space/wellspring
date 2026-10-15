CREATE TABLE reading_settings (
  id INT PRIMARY KEY,
  display_days INT NOT NULL,
  translation_assistant_id VARCHAR(80),
  version BIGINT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL
);

INSERT INTO reading_settings (id, display_days, translation_assistant_id, version, updated_at)
VALUES (1, 90, NULL, 0, CURRENT_TIMESTAMP);

CREATE TABLE reading_sources (
  id VARCHAR(80) PRIMARY KEY,
  name VARCHAR(120) NOT NULL,
  description VARCHAR(240) NOT NULL,
  site_url VARCHAR(1000) NOT NULL,
  listing_url VARCHAR(1000) NOT NULL,
  adapter VARCHAR(40) NOT NULL,
  enabled BOOLEAN NOT NULL,
  position INT NOT NULL,
  last_checked_at TIMESTAMP(6),
  last_success_at TIMESTAMP(6),
  last_error VARCHAR(80),
  created_at TIMESTAMP(6) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL
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
  id VARCHAR(36) PRIMARY KEY,
  source_id VARCHAR(80) NOT NULL,
  external_url VARCHAR(1000) NOT NULL,
  external_url_hash VARCHAR(64) NOT NULL,
  original_title VARCHAR(500) NOT NULL,
  translated_title VARCHAR(500),
  published_at TIMESTAMP(6) NOT NULL,
  discovered_at TIMESTAMP(6) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  title_hash VARCHAR(64) NOT NULL,
  translation_status VARCHAR(16) NOT NULL,
  translation_task_id VARCHAR(80),
  translation_attempts INT NOT NULL,
  next_translation_at TIMESTAMP(6),
  translation_revision INT NOT NULL,
  CONSTRAINT fk_reading_articles_source FOREIGN KEY (source_id) REFERENCES reading_sources(id) ON DELETE CASCADE,
  CONSTRAINT uq_reading_article_url_hash UNIQUE (external_url_hash)
);

CREATE INDEX idx_reading_articles_public ON reading_articles (source_id, published_at, id);
CREATE INDEX idx_reading_articles_translation ON reading_articles (translation_status, next_translation_at, discovered_at);
