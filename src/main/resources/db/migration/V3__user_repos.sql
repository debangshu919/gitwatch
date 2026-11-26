-- Per-user watch of a shared repos row. owner/repo stay on repos.
CREATE TABLE user_repos (
    user_id UUID NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    repo_id UUID NOT NULL REFERENCES repos (id) ON DELETE CASCADE,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, repo_id)
);

CREATE INDEX idx_user_repos_repo ON user_repos (repo_id);
