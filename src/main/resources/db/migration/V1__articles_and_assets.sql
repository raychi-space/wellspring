CREATE TABLE articles (
  id VARCHAR(36) PRIMARY KEY,
  slug VARCHAR(120) NOT NULL UNIQUE,
  status VARCHAR(12) NOT NULL,
  version BIGINT NOT NULL,
  draft_title VARCHAR(255) NOT NULL,
  draft_summary VARCHAR(600) NOT NULL,
  draft_body LONGTEXT NOT NULL,
  draft_tags TEXT NOT NULL,
  draft_cover VARCHAR(1000),
  public_title VARCHAR(255),
  public_summary VARCHAR(600),
  public_body LONGTEXT,
  public_tags TEXT,
  public_cover VARCHAR(1000),
  created_at TIMESTAMP(6) NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL,
  published_at TIMESTAMP(6),
  public_updated_at TIMESTAMP(6)
);

CREATE INDEX idx_articles_public ON articles (status, published_at, id);
CREATE INDEX idx_articles_updated ON articles (updated_at, id);

CREATE TABLE assets (
  id VARCHAR(36) PRIMARY KEY,
  article_id VARCHAR(36) NOT NULL,
  storage_key VARCHAR(100) NOT NULL UNIQUE,
  media_type VARCHAR(30) NOT NULL,
  byte_size BIGINT NOT NULL,
  width_px INT NOT NULL,
  height_px INT NOT NULL,
  created_at TIMESTAMP(6) NOT NULL,
  CONSTRAINT fk_assets_article FOREIGN KEY (article_id) REFERENCES articles(id)
);
CREATE INDEX idx_assets_article ON assets (article_id);

CREATE TABLE published_assets (
  article_id VARCHAR(36) NOT NULL,
  asset_id VARCHAR(36) NOT NULL,
  PRIMARY KEY (article_id, asset_id),
  CONSTRAINT fk_published_assets_article FOREIGN KEY (article_id) REFERENCES articles(id),
  CONSTRAINT fk_published_assets_asset FOREIGN KEY (asset_id) REFERENCES assets(id)
);
