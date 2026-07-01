CREATE TABLE users (
    id            UUID PRIMARY KEY,
    email         VARCHAR UNIQUE NOT NULL,
    name          VARCHAR,
    picture_url   TEXT,
    role          VARCHAR NOT NULL DEFAULT 'USER',
    is_approved   BOOLEAN NOT NULL DEFAULT FALSE,
    is_active     BOOLEAN NOT NULL DEFAULT TRUE,
    last_login_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_users_email ON users(email);
