-- Registered users and their access-control state (Google OAuth2 + approval workflow).
-- Emails are stored normalized (lower-cased) by the application, so the UNIQUE
-- constraint on `email` enforces case-insensitive uniqueness and already provides
-- the lookup index (no separate index needed). `role` is constrained to the two
-- values the authorization layer understands.
CREATE TABLE users (
    id            UUID PRIMARY KEY,
    email         VARCHAR UNIQUE NOT NULL,
    name          VARCHAR,
    picture_url   TEXT,
    role          VARCHAR NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'ADMIN')),
    is_approved   BOOLEAN NOT NULL DEFAULT FALSE,
    is_active     BOOLEAN NOT NULL DEFAULT TRUE,
    last_login_at TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
