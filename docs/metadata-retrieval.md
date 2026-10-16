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


## October 15 (later): how other Valve games get post-match data

The question was whether Dota 2 or Counter-Strike tooling uses a faster path that Deadlock also offers. Constraint kept from the plan review: server-side sources only (no participant cache scanning, desktop helper, or participant uploads).

| Game | How sites get post-match data quickly | Deadlock equivalent? |
| --- | --- | --- |
| Dota 2 | Public Steam Web API `IDOTA2Match_570/GetMatchDetails` returns full post-match stats with a normal Web API key. Deeper replay data needs GC replay salts, which OpenDota fetches with pools of Steam accounts on several IPs; its 2015 architecture notes cite per-account and per-IP daily replay limits ([OpenDota architecture](https://blog.opendota.com/2015/03/05/architecture/)). | **No.** SteamTracking lists only `ICitadelAdmin_1422450` (one `GetReplayURL` method, an admin-named interface, likely restricted) and `IGCVersion_1422450` for Deadlock ([SteamTracking API](https://github.com/SteamTracking/SteamTracking/tree/master/API)). Deadlock works like Dota's *replay* path: GC salt, then CDN. |
| CS2 | Players hand over a share code (match ID, outcome ID, token) and an auth code; sites discover new matches with `ICSGOPlayers_730/GetNextMatchSharingCode`, then a bot asks the GC for full game info, which returns the demo URL ([Leetify](https://leetify.com/blog/share-codes/), [csgo library](https://csgo.readthedocs.io/en/stable/csgo.features.match.html)). | **No.** No share-code or auth-code equivalent exists; the token is what authorizes CS2 lookups. Local-client tools are excluded by the constraint. |
| Live broadcast (CS GOTV / Dota broadcast) | Data during the match, so stats exist the moment it ends. | **Partly.** deadlock-api's live-events service streams spectate data 60–90 s behind real time with an `end` event and per-player kills, deaths, assists, net worth and hero damage. It needs a key and bills each match subscription; the subscription must start while the match is live ([quickstart](https://live.deadlock-api.com/docs/quickstart), [source](https://github.com/deadlock-api/deadlock-api/tree/master/live-events)). It is a different data shape from the metadata file. Treat it as a separate project, not the critical path. |
| TF2 (logs.tf) | Server plugins push logs from servers the community controls. | **No.** Matchmaking servers are Valve's. |

Conclusion: no faster Valve channel exists for Deadlock. The GC metadata query, then Valve's CDN file, is the canonical path, and deadlock-api uses the same one. What other games teach is operational: budget per Steam account rather than spacing evenly, persist intermediate references, post a summary first and upgrade it, and scale with more bot accounts.

The strongest evidence on budget comes from deadlock-api's own ingest tool, which runs on players' personal accounts: `FETCH_QUOTA_LIMIT = 40` per rolling 24 h, `GC_MIN_INTERVAL = 20s`, and the whole window treated as spent on `RateLimited` ([quota.rs](https://github.com/deadlock-api/deadlock-api-ingest/blob/master/src/gc/quota.rs), [mod.rs](https://github.com/deadlock-api/deadlock-api-ingest/blob/master/src/gc/mod.rs)). The tool also refuses to run while the game is open, because one Steam account can hold only one GC session. Never play Deadlock on the bot's account.

Paid options that may shorten waits, unverified for freshness: a deadlock-api key (Steam-backed lookups go from 3 to 300 per hour), and deadlock-api's [Patreon prioritized fetching](https://deadlock-api.com/patron), which moves the chosen accounts to the front of its queue.

## Cross-check against the Codex fast-metadata plan

| Plan item | Status in this branch |
| --- | --- |
| Distinguishable causes; local pacing never reported as a Valve rejection | Done: `MetadataLookup.Unavailable` reasons, `/match` messages, and GC result classification. |
| Count only sent attempts, failures included | Done: `GcRequestGate` spends the budget at send time. |
| Rolling persisted budget with a short interval instead of 30-minute spacing | Done: `GcMetadataBudget`, 40/24 h and 20 s by default, persisted in `gc_metadata_queries`, with a quarter reserved for tracked matches. |
| RateLimited: longer adaptive cooldown, no minute-by-minute probing | Done: metadata pauses for 1 h, then 6 h, then 24 h, on top of the shared 1–15 min history cooldown. |
| MatchInFlight / TooBusy / Timeout: bounded retries | Done: re-ask after 1, 3, 10, 30 and 60 minutes, at most six queries per match. |
| InvalidPermission: change target rather than retry unchanged | Done: a denied target is never re-asked for that match; the next participant, then an untargeted query, is tried. |
| InvalidMatch: stop | Done. |
| CDN not ready: reuse the reference, no new GC lookup | Done: references are kept for 24 h; Steam sources are skipped while one exists. |
| Viewer never silently assumed to be a participant | Done: buttons and `/match <id>` use `viewer = true`. |
| Start lookups at discovery and drop the fixed 60 s pre-fetch delay | Done: the 60 s deadline now only limits how long posting waits for metadata. |
| Quick miss schedule (0, 15 s, 30 s, 60 s, …) and waking for it | Done: 15 s doubling to 15 min, and the scheduler wakes for `nextRetryInMillis()`. |
| Post promptly, persist the message ID, edit when metadata arrives | Done: `match_notifications` plus `enrichPostedNotifications`; 24 h window, survives restarts. |
| Keyed deadlock-api Steam provider only when direct GC is deferred or unavailable; no duplicate fresh lookups | Done: GC first; the API Steam lane runs only when the GC can't be asked or answer, and never after Valve said processing or invalid. Its budget is in memory, not persisted. |
| Persist references and jobs across restarts | Partial: posted-notification jobs and the GC budget persist; download references do not (a restart may cost one extra query per in-flight match). |
| No global metadata mutex across network operations | Partial: cached reports and the scheduler's wake check avoid it, but uncached lookups are still serialized. The GC is serialized anyway; per-match coalescing is future work. |
| Per-stage timestamps and a 20–30 match pilot comparing targeted and untargeted queries | Partial: logs now include query mode and result, remaining budget, lookup duration, discovery-to-post and post-to-edit times. The pilot itself must be run on the live bot. |
| Statlocker / live events evaluation | Not started; see the table above. |
