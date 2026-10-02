CREATE TABLE admin_account (
    id INT PRIMARY KEY,
    username VARCHAR(100) NOT NULL,
    password_hash VARCHAR(60) NOT NULL,
    credential_version BIGINT NOT NULL
);
