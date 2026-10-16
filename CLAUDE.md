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

- **`api/`** — `DeadlockClient` wraps the external API with injectable HTTP transport, retries and a shared metadata rate-limit gate. `MatchMetadataService` is shared by scheduler and commands: cache, stored API (`disable_steam=true`) and known Valve download references first; then a GC reference query under `GcMetadataBudget` (rolling 40/24h, 20 s apart, persisted in `gc_metadata_queries`, quarter reserved for tracked matches) targeting a participant (bot friends first, untargeted last; a `viewer` is never assumed to be a participant); then deadlock-api's Steam-backed lookup (separate quota and cooldown lane in `MetadataRequestGate`) only when the GC cannot be asked or answer. Valve is re-asked about an unfinished match only after 1/3/10/30/60 min. `lookup` returns `MetadataLookup.Unavailable` with a reason that `/match` explains. Interactive lookups skip the background retry delay. Valve's GC result is classified (`GcMetadataException`): denied targets are not retried for that match, invalid matches stop Steam lookups. `GcRequestGate` spends the budget only when a request is actually sent. Completed documents are cached once per match and persisted. `api/gc` shares one serialized Steam session for history and metadata. Match response type and echoed job ID; reconnect on in-flight timeout/cancellation before allowing untagged responses in another request. Valve files may be zstd despite the `.bz2` extension. Decoder tests cover both compression formats, unsigned account IDs, identity checks and size limits.
- **`bot/`** — Discord-facing glue built on Kord.
  - `Commands.kt` registers `signup`, `unsubscribe`, `recentmatch`, `lastfive`, and `steambot`. Signup resolves numeric/vanity profile input, authorizes incoming friend acceptance for 15 minutes, and tries GC verification before the public API. `/steambot` and permission-denied responses provide the signed-in bot's profile button and friend code. SteamFriendPolicy only accepts incoming requests for tracked accounts/recent signups, with duplicate suppression and retries; `STEAM_AUTO_ACCEPT_FRIENDS=false` disables acceptance. Do not introduce outgoing invitations or infer public Steam settings grant GC access.
  - `Scheduler.kt` polls newest GC matches (active-feed fallback without Steam config), syncs tracked friend eligibility and posts before persisting last_match_id. Source polls are separated by 90 seconds plus source work time. Metadata retrieval starts at discovery; a notification posts once metadata arrives or, after `NotificationTiming`'s 60-second deadline (monotonic clock), with the basic GC summary. Basic posts are queued in `match_notifications` and edited in place by `enrichPostedNotifications` when metadata arrives (24 h window, survives restarts). The loop wakes for poll deadlines and `MatchMetadataService.nextRetryInMillis()`. Duplicate discoveries and failed sends do not reset the deadline. On-demand commands remain immediate. No bulk metadata downloads for all 50 GC history rows.
- **`data/`** — Persistence via Exposed + Postgres. [UserTable.kt](src/main/kotlin/data/UserTable.kt) defines the `tracked_users` table (primary key `account_id`, unique index on `discord_id`); [UserRepository.kt](src/main/kotlin/data/UserRepository.kt) provides plain synchronous `transaction {}` CRUD functions (not suspend functions — called from coroutines but blocking under the hood). `addUser` returns a `SignupOutcome` (`REGISTERED` / `ALREADY_REGISTERED` / `ACCOUNT_ID_IN_USE`) rather than silently no-opping or letting a duplicate-key DB exception propagate — a single Deadlock account can only be tracked by one Discord user at a time. [DatabaseFactory.kt](src/main/kotlin/data/DatabaseFactory.kt) connects and creates the schema on startup against real Postgres; tests instead point Exposed directly at in-memory H2 (see Commands below) and never call `DatabaseFactory.init()`.
- **`models/`** — `@Serializable` DTOs mirroring the deadlock-api.com JSON responses ([MatchDTO.kt](src/main/kotlin/models/MatchDTO.kt), [MatchHistoryDTO.kt](src/main/kotlin/models/MatchHistoryDTO.kt)), plus hero and rank lookup repositories backed by `https://api.deadlock-api.com/v1/assets`. [AssetCatalog.kt](src/main/kotlin/models/AssetCatalog.kt) loads lazily, refreshes after six hours or on a missing ID with a one-minute cooldown, and keeps its last good entries during outages. Rank image lookup uses the explicit tier rather than the catalog's array order, and unavailable rank images do not prevent posting a match. Catalog behavior and Discord channel decoding are covered by offline regression tests.
- **`util/`** — [MatchMessageGenerator.kt](src/main/kotlin/util/MatchMessageGenerator.kt) builds the Discord embeds for a single match (`generateRecentMatch`, used by both the on-demand command and the scheduler) and for the last-five summary (`generateLastFive`). [Env.kt](src/main/kotlin/util/Env.kt) is in the default (unnamed) package, not `util` — keep that in mind when importing it.

## Local development without Docker

`docker-compose.yml` is the intended way to run the full stack (app + Postgres) locally, and `./gradlew run` fails fast with a clear message if `DISCORD_TOKEN`/`DB_URL`/`DB_USER`/`DB_PASSWORD` are missing or the DB is unreachable (`Env.kt`'s `error(...)` calls, then a `ConnectException` out of `DatabaseFactory.init()`). If Docker isn't available in your environment, you can't get a real bot online, but you can still validate almost all the interesting logic through the test suite above — it's designed specifically not to need Postgres, Discord, or network access.

## Notes for future work

Full end-game timelines/items are not stored; the decoder retains final per-player stats and summary fields. Notifications discover the newest match only. Sending before database persistence can duplicate delivery after a crash; offline tests cannot verify live Steam permissions or friend acceptance.

## Deployment and current match tracking

`/recentmatch` resolves the requested account's Steam display name through the
shared PlayerNameService, preferring GcClient's Steam friend persona cache and
falling back to SteamAccountResolver.playerName. The public profile response must
match the requested numeric account; XML entities/redirects are disabled and
downloads/time are bounded. Profile names cache for 15 minutes, failures for one
minute. Missing names fall back to an explicit account-ID label, never the
requester's Discord name. Escape Steam names as literal title text. Signup,
automatic notifications and `/lastfive` retain their existing behavior.

See DEPLOYMENT.md for the home-lab commands and acceptance checks. Compose uses
DB_PASSWORD, an internal DB URL, no host DB port, bounded logs, memory limits and
a mounted state directory. Username plus the saved STEAM_TOKEN_FILE supports
headless Steam login. Initially deploy with Steam credentials empty until the
dedicated account has Deadlock access. CI verifies PRs and publishes amd64 GHCR
images on main only, tagged latest and full SHA.

Scheduler uses GC newest-match discovery or active-feed fallback, then retrieves
metadata from Valve with the public API as fallback. It requires an accessible channel, sends before
persisting last_match_id, and retains failed sends for retry. A crash or DB error
after send can duplicate delivery. Cancellation propagates through scheduler,
match sources and API client. SchedulerTest covers delivery ordering and failures.
GC history/metadata share readiness, request serialization, response routing and connection reset after timeout/cancellation.

GC history now preserves basic match summaries rather than discarding everything
except IDs. CachedMatchTable stores shared match information/metadata once;
CachedPlayerMatchTable stores each account's stats with a composite key. Retain
25 player rows per account and delete shared rows only when no account references
them. Recent commands use GC summaries, with metadata fallback only for incomplete
rows. The scheduler can send a basic summary during API outages. Metadata requests
share completed-match cache entries and a Retry-After cooldown, while unavailable
basic data is retained with bounded backoff. scripts/update.py selects a published
main SHA and updates IMAGE_TAG automatically, with startup checks and rollback.
