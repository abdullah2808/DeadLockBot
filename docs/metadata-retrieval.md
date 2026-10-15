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
