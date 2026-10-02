-- One durable job for the latest publication. No credentials or duplicate document bodies.
CREATE TABLE publication_summaries (
  article_id VARCHAR(36) PRIMARY KEY,
  job_id VARCHAR(36) NOT NULL UNIQUE,
  assistant_id VARCHAR(80),
  publication_at TIMESTAMP(6) NOT NULL,
  status VARCHAR(12) NOT NULL,
  task_id VARCHAR(80),
  started_at TIMESTAMP(6),
  error_code VARCHAR(80),
  CONSTRAINT fk_summary_article FOREIGN KEY (article_id) REFERENCES articles(id)
);
CREATE INDEX idx_summary_status ON publication_summaries(status);
