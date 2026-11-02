CREATE TABLE IF NOT EXISTS user_accounts (
    id UUID PRIMARY KEY,
    email VARCHAR(320) NOT NULL UNIQUE,
    display_name VARCHAR(80) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS watch_spaces (
    id UUID PRIMARY KEY,
    title_id VARCHAR(200) NOT NULL,
    host_user_id UUID NOT NULL REFERENCES user_accounts(id),
    invite_code VARCHAR(32) NOT NULL UNIQUE,
    max_participants INTEGER NOT NULL CHECK (max_participants BETWEEN 2 AND 50),
    ai_verbosity VARCHAR(16) NOT NULL,
    voting_enabled BOOLEAN NOT NULL,
    status VARCHAR(16) NOT NULL,
    status_before_lock VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS watch_space_participants (
    watch_space_id UUID NOT NULL REFERENCES watch_spaces(id) ON DELETE CASCADE,
    user_id UUID NOT NULL REFERENCES user_accounts(id) ON DELETE CASCADE,
    joined_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (watch_space_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_watch_space_participants_user
    ON watch_space_participants (user_id);
