CREATE UNIQUE INDEX repos_owner_repo_lower_idx ON repos (lower(owner), lower(repo));
