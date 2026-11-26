# Security policy

## How to report

Do not open a public issue, pull request, or discussion for a vulnerability. Report through https://debangshu.dev.

Never include a live encryption key, GitHub token, or Discord webhook URL in a report.

## What to include

Include the affected version or commit, what an attacker can do, and steps to reproduce if you have them. A patch is not required.

## In scope

This repository, the Spring Boot service it builds, and the way it stores secrets. OAuth `access_token` and `refresh_token` values, and Discord webhook URLs, are stored as `enc:v1:` AES-GCM ciphertext. The key is only `GITWATCH_ENCRYPTION_KEY`.

## Out of scope

GitHub, Discord, PostgreSQL, Redis, MongoDB, and Kafka themselves are out of scope.

The local-only encryption key set by `docker compose --profile app` is out of scope. README says not to reuse that key outside `docker-compose.yml`.

A vulnerability in a dependency with no impact on this service is out of scope. You can still report it. A Dependabot alert or a normal issue is fine for an unused advisory.

## Supported versions

There are no releases. The only supported line is the `main` branch. The version in `pom.xml` is `0.1.0-SNAPSHOT`.
