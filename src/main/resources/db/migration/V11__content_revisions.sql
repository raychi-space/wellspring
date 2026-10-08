CREATE TABLE content_revisions (
    id CHAR(36) PRIMARY KEY,
    article_id CHAR(36) NOT NULL,
    article_version BIGINT NOT NULL,
    operation VARCHAR(16) NOT NULL,
    title VARCHAR(255) NOT NULL,
    summary VARCHAR(600) NOT NULL,
    body_markdown LONGTEXT NOT NULL,
    tags_json LONGTEXT NOT NULL,
    category VARCHAR(80),
    cover_url VARCHAR(1000),
    created_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_revision_article FOREIGN KEY (article_id) REFERENCES articles(id) ON DELETE CASCADE,
    CONSTRAINT uq_revision_version UNIQUE (article_id, article_version)
);
