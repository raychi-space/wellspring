CREATE TABLE analytics_settings (
  id INT PRIMARY KEY,
  collection_enabled BOOLEAN NOT NULL,
  version BIGINT NOT NULL,
  updated_at TIMESTAMP(6) NOT NULL
);

-- Preserve the deployment's existing opt-in: this alone never enables analytics.
INSERT INTO analytics_settings (id, collection_enabled, version, updated_at)
VALUES (1, TRUE, 0, CURRENT_TIMESTAMP);
