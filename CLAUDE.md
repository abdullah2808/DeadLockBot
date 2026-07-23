# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Discord bot (Kotlin/JVM) that tracks Deadlock (the video game) matches for registered users. Users sign up with their Deadlock account ID via a slash command; a background scheduler polls the deadlock-api.com API every 3 minutes and posts an embed to the user's channel whenever a new match is detected. Users can also fetch their most recent match or last five matches on demand.

## Commands

Build the fat jar:
```bash
./gradlew shadowJar
```

Run tests (no test sources exist yet — `src/test` is not present):
```bash
./gradlew test
```

Run locally (requires a `.env` file, see Configuration below):
```bash
./gradlew run
```

Full container build/run via Docker Compose (builds the app image and starts Postgres):
```bash
docker-compose up --build
```

The `Dockerfile` is a two-stage build: `gradle:8.14-jdk21` builds the shadow jar, then `eclipse-temurin:21-jre` runs it as `app.jar`.

## Configuration

Config is loaded in [Env.kt](src/main/kotlin/util/Env.kt) via `dotenv-kotlin`: it reads a local `.env` file first, falling back to real process environment variables (so the same code path works locally and in the container). Required variables:
- `DISCORD_TOKEN`
- `DB_URL` — parsed as a URI to build a JDBC Postgres URL (see [DatabaseFactory.kt](src/main/kotlin/data/DatabaseFactory.kt))
- `DB_USER`
- `DB_PASSWORD`

Missing values throw immediately at startup (`error(...)`), not lazily.

## Architecture

Entry point is [Main.kt](src/main/kotlin/Main.kt), which wires everything together: init the DB, log in to Discord via Kord, register slash commands, start the interaction handler, and launch the polling scheduler as a background coroutine.

- **`api/`** — [DeadlockClient.kt](src/main/kotlin/api/DeadlockClient.kt) wraps the external `api.deadlock-api.com` HTTP API (Ktor/CIO client). `getRecentMatch` retries up to 3 times with a 10s backoff and forces a refetch (`force_refetch=true`) on the final attempt to handle the upstream API's own caching/rate-limiting. A fresh `DeadlockClient` (and its underlying `HttpClient`) is created per call site and explicitly `close()`d — there's no shared/pooled client.
- **`bot/`** — Discord-facing glue built on Kord.
  - [Commands.kt](src/main/kotlin/bot/Commands.kt) registers global slash commands (`signup`, `unsubscribe`, `recentmatch`, `lastfive`) and dispatches `ChatInputCommandInteractionCreateEvent` to handlers by root command name.
  - [Scheduler.kt](src/main/kotlin/bot/Scheduler.kt) runs an infinite loop: fetch all tracked users from the DB, launch one concurrent coroutine per user to check their most recent match, compare against the stored `lastMatchId`, post an embed and update the stored match ID if it changed, then wait 3 minutes before the next cycle. Each user job staggers itself with a 6s delay to avoid hammering the upstream API.
- **`data/`** — Persistence via Exposed + Postgres. [UserTable.kt](src/main/kotlin/data/UserTable.kt) defines the `tracked_users` table (keyed by `account_id`); [UserRepository.kt](src/main/kotlin/data/UserRepository.kt) provides plain synchronous `transaction {}` CRUD functions (not suspend functions — called from coroutines but blocking under the hood). [DatabaseFactory.kt](src/main/kotlin/data/DatabaseFactory.kt) connects and creates the schema on startup.
- **`models/`** — `@Serializable` DTOs mirroring the deadlock-api.com JSON responses ([MatchDTO.kt](src/main/kotlin/models/MatchDTO.kt), [MatchHistoryDTO.kt](src/main/kotlin/models/MatchHistoryDTO.kt)), plus two lookup repositories that fetch and cache static game data from `assets.deadlock-api.com`: [HeroRepository.kt](src/main/kotlin/models/HeroRepository.kt) (hero names/images, cached in-memory with a 6-hour TTL) and [RankRepository.kt](src/main/kotlin/models/RankRepository.kt) (rank tier images, fetched once at object init with no TTL/refresh). `RankRepository.getRankImage` derives a team's average rank badge from `MatchDTO.matchInfo.averageBadgeTeam0/1` based on which team the player was on.
- **`util/`** — [MatchMessageGenerator.kt](src/main/kotlin/util/MatchMessageGenerator.kt) builds the Discord embeds for a single match (`generateRecentMatch`, used by both the on-demand command and the scheduler) and for the last-five summary (`generateLastFive`). [Env.kt](src/main/kotlin/util/Env.kt) is in the default (unnamed) package, not `util` — keep that in mind when importing it.

## Notes for future work

Known gaps tracked as TODOs in [Main.kt](src/main/kotlin/Main.kt): no error handling around signup, match info responses lack additional detail, no "end game screen" stats, no Deadlock game-client (GC) integration, and command responses are not ephemeral where they arguably should be.
