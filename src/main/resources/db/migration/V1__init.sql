CREATE TABLE users (
    id UUID PRIMARY KEY,
    email TEXT NOT NULL UNIQUE,
    password_hash TEXT,
    name TEXT,
    avatar_url TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE oauth_accounts (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    provider TEXT NOT NULL,
    provider_id TEXT NOT NULL,
    access_token TEXT,
    refresh_token TEXT,
    expires_at TIMESTAMPTZ,
    UNIQUE (provider, provider_id)
);

CREATE TABLE channels (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type TEXT NOT NULL,
    name TEXT,
    config JSONB,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE repos (
    id UUID PRIMARY KEY,
    owner TEXT NOT NULL,
    repo TEXT NOT NULL,
    full_name TEXT NOT NULL UNIQUE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (owner, repo)
);

CREATE TABLE subscriptions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    repo_id UUID NOT NULL REFERENCES repos (id) ON DELETE CASCADE,
    channel_id UUID NOT NULL REFERENCES channels (id) ON DELETE CASCADE,
    event_type TEXT NOT NULL,
    filters JSONB,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT subscriptions_event_type_check CHECK (event_type IN ('issue.opened', 'pr.opened')),
    UNIQUE (user_id, repo_id, channel_id, event_type)
);

CREATE TABLE repo_poll_state (
    owner TEXT NOT NULL,
    repo TEXT NOT NULL,
    last_issue_number INTEGER,
    last_pr_number INTEGER,
    last_polled_at TIMESTAMPTZ,
    next_poll_at TIMESTAMPTZ,
    PRIMARY KEY (owner, repo)
);

CREATE INDEX idx_repo_poll_state_next_poll_at ON repo_poll_state (next_poll_at);
CREATE INDEX idx_subscriptions_repo_event ON subscriptions (repo_id, event_type) WHERE is_active;
CREATE INDEX idx_repos_owner_repo ON repos (owner, repo);
CREATE INDEX idx_channels_user ON channels (user_id);
