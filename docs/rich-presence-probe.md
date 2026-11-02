# Steam rich presence probe

This diagnostic answers one question: does Steam tell the bot account what a friend is doing in Deadlock? If it does, the bot could see a friend's match without Valve's Watch list.

It only writes to the logs. No command or alert uses these values, and it is off unless `STEAM_RICH_PRESENCE_PROBE=true`.

## What it does

While it's on, every 30 seconds the bot asks Steam about tracked Steam friends who are in Deadlock, in two ways:

| Log label | How it asks |
| --- | --- |
| `persona` | Friend data requests with the `RichPresence` flag, which also covers updates Steam pushes |
| `rich-presence-info` | `ClientRichPresenceRequest`, answered by `ClientRichPresenceInfo` |

These are Steam requests, not GC requests, so they don't use the GC metadata budget. Other Steam friends are never logged, and a line is written only when a value changes. `connect` values are replaced with `[redacted]` because they can let someone join a game.

## How to run it

The bot account can only hold one session, so run this on the deployed bot, not a second copy.

1. Deploy a build that includes the probe.
2. Add `STEAM_RICH_PRESENCE_PROBE=true` to `.env`, then restart the app:

   ```sh
   docker compose up -d --no-build app
   ```

3. Check that the logs contain `Rich presence probe is on`.
4. Use a tracked account that is a Steam friend of the bot, and has agreed to the test. Spend about a minute in each state, and note the times:
   - main menu
   - party hideout, if you can
   - searching for a match
   - in a match for at least 5 minutes, ideally ranked, to see whether the minutes change
   - back at the menu after the match
   - game closed
5. Collect the results:

   ```sh
   docker compose logs app --since 3h | grep "Rich presence probe"
   ```

6. Turn it off by setting `STEAM_RICH_PRESENCE_PROBE=false` and restarting.

The output includes account IDs and whatever the game reports, so share it only with people who should see that.

## Reading the results

| What the logs show | What it means |
| --- | --- |
| `asking about 0 tracked friends` throughout | The test friend wasn't seen in Deadlock, so nothing was tested. Check `/steambot` and that they aren't invisible on Steam. |
| Asking about 1 or more, with only `(none)` or no answers | Steam isn't giving this bot rich presence by that route. |
| Values only in the menu or hideout | Rich presence can't detect matches. |
| Values that change when a match starts | Rich presence can detect matches, so `/active` could use it. |

Also check whether the hero is readable or an ID or token, whether the minutes update, and whether any hero level appears.

## Expected values

Deadlock's localization files suggest a `steam_display` value naming a string such as `#Steam_Citadel_InGame_Hero`, whose template `{%param1%} {%param2%} (%param0% min)` reads as mode, hero and minutes. These names are unconfirmed until a run shows them.
