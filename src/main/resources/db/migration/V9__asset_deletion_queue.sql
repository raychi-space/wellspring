CREATE TABLE asset_deletion_queue (
  storage_key VARCHAR(100) PRIMARY KEY,
  created_at TIMESTAMP(6) NOT NULL
);
