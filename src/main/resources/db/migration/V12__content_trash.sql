ALTER TABLE articles ADD COLUMN trashed_at TIMESTAMP(6);
CREATE INDEX idx_article_trash ON articles(status,trashed_at,id);
