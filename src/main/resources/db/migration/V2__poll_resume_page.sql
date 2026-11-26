ALTER TABLE repo_poll_state
    ADD COLUMN issue_page INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN pr_page INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN issue_high_water INTEGER,
    ADD COLUMN pr_high_water INTEGER;

ALTER TABLE repo_poll_state
    ADD CONSTRAINT repo_poll_state_issue_page_check CHECK (issue_page >= 1),
    ADD CONSTRAINT repo_poll_state_pr_page_check CHECK (pr_page >= 1);
