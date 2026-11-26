# GitWatch

GitWatch polls public GitHub repositories and posts a Discord message when a new issue or pull request appears. This repository is the first backend slice: a Spring Boot 3.5 service, Java 21, Maven. There is no frontend, no login, and no management API.

A poll runs about every 30 seconds and only considers repositories that already have an active subscription and a due `repo_poll_state` row. New issues and pull requests are published once to Kafka topic `github.events`. A worker on that topic applies subscription label filters and posts to Discord. The same GitHub number is not published again for 30 days.

## What is intentionally absent

- Frontend, Next.js, or any UI
- Auth endpoints, JWT, and GitHub OAuth login (the tables exist; login is not implemented)
- Management REST API for users, repos, channels, and subscriptions
- Telegram sender, bot, or chat-id flow. The `channels.type` column may contain `telegram`, and application code rejects creating one and never delivers to one
- GitHub App, inbound GitHub webhooks, private repositories
- Slack, teams, billing
- A `notifications.send` topic. Discord is delivered from the `github.events` consumer

## Run the dependencies

Docker Compose starts PostgreSQL, Redis, MongoDB, and Kafka in KRaft mode (no ZooKeeper).

```bash
docker compose up -d
```

From the host, the app uses `localhost:5432`, `localhost:6379`, `localhost:27017`, and `localhost:9092`. Give Kafka a few seconds to finish starting.

## Environment

| Variable | Purpose |
| --- | --- |
| `GITWATCH_ENCRYPTION_KEY` | Required. Base64 of 32 bytes. AES-GCM key for OAuth tokens and Discord webhook URLs. Never commit a real key. |
| `GITHUB_TOKEN` | GitHub token used as `Authorization: Bearer`. Public repos work without it at a lower rate limit. |
| `DATABASE_URL` | JDBC URL. Default `jdbc:postgresql://localhost:5432/gitwatch`. |
| `DATABASE_USERNAME` | Default `gitwatch`. |
| `DATABASE_PASSWORD` | Default `gitwatch`. |
| `REDIS_HOST` / `REDIS_PORT` | Default `localhost` / `6379`. |
| `MONGODB_URI` | Default `mongodb://localhost:27017/gitwatch`. |
| `KAFKA_BOOTSTRAP_SERVERS` | Default `localhost:9092`. Inside Compose the app profile uses `kafka:19092`. |
| `GITHUB_API_BASE` | Override only in tests. Default `https://api.github.com`. |
| `GITWATCH_SEED_ENABLED` | `true` inserts the documented dev rows through JPA so the webhook is encrypted. Default `false`. The seed does not call GitHub. |

`docker compose --profile app` sets a local-only encryption key (`base64` of `0123456789abcdef0123456789abcdef`). Do not reuse that key outside this compose file.

Generate a key:

```bash
openssl rand -base64 32
```

PowerShell:

```powershell
$env:GITWATCH_ENCRYPTION_KEY = "<base64 of 32 bytes>"
$env:GITHUB_TOKEN = "<token>"
```

## Run the app

Java 21 and Maven are required. The project compiles with `--release 21`.

```bash
mvn spring-boot:run
```

Or, after `mvn -DskipTests package`:

```bash
docker compose --profile app up --build
```

`GET /health` returns `{"status":"UP"}`. The process does not call GitHub during startup. The scheduled poller waits one interval, then locks due `repo_poll_state` rows with `SELECT ... FOR UPDATE SKIP LOCKED`. Repositories with no active subscription are not polled.

### Dev seed

Start once with `GITWATCH_SEED_ENABLED=true`. That inserts:

- user `seed@example.invalid`
- Discord channel whose webhook is `https://example.invalid/discord-webhook` (encrypted at rest; not a real Discord URL)
- repo `apache/iggy`
- active subscriptions for `issue.opened` and `pr.opened` with an empty label filter
- a due `repo_poll_state` row with null cursors

A null cursor baselines on the first poll: the current highest number is stored and nothing historical is published. Later polls publish numbers above that cursor. To alert from a known number instead, set `last_issue_number` and `last_pr_number` before the first poll. `src/main/resources/db/seed/dev-seed.sql` shows that update and is not applied by Flyway.

Replace the example webhook with a real Discord webhook in the database only through the application (so it is encrypted), not as plaintext SQL.

## What a poll does

1. Claim due rows for repos that have at least one active subscription.
2. `GET /repos/{owner}/{repo}/issues` and `GET /repos/{owner}/{repo}/pulls`, newest first.
3. The issues list also returns pull requests. Those are not emitted as `issue.opened`.
4. Skip numbers already stored in Redis at `dedup:{owner}/{repo}:{event_type}:{number}` (30 day TTL).
5. Publish to `github.events`, then move the cursor and set `next_poll_at` about 3 minutes ahead. If more results sit above the cursor than `gitwatch.poller.max-pages` can read, that poll publishes the pages it fetched and the next poll continues at the following GitHub page. The cursor advances once a later page reaches it. Items opened during that catch-up are published on the poll that returns to page 1.
6. On HTTP 429, or when `X-RateLimit-Remaining` is below 10, set `next_poll_at` from `Retry-After` or the rate-limit reset instead of polling again immediately. A 403 whose body says rate limit is treated the same way.

Kafka payload:

```json
{"event_type":"issue.opened","owner":"apache","repo":"iggy","number":1423,"title":"Add support for streaming queries","author":"username","labels":["good first issue"],"url":"https://github.com/apache/iggy/issues/1423","body_excerpt":"First 200 characters...","created_at":"2026-11-17T03:00:00Z"}
```

`pr.opened` uses the pull request URL. Topics are `github.events` and `notifications.dlq`.

## What a delivery does

The worker loads active subscriptions for that repo and event type. Label filters are AND: every required label must be present. An empty filter matches every event of that type. Telegram channels are skipped and never called. A matching Discord webhook receives:

```text
🟢 New Issue in apache/iggy

#1423 – Add support for streaming queries
Author: @username
Labels: good first issue, help wanted

https://github.com/apache/iggy/issues/1423
```

Pull requests say `New Pull Request` and use the pull request URL. The Labels line is omitted when there are no labels.

A Discord 4xx or an invalid webhook is logged `failed` in MongoDB collection `notification_logs` and published to `notifications.dlq`. It is not retried. A Discord 5xx or a network error is retried with backoff, at most 3 attempts, then logged `failed` and sent to the DLQ. `notification_logs.created_at` has a 90 day TTL index.

## Tests

Tests use MockWebServer. They do not call GitHub or Discord. A PostgreSQL Testcontainers test for `SKIP LOCKED` runs when Docker is already running and is skipped otherwise.

```bash
mvn test
```

`./mvnw test` (or `mvnw.cmd test` on Windows) does the same thing if Maven is not on the PATH. The wrapper downloads Maven 3.9.11.

## Encryption

OAuth `access_token` and `refresh_token` are stored as `enc:v1:` AES-GCM ciphertext. Channel `config` stays JSON, and `webhookUrl` inside it is the same ciphertext. The key is only `GITWATCH_ENCRYPTION_KEY`. Plaintext values already in the database are rejected rather than treated as encrypted.
