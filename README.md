# DeadlockBot

A Kotlin Discord bot that tracks Deadlock matches and posts match recaps.

## Match commands

- `/match` with its `match_id` option set to `103328445` posts a full match scoreboard in the current channel. Select the command, press Tab to select the option on desktop, and paste only the numeric ID. Notification buttons open a report directly.
- `/match` shows the latest match for your account linked through `/signup`.
- `/recentmatch account_id:<id>` posts a player recap. Account IDs, SteamID64s, and numeric Steam profile URLs are accepted.
- `/lastfive` posts the latest five matches for your linked account.
- `/compare player:<user>` or `/compare account_id:<id>` compares two players' recent stats side by side. See [stat comparison](docs/stat-compare.md).
- `/unsubscribe` stops automatic tracking.

`/trends`, `/party`, `/compare`, `/steambot` and `/weekly action:preview` reply privately by default. Set their `public:True` option to post the reply in the channel instead. Discord fixes a reply's visibility before the command runs, so an error from a `public:True` command, such as a reminder to `/signup`, is also visible to the channel.

The scoreboard groups all players by team and displays heroes, K/D/A, souls, player damage, objective damage, healing, team soul totals, and final shop items. The latest recorded stats snapshot supplies damage and healing. Sold items and hero ability upgrades are excluded from the inventory. Longer inventories wrap onto a second row. Your row is outlined when viewing your latest linked match.

`/match` includes an **Explore match** button. It opens a private report with a hero/player selector and Stats, Build, Events, and Abilities views. Build offers final-item descriptions; Events offers filters and eight events per page. Each person can browse independently. Reports remain available for one hour, subject to a 50-report cache, and expire when the bot restarts; run `/match` again to reopen one. Descriptions use the current asset catalog.

Match notifications include **Open full match**; last-five recaps include one button per match. These open the PNG/HTML report privately with its explorer button, without typing a command. Their match IDs survive bot restarts and explorer-cache expiry, although metadata must still be available. For manual lookup, choose `/match`, select its `match_id` option (Tab on desktop), and paste only the numeric ID. Plain pasted `/match 123456` text does not reliably populate Discord's command option.

`/match` also attaches a standalone `.html` report. Download it and open it in a browser to hover/focus heroes for ability descriptions, hover/focus/tap items for descriptions and properties, select a player, and filter/scrub their purchase, sale and ability-event timeline. Images and scripts are embedded; no hosting or internet connection is needed to view the file. The PNG in Discord remains a static preview.

Progression preserves recorded event order and times, including sold items. Ability unlocks display Tier 0; the next three distinct upgrades for that ability infer Tiers 1, 2, and 3, with costs of 1, 2, and 5 AP. Inference requires a recorded unlock (`upgrade_id=0`) and identifiable upgrade records; missing progression stays unknown and duplicate upgrade IDs do not advance tiers. Raw IDs are retained. Events sharing a timestamp keep their original metadata order. Descriptions come from the current asset catalog, so historical patch values may differ. Older cached matches may lack progression or upgrade details; the report states when data is unavailable.

Player names are used if metadata supplies them; otherwise the scoreboard displays account IDs. Missing damage/healing stats display `—`. Hero and item images use the public Deadlock assets API, with cached images and fallbacks during outages. If full match metadata is unavailable or still processing, the command explains this instead of producing an incomplete scoreboard. Metadata cached by older bot versions may lack inventory; those rows show `Unavailable` for items.

Automatic notifications include result colors, hero thumbnails, K/D/A, souls per minute, farming stats, damage and support stats when available, and a command to open the full scoreboard. The scheduler records a notification as delivered after Discord accepts the message.

## Run and verify

The Dossier timeline also includes recorded kills and deaths. Select **Kills & deaths**, **Kills only**, or **Deaths only** in the event filter. Victim death records identify attackers by player slot, so kills show the victim's hero and deaths show the attacker's hero when identifiable. Unknown/ambiguous attackers remain labelled unavailable, and self-inflicted deaths do not create kill events. Older cached metadata may lack combat records; the report labels incomplete coverage instead of inventing timestamps from final K/D/A totals.

The HTML report uses hero names only and renders every participant supplied by metadata. Team banners show their actual hero counts and soul totals; there is no four-player cap. The original preview used an eight-player match. Standard 12-player matches show six heroes on each team.

The design keeps Deadlock's dark, brass-and-cream styling and amber/sapphire team banners. Item category colors, upgrade-component information, disabled-property filtering and ability-scaling/upgrade-effect formatting are adapted from [Soul Ledger](https://github.com/abdullah2808/Soul-Ledger). Prices and effects use the live asset catalog; simulator investment totals are not presented as observed match stats.

Startup replaces this application's complete global command list, automatically removing obsolete global commands. Commands registered specifically to an individual server have a separate list and need separate cleanup; commands belonging to other Discord applications are unaffected.

Startup also clears this application's legacy server-specific commands in server `875604309180559383`. It checks the server command list first and skips the overwrite once the list is empty. The global commands remain available; other servers and applications are unaffected. A cleanup access error is logged and retried on the next startup without preventing the bot from running.

Use JDK 21. Configure the existing environment variables required by `Env` and the database, then run `./gradlew run` (`.\gradlew.bat run` on Windows). Restarting the bot registers `/match`. The bot needs **Send Messages**, **Embed Links**, and **Attach Files** permissions in the posting channel.

The scoreboard uses the shared metadata service. Cheap sources are tried on every request: the local cache, stored public API metadata (`disable_steam=true`), and any Valve download reference the bot already holds (reused for 24 hours while Valve finishes publishing the file). Only on a miss does the bot spend scarce Steam lookups: first this bot's budgeted GC query, then, when the GC cannot be asked (budget, cooldown, no Steam account) or cannot answer, deadlock-api's own Steam-backed lookup (`DEADLOCK_API_STEAM_FALLBACK=true`, paced to its quota of three per hour per IP, or 300 with `DEADLOCK_API_KEY`). It is skipped when Valve already said the match is unpublished or invalid. The two quotas are separate, and a 429 from the Steam-backed quota never pauses stored lookups. `/match` without an ID uses the recent-match lookup for the linked account. Signup, `/steambot`, metadata retries and Steam player-name lookup remain in place.

GC metadata queries target a registered participant of the match, preferring players who are the bot's Steam friends, and fall back to an untargeted query, as deadlock-api sends. Whoever clicks **Open full match** does not matter, so unregistered viewers of a tracked player's match still get the report; a viewer is never assumed to have played the match, so an untracked match ID is queried untargeted, from the unreserved share of the budget. Valve's answer is classified: a denied participant is not asked again for that match, an invalid match stops all Steam lookups, and "in flight" or unpublished files are reported as still processing. A query that never left the bot, such as one waiting for the GC session, does not spend the budget. Valve is asked about an unfinished match again only after 1, 3, 10, 30 and 60 minutes, at most six times, while the cheap sources are re-checked after 15 s, 30 s, 1 min and so on. On-demand commands and buttons skip the background retry delay, so pressing the button again re-checks the public archive immediately. When metadata is still missing, `/match` explains why: still processing, waiting for the next paced lookup (with an estimate), access denied, invalid match, or not in the public archive.

Automatic notifications request metadata as soon as a match is discovered and post the moment it arrives. If it has not arrived within 60 seconds, the basic summary is posted and later edited in place once full metadata arrives (for up to 24 hours, also after a restart).

When Valve responds with `k_eResult_RateLimited`, the Steam session pauses both history and metadata queries across all accounts and matches. The cooldown starts at one minute and doubles on repeated throttling, up to 15 minutes. Cached data and the public API fallback remain available. A successful retry of the throttled endpoint resets the backoff; restarting the bot is not required. This reduces repeated requests but cannot change Valve's quota.

Fresh GC metadata queries use a rolling budget: at most `STEAM_METADATA_DAILY_LIMIT=40` per 24 hours, at least `STEAM_METADATA_INTERVAL_SECONDS=20` apart, the same policy deadlock-api's ingest tool applies per Steam account. A quarter of the budget is reserved for registered players' matches. Sent queries are stored in PostgreSQL, so restarts cannot overspend it. A `k_eResult_RateLimited` answer to a metadata query additionally pauses fresh metadata queries for one hour, then six, then 24 hours if it repeats. The budget does not block GC history or stored reports. Valve's real quota is unpublished; this is a client policy. A deadlock-api key raises the separate Steam-backed quota used when the GC budget is spent. See [metadata retrieval research](docs/metadata-retrieval.md) for sources, alternatives, and limitations.

For the NAS, merge the reviewed PR and wait for the main container image to publish, then run:

```sh
cd /home/abdullah/deadlockbot
python3 scripts/update.py
```

See [DEPLOYMENT.md](DEPLOYMENT.md) for deployment setup, checks and rollback.

Run `./gradlew test` to compile and execute the offline regression suite. The tests cover real metadata structure, incomplete stats, final snapshot selection, sold inventory events, 12-player scoreboards, other team sizes, and notification formatting. Tests also write a synthetic layout preview to `build/scoreboard-preview.png`.

Public data sources: [match metadata API](https://api.deadlock-api.com/docs), [metadata protobuf definitions](https://github.com/SteamDatabase/Protobufs/blob/master/deadlock/citadel_gcmessages_common.proto).
