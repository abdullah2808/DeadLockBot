# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A Discord bot (Kotlin/JVM) that tracks Deadlock (the video game) matches for registered users. Users sign up with their Deadlock account ID via a slash command; a background scheduler polls the deadlock-api.com API every 3 minutes and posts an embed to the user's channel whenever a new match is detected. Users can also fetch their most recent match or last five matches on demand.

## Commands

Build the fat jar:
```bash
./gradlew shadowJar
```

Run all tests:
```bash
./gradlew test
```

Run a single test class or method (standard Gradle test filter):
```bash
./gradlew test --tests "data.UserRepositoryTest"
./gradlew test --tests "api.DeadlockClientTest.getRecentMatch retries after a 429 and then succeeds"
```

Tests run entirely offline — no Docker, Postgres, or Discord token required:
- [`UserRepositoryTest`](src/test/kotlin/data/UserRepositoryTest.kt) points Exposed at a fresh in-memory H2 database per test (`Database.connect("jdbc:h2:mem:...")`) instead of calling `DatabaseFactory.init()`, so real repository CRUD logic is exercised without a live Postgres instance.
- [`DeadlockClientTest`](src/test/kotlin/api/DeadlockClientTest.kt) injects a Ktor `MockEngine` in place of the real `HttpClient`, so retry/backoff/force-refetch behavior against `deadlock-api.com` is testable without network access. `DeadlockClient`'s `HttpClient` and retry delay are both constructor-injectable for this reason — don't hardcode `HttpClient(CIO)` calls elsewhere without a similar seam if new tests need them.
- [`RankRepositoryTest`](src/test/kotlin/models/RankRepositoryTest.kt) tests `RankRepository.rankImageForBadge`, the pure tier/sub-rank lookup extracted from `getRankImage` specifically so it doesn't require hitting `assets.deadlock-api.com`.

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

- **`api/`** — [DeadlockClient.kt](src/main/kotlin/api/DeadlockClient.kt) wraps the external `api.deadlock-api.com` HTTP API (Ktor client, `CIO` engine by default). `getRecentMatch` retries up to 3 times with a 10s backoff and forces a refetch (`force_refetch=true`) on the final attempt to handle the upstream API's own caching/rate-limiting. The underlying `HttpClient` and the retry delay are constructor parameters (defaulted for production use) purely so tests can substitute a `MockEngine` and a zero delay. A fresh `DeadlockClient` is created per call site (command handlers) or once for the scheduler's lifetime, and explicitly `close()`d where appropriate — there's no shared/pooled client across the app.
- **`bot/`** — Discord-facing glue built on Kord.
  - [Commands.kt](src/main/kotlin/bot/Commands.kt) registers global slash commands (`signup`, `unsubscribe`, `recentmatch`, `lastfive`) and dispatches `ChatInputCommandInteractionCreateEvent` to handlers by root command name. None of the handlers catch exceptions themselves — an unhandled throw (bad API response, DB error) surfaces to the user as a generic failed interaction in Discord rather than a friendly message; see `Main.kt`'s TODOs.
  - [Scheduler.kt](src/main/kotlin/bot/Scheduler.kt) runs an infinite loop: fetch all tracked users from the DB, launch one concurrent coroutine per user to check their most recent match, compare against the stored `lastMatchId`, post an embed and update the stored match ID if it changed, then wait 3 minutes before the next cycle. Each user's job is given a `delay(index * 6000L)` head start before it does anything, staggering when requests actually go out to the upstream API — don't move that delay to the end of the job, since by then the request has already fired and staggering it no longer does anything.
- **`data/`** — Persistence via Exposed + Postgres. [UserTable.kt](src/main/kotlin/data/UserTable.kt) defines the `tracked_users` table (primary key `account_id`, unique index on `discord_id`); [UserRepository.kt](src/main/kotlin/data/UserRepository.kt) provides plain synchronous `transaction {}` CRUD functions (not suspend functions — called from coroutines but blocking under the hood). `addUser` returns a `SignupOutcome` (`REGISTERED` / `ALREADY_REGISTERED` / `ACCOUNT_ID_IN_USE`) rather than silently no-opping or letting a duplicate-key DB exception propagate — a single Deadlock account can only be tracked by one Discord user at a time. [DatabaseFactory.kt](src/main/kotlin/data/DatabaseFactory.kt) connects and creates the schema on startup against real Postgres; tests instead point Exposed directly at in-memory H2 (see Commands below) and never call `DatabaseFactory.init()`.
- **`models/`** — `@Serializable` DTOs mirroring the deadlock-api.com JSON responses ([MatchDTO.kt](src/main/kotlin/models/MatchDTO.kt), [MatchHistoryDTO.kt](src/main/kotlin/models/MatchHistoryDTO.kt)), plus two lookup repositories that fetch and cache static game data from `assets.deadlock-api.com`: [HeroRepository.kt](src/main/kotlin/models/HeroRepository.kt) (hero names/images, cached in-memory with a 6-hour TTL and a try/catch fallback to the last-good cache) and [RankRepository.kt](src/main/kotlin/models/RankRepository.kt) (rank tier images, fetched once, eagerly, in the object's top-level property initializers — unlike `HeroRepository` there's no TTL, no refresh, and no fallback, so a failed fetch the first time anything touches `RankRepository` poisons the object for the process's lifetime). `RankRepository.getRankImage` derives a team's average rank badge from `MatchDTO.matchInfo.averageBadgeTeam0/1` based on which team the player was on, then delegates to the pure, bounds-safe `rankImageForBadge(avgTeamRank, ranks)` for the actual tier/sub-rank lookup.
- **`util/`** — [MatchMessageGenerator.kt](src/main/kotlin/util/MatchMessageGenerator.kt) builds the Discord embeds for a single match (`generateRecentMatch`, used by both the on-demand command and the scheduler) and for the last-five summary (`generateLastFive`). [Env.kt](src/main/kotlin/util/Env.kt) is in the default (unnamed) package, not `util` — keep that in mind when importing it.

## Local development without Docker

`docker-compose.yml` is the intended way to run the full stack (app + Postgres) locally, and `./gradlew run` fails fast with a clear message if `DISCORD_TOKEN`/`DB_URL`/`DB_USER`/`DB_PASSWORD` are missing or the DB is unreachable (`Env.kt`'s `error(...)` calls, then a `ConnectException` out of `DatabaseFactory.init()`). If Docker isn't available in your environment, you can't get a real bot online, but you can still validate almost all the interesting logic through the test suite above — it's designed specifically not to need Postgres, Discord, or network access.

## Notes for future work

Known gaps tracked as TODOs in [Main.kt](src/main/kotlin/Main.kt): no error handling around signup, match info responses lack additional detail, no "end game screen" stats, no Deadlock game-client (GC) integration, and command responses are not ephemeral where they arguably should be. Beyond those, command handlers in `Commands.kt` have no try/catch of their own, so any unhandled exception (a bad API response, a DB error) surfaces to the Discord user as a generic failed-interaction error rather than a useful message.
