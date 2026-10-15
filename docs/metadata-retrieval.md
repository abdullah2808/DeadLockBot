# Match metadata retrieval research

Checked October 14, 2026.

## What is limited

The logged error comes from `CMsgClientToGCGetMatchMetaData`, sent by the configured Steam bot session. Its response returns download references, including a metadata salt and replay server group. The full scoreboard and event data are downloaded separately from Valve's CDN. The [published protocol](https://github.com/SteamTracking/Protobufs/blob/master/deadlock/citadel_gcmessages_client.proto#L1087-L1110) reports `k_eResult_RateLimited`, without a quota, remaining count, or retry time. It does not identify whether enforcement is per account, session, IP, target player, or endpoint. We cannot determine the exact exhausted bucket from this log alone.

The previous retrieval order queried GC before the API for every uncached match. Participant-specific retries could also request the same match again when a download failed. This consumes fresh lookup capacity even when the public service already has the complete data. Reactive backoff reduces repeated failures but does not prevent consuming the initial budget.

The [Deadlock API salts implementation](https://github.com/deadlock-api/deadlock-api/blob/master/api/src/routes/v1/matches/salts.rs) currently spaces fresh requests at 50 per bot per day. This is an operator's policy, not proof of Valve's exact limit. The bot's default 30-minute spacing allows approximately 48 fresh metadata queries per day of uninterrupted operation; any response or failed attempt reserves the interval. History requests do not consume this local pacing interval. Reactive Valve cooldowns remain shared across GC query types.

## Alternatives assessed

| Source | Full scoreboard and event records? | Fresh GC lookup needed? |
| --- | --- | --- |
| Local enriched match cache | Yes, to the extent recorded | No |
| Public metadata with `disable_steam=true` | Yes when stored; may also use already stored salts to download the CDN file | No |
| Public salts with `disable_steam=true`, then CDN | Yes when references and the file exist | No, but redundant with the metadata endpoint's stored-salt path |
| `GetMatchMetaData`, then Valve CDN | Yes when permitted and ready | Yes, for references |
| Match history / account statistics / profile card | Summary or aggregate data | No equivalent full match timeline found |
| Metadata from a participant's local game cache or an uploaded replay | Potentially, with an import/parser feature | Separate future workflow; server bot cannot access players' local files |

The [API specification](https://api.deadlock-api.com/openapi.json) documents `disable_steam=true` for both metadata and salts. Its public Steam-backed metadata quota (currently 3/hour per IP without a key) belongs to that service and must not be mistaken for Valve's direct GC quota. The [metadata handler](https://github.com/deadlock-api/deadlock-api/blob/master/api/src/routes/v1/matches/metadata.rs) and [salt lookup](https://github.com/deadlock-api/deadlock-api/blob/master/api/src/routes/v1/matches/salts.rs) confirm that stored metadata or stored salts can be used without contacting GC. Calling metadata/raw instead of metadata changes serialization, not the source or underlying limits.

The protocol's metadata request also accepts a known salt. Obtaining a salt by making another fresh metadata request does not solve the limit. We found no documented alternate GC method that returns equivalent full-match inventories, ability upgrades, and combat events without the metadata file. We do not infer missing events from summary totals.

## Implemented path and verification

1. Return an enriched local match immediately when available.
2. Request public metadata with `disable_steam=true`, under the existing API cooldown and an eight-second HTTP timeout. A 429/503 with `Retry-After` is respected.
3. On a miss, reuse a recent download reference or request one through the shared, paced GC session.
4. Download and decode Valve metadata, persist one completed match, and reuse it for all participants.
5. On failure, retain pending enrichment and basic summary notifications. No fresh GC request is sent during pacing or cooldown. Successful references remain reusable for one hour, bounded to 256 matches.

A read-only request to `/v1/matches/116526948/metadata?disable_steam=true` returned HTTP 200 with 12 players, item records for all 12, and death details for all 12. This demonstrates the alternative for the reported match; it does not guarantee storage coverage for future matches. Tests cover source ordering, validation, cancellation, shared pacing, reference reuse, and API retry headers. Live Discord interactions still require a deployed bot.

The default interval is conservative and configurable from 0 to 86400 seconds. Zero disables proactive pacing, not reactive rate-limit handling. Neither setting changes Valve's server-side limits or guarantees a reset time. Restarting clears client pacing but cannot reset Valve's quota.

## October 15 follow-up: frequent "metadata unavailable"

`/match` and **Open full match** still often reported `Full metadata for match … is unavailable`. Tracing the code and deadlock-api's source found these causes:

1. **Scarce lookups were wasted.** The 30-minute GC interval was reserved before the request was sent, so a GC session that was not ready yet spent it without sending anything. Every non-success answer was treated the same, so a denied account could be retried and spend the scarce budget again and again.
2. **The GC target was whoever asked.** The bot set `target_account_id` to the requester. When someone else clicked a notification's button, the target was not in the match. deadlock-api sends the query untargeted (`target_account_id: None`).
3. **Background backoff blocked people.** After a background failure, `/match` returned "unavailable" without trying anything, for up to 15 minutes, even when the public archive had since stored the match.
4. **The public API's Steam path was never used.** With `disable_steam=true`, a fresh match not yet stored by deadlock-api always misses. deadlock-api's Steam-backed path has its own quota ([salts.rs](https://github.com/deadlock-api/deadlock-api/blob/master/api/src/routes/v1/matches/salts.rs): 3/hour per IP, 300/hour per key via `X-API-Key`). That quota is separate from this bot's GC budget. The earlier concern was that its 429s would pause stored lookups through the shared gate.
5. **One message for every cause.** Users could not tell "still processing" from "waiting for the next lookup", "denied", or "invalid".

Changes:

- `GcRequestGate` reserves the interval only when the message is actually sent.
- GC results are classified: `InvalidPermission` marks that target denied for the match, `InvalidMatch` stops Steam lookups for it, and `MatchInFlight` or a missing salt mean processing. Other results count as busy.
- Targets are retained participants, bot friends first, then an untargeted query. Lookups with no registered requester and no tracked participant never spend GC budget.
- deadlock-api's Steam-backed lookup runs after a stored miss and before the bot's own GC query. It has its own pacing (20 minutes keyless, 15 seconds with a key) and its own 429 cooldown. Disable it with `DEADLOCK_API_STEAM_FALLBACK=false`.
- Known download references are reused for 24 hours. A known reference skips both Steam sources, because only Valve's file is missing.
- Interactive lookups bypass the participant backoff. A cached report no longer waits behind another match's slow Steam lookup.
- `/match` reports the reason and an estimated retry time.

Limits that remain: Valve's own quota is unknown, and the bot runs a single Steam account. If reports are still slow at your volume, the most effective options are a deadlock-api key (100× the Steam-backed quota) and a second Steam bot account (doubles GC capacity; this would need a client pool). Lowering `STEAM_METADATA_INTERVAL_SECONDS` raises the risk of Valve's rate-limit cooldown, which also pauses match-history polling.

