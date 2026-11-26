# Management API

Auth and the management routes sit on the notification pipe. Routes are under `/v1`. There is no frontend and no Telegram delivery.

## Environment

| Variable | Required | Purpose |
| --- | --- | --- |
| `GITWATCH_JWT_SECRET` | Yes, outside tests | HMAC secret for HS256 access tokens and OAuth state. At least 32 bytes of UTF-8. The process refuses to start without it. |
| `GITWATCH_ENCRYPTION_KEY` | Yes | Existing AES-GCM key. Discord webhooks and GitHub OAuth access tokens are stored with it. |
| `GITHUB_OAUTH_CLIENT_ID` | No | GitHub OAuth app client id. If this or the secret is unset, only the two OAuth routes return 503. The rest of the API still starts. |
| `GITHUB_OAUTH_CLIENT_SECRET` | No | GitHub OAuth app secret. Same 503 rule. |
| `GITHUB_OAUTH_REDIRECT_URI` | No | Callback URL registered on the OAuth app. When unset, the redirect is built from the incoming request as `{scheme}://{host}/v1/auth/github/callback`. |

Access tokens expire 7 days after they are issued (`gitwatch.jwt-expiry`, default `7d`). Validation allows the Spring Security default of 60 seconds of clock skew. There is no refresh token. OAuth `state` is an HS256 token that expires after 10 minutes and cannot be used as an access token.

Passwords must be 8 to 72 bytes and are stored with BCrypt. The hash is never returned.

## Envelope

Success is `{"data": ...}`. A list that is paged adds `pagination` with `limit`, `offset`, and `total`. Errors are `{"error": {"code": "...", "message": "..."}}`.

Login and an unknown email return the same 401, `Invalid email or password`. A wrong password is not distinguished from a missing account.

## Auth

- `POST /v1/auth/register` `{email, password, name}` returns the user and a token. Email is stored lowercase.
- `POST /v1/auth/login` `{email, password}` returns the user and a token.
- `GET /v1/auth/me` returns `id`, `email`, `name`, `avatar_url`. Bearer token required.
- `GET /v1/auth/github` redirects to GitHub when OAuth is configured, otherwise 503 `OAUTH_NOT_CONFIGURED`.
- `GET /v1/auth/github/callback` finishes login and returns the same token shape. Tests mock the token exchange. Do not call GitHub from tests.

## Watches

`repos` stays the shared repository row. A caller's watch is a `user_repos` row pointing at that id. It is not a second repository.

`POST /v1/repos` `{url}` accepts an `http` or `https` `github.com` repository URL, including a trailing slash or `.git`. Anything else is 400 `VALIDATION_ERROR`. The GitHub API must show the repository as public. A private repository is 400 `PRIVATE_REPO`. A 404 is 400 `NOT_PUBLIC`. A second user watching the same owner/repo reuses the row and does not reset `repo_poll_state`.

`is_active` on a repo response is that caller's watch, not the shared `repos.is_active` flag. `PATCH /v1/repos/{id}` `{is_active}` turns the watch on or off and sets that caller's subscriptions for the repo to the same value, so the existing notifier stops delivering without a poller change. `DELETE /v1/repos/{id}` removes only that caller's watch and subscriptions. The shared repo and its poll state are deleted only when nobody else still watches it and no subscription remains.

## Channels, subscriptions, profile

`POST /v1/channels` accepts `type: discord` and `config.webhook_url` (https only). `type: telegram` is 400 `UNSUPPORTED_CHANNEL`. The webhook is encrypted with the existing helper and is not returned or logged. `POST /v1/channels/{id}/test` sends one Discord message through `DiscordClient` and returns `{"data": {"status": "sent"}}`.

`POST /v1/repos/{repoId}/subscriptions` takes `channel_id`, `event_type` (`issue.opened` or `pr.opened`), and `filters.labels` as an AND list in the existing JSONB column. The watch and the channel must belong to the caller. `PATCH` and `DELETE` on `/v1/repos/{repoId}/subscriptions/{id}` update or remove that caller's subscription.

`GET /v1/notifications?limit&offset&status=success|failed` reads that caller's Mongo delivery log. Default limit is 20, maximum 100.

`PATCH /v1/users/me` `{name}` updates the name. `DELETE /v1/users/me` deletes the account, channels, subscriptions, OAuth links, and watches. Shared repo rows that someone else still watches stay.

## Rate limits

Auth routes (`register`, `login`, `github`, `github/callback`) are 10 requests per minute per client IP. Other `/v1` routes are 100 per minute per user id. Counters are Redis keys `ratelimit:{bucket}:{id}:{minute}`. If Redis cannot increment or set the expiry, the request is rejected with 503 `RATE_LIMIT_UNAVAILABLE`. The limit is not skipped. `GET /health` is not limited.
