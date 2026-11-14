CREATE TABLE profile_avatar (
    id INTEGER PRIMARY KEY,
    image_id VARCHAR(36) NOT NULL,
    image_bytes BYTEA NOT NULL,
    CONSTRAINT singleton_profile_avatar CHECK (id=1)
);
