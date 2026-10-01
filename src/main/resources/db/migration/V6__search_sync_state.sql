-- Retain desired DELETE states after content deletion; intentionally no FK to articles.
CREATE TABLE search_sync_state (
 document_id VARCHAR(160) PRIMARY KEY,
 version BIGINT NOT NULL,
 desired_action VARCHAR(8) NOT NULL,
 payload_json LONGTEXT,
 status VARCHAR(8) NOT NULL,
 attempts INT NOT NULL DEFAULT 0,
 next_retry_at TIMESTAMP(6) NOT NULL,
 last_error VARCHAR(120),
 updated_at TIMESTAMP(6) NOT NULL
);
CREATE INDEX idx_search_due ON search_sync_state(status,next_retry_at);
