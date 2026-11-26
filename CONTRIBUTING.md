# Contributing

GitWatch polls public GitHub repositories and posts a Discord message when a new issue or pull request appears. This repository is the first backend slice: a Spring Boot 3.5 service, Java 21, Maven. There is no frontend, no login, and no management API.

`pom.xml` describes the artifact as: Poll public GitHub repositories and deliver Discord alerts for new issues and pull requests.

The only HTTP route is `GET /health`. It returns `{"status":"UP"}`.

Read [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) before you participate. Report a security issue as [SECURITY.md](SECURITY.md) describes. Do not file a conduct report or a security report as a public issue.

## What is intentionally absent

- Frontend, Next.js, or any UI
- Auth endpoints, JWT, and GitHub OAuth login (the tables exist; login is not implemented)
- Management REST API for users, repos, channels, and subscriptions
- Telegram sender, bot, or chat-id flow. The `channels.type` column may contain `telegram`, and application code rejects creating one and never delivers to one
- GitHub App, inbound GitHub webhooks, private repositories
- Slack, teams, billing
- A `notifications.send` topic. Discord is delivered from the `github.events` consumer

Discuss first, in an issue, before you open a pull request that adds anything in that list. That includes a frontend, auth, a management API, Telegram, Slack, teams, billing, a GitHub App, inbound webhooks, or private-repository support.

## Setup

Java 21 and Maven are required. The project compiles with `--release 21`. `pom.xml` sets `java.version` to 21, the Spring Boot parent to `3.5.16`, and the artifact version to `0.1.0-SNAPSHOT`.

Docker Compose starts PostgreSQL, Redis, MongoDB, and Kafka in KRaft mode (no ZooKeeper).

```bash
docker compose up -d
```

From the host, the app uses `localhost:5432`, `localhost:6379`, `localhost:27017`, and `localhost:9092`. Give Kafka a few seconds to finish starting.

`GITWATCH_ENCRYPTION_KEY` is required. It is base64 of 32 bytes, and it is the AES-GCM key for OAuth tokens and Discord webhook URLs. Never commit a real key.

```bash
openssl rand -base64 32
```

`GITHUB_TOKEN` is optional. The app uses it as `Authorization: Bearer`. Public repos work without it at a lower rate limit.

PowerShell, as README shows it:

```powershell
$env:GITWATCH_ENCRYPTION_KEY = "<base64 of 32 bytes>"
$env:GITHUB_TOKEN = "<token>"
```

Run the app:

```bash
mvn spring-boot:run
```

If Maven is not on the PATH, use `./mvnw spring-boot:run`, or `mvnw.cmd spring-boot:run` on Windows. `mvnw` passes its arguments to Maven, and the wrapper downloads Maven 3.9.11. README says `mvn test`, `./mvnw test`, and `mvnw.cmd test` are equivalent.

## Tests

Tests use MockWebServer. They do not call GitHub or Discord. A PostgreSQL Testcontainers test for `SKIP LOCKED` runs when Docker is already running and is skipped otherwise.

```bash
mvn test
```

`./mvnw test` (or `mvnw.cmd test` on Windows) does the same thing if Maven is not on the PATH. The wrapper downloads Maven 3.9.11.

## Pull requests

Pull requests target `main`. Describe the change and how you tested it.

Do not commit secrets, tokens, webhook URLs, or encryption keys. `docker compose --profile app` sets a local-only encryption key. Do not reuse that key outside `docker-compose.yml`.

## Questions

Questions go to a GitHub issue. Security reports and conduct reports do not.
