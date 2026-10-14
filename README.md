# DeadlockBot

A Kotlin Discord bot that tracks Deadlock matches and posts match recaps.

## Match commands

- `/match match_id:103328445` posts a full match scoreboard image in the current channel.
- `/match` shows the latest match for your account linked through `/signup`.
- `/recentmatch account_id:<id>` posts a player recap. Account IDs, SteamID64s, and numeric Steam profile URLs are accepted.
- `/lastfive` posts the latest five matches for your linked account.
- `/unsubscribe` stops automatic tracking.

The scoreboard groups all players by team and displays heroes, K/D/A, souls, player damage, objective damage, healing, team soul totals, and final shop items. The latest recorded stats snapshot supplies damage and healing. Sold items and hero ability upgrades are excluded from the inventory. Longer inventories wrap onto a second row. Your row is outlined when viewing your latest linked match.

Player names are used if metadata supplies them; otherwise the scoreboard displays account IDs. Missing damage/healing stats display `—`. Hero and item images use the public Deadlock assets API, with cached images and fallbacks during outages. If full match metadata is unavailable or still processing, the command explains this instead of producing an incomplete scoreboard. Metadata cached by older bot versions may lack inventory; those rows show `Unavailable` for items.

Automatic notifications include result colors, hero thumbnails, K/D/A, souls per minute, farming stats, damage and support stats when available, and a command to open the full scoreboard. The scheduler records a notification as delivered after Discord accepts the message.

## Run and verify

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
