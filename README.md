# DeadlockBot

A Kotlin Discord bot that tracks Deadlock matches and posts match recaps.

## Match commands

- `/match 103328445` posts a full match scoreboard image in the current channel. Select `/match` and paste the numeric ID into its `match_id` option if your client does not accept the copied command directly.
- `/match` shows the latest match for your account linked through `/signup`.
- `/recentmatch account_id:<id>` posts a player recap. Account IDs, SteamID64s, and numeric Steam profile URLs are accepted.
- `/lastfive` posts the latest five matches for your linked account.
- `/unsubscribe` stops automatic tracking.

The scoreboard groups all players by team and displays heroes, K/D/A, souls, player damage, objective damage, healing, team soul totals, and final shop items. The latest recorded stats snapshot supplies damage and healing. Sold items and hero ability upgrades are excluded from the inventory. Longer inventories wrap onto a second row. Your row is outlined when viewing your latest linked match.

`/match` also attaches a standalone `.html` report. Download it and open it in a browser to hover/focus heroes for ability descriptions, hover/focus/tap items for descriptions and properties, select a player, and filter/scrub their purchase, sale and ability-event timeline. Images and scripts are embedded; no hosting or internet connection is needed to view the file. The PNG in Discord remains a static preview.

Progression preserves recorded event order and times, including sold items. Ability events show which ability was upgraded and when, retaining raw upgrade IDs without guessing tier numbers. Events sharing a timestamp keep their original metadata order. Descriptions come from the current asset catalog, so historical patch values may differ. Older cached matches may lack progression or upgrade details; the report states when data is unavailable.

Player names are used if metadata supplies them; otherwise the scoreboard displays account IDs. Missing damage/healing stats display `—`. Hero and item images use the public Deadlock assets API, with cached images and fallbacks during outages. If full match metadata is unavailable or still processing, the command explains this instead of producing an incomplete scoreboard. Metadata cached by older bot versions may lack inventory; those rows show `Unavailable` for items.

Automatic notifications include result colors, hero thumbnails, K/D/A, souls per minute, farming stats, damage and support stats when available, and a command to open the full scoreboard. The scheduler records a notification as delivered after Discord accepts the message.

## Run and verify

The Dossier timeline also includes recorded kills and deaths. Select **Kills & deaths**, **Kills only**, or **Deaths only** in the event filter. Victim death records identify attackers by player slot, so kills show the victim's hero and deaths show the attacker's hero when identifiable. Unknown/ambiguous attackers remain labelled unavailable, and self-inflicted deaths do not create kill events. Older cached metadata may lack combat records; the report labels incomplete coverage instead of inventing timestamps from final K/D/A totals.

The HTML report uses hero names only and renders every participant supplied by metadata. Team banners show their actual hero counts and soul totals; there is no four-player cap. The original preview used an eight-player match. Standard 12-player matches show six heroes on each team.

The design keeps Deadlock's dark, brass-and-cream styling and amber/sapphire team banners. Item category colors, upgrade-component information, disabled-property filtering and ability-scaling/upgrade-effect formatting are adapted from [Soul Ledger](https://github.com/abdullah2808/Soul-Ledger). Prices and effects use the live asset catalog; simulator investment totals are not presented as observed match stats.

Startup replaces this application's complete global command list with the six commands above, automatically removing obsolete global commands. Commands registered specifically to an individual server have a separate list and need separate cleanup; commands belonging to other Discord applications are unaffected.

Startup also clears this application's legacy server-specific commands in server `875604309180559383`. It checks the server command list first and skips the overwrite once the list is empty. The six global commands remain available; other servers and applications are unaffected. A cleanup access error is logged and retried on the next startup without preventing the bot from running.

Use JDK 21. Configure the existing environment variables required by `Env` and the database, then run `./gradlew run` (`.\gradlew.bat run` on Windows). Restarting the bot registers `/match`. The bot needs **Send Messages**, **Embed Links**, and **Attach Files** permissions in the posting channel.

The scoreboard uses the existing shared metadata service: Steam GC/Valve metadata when available, then the public API fallback. `/match` without an ID uses the existing recent-match lookup for the linked account. Signup, `/steambot`, the notification grace period, metadata retries and Steam player-name lookup remain in place. Valve decoding now retains item events as well as final stats.

For the NAS, merge the reviewed PR and wait for the main container image to publish, then run:

```sh
cd /home/abdullah/deadlockbot
python3 scripts/update.py
```

See [DEPLOYMENT.md](DEPLOYMENT.md) for deployment setup, checks and rollback.

Run `./gradlew test` to compile and execute the offline regression suite. The tests cover real metadata structure, incomplete stats, final snapshot selection, sold inventory events, 12-player scoreboards, other team sizes, and notification formatting. Tests also write a synthetic layout preview to `build/scoreboard-preview.png`.

Public data sources: [match metadata API](https://api.deadlock-api.com/docs), [metadata protobuf definitions](https://github.com/SteamDatabase/Protobufs/blob/master/deadlock/citadel_gcmessages_common.proto).
