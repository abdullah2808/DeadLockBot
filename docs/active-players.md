# Active players and match alerts

`/active` shows who is playing among the players tracked in the current channel. `/matchalerts` opts a channel into a post when one of them appears in a live match. Neither can see every match, for the reasons under [Coverage limits](#coverage-limits).

## `/active`

The reply is private unless you set `public:True`. Anyone in the channel can run it. Each player gets their best known status:

| Status | What it means | Evidence |
| --- | --- | --- |
| In a listed match | Hero, team, elapsed time, mode and team souls, grouped by match | Valve's Watch list |
| In Deadlock, match unknown | Steam shows the game open, which could be the menu or a match | Steam presence, for Steam friends of the bot |
| Not seen | Steam doesn't show them in Deadlock, or the bot can't see their Steam status | Absence from both sources |

"Not seen" never says a player is offline. Someone can be in an unlisted match, and Steam also hides the game for friends who appear invisible.

Illustrative reply:

```text
Tracked players in this channel

In a listed match
🏅 Ranked · ⏱ 12:05 · Souls: Amber 20,000, Sapphire 26,500 · Match `55`
• Alice: Haze for Amber
• Bob: Wraith for Sapphire

In Deadlock, match unknown
Steam shows they have the game open, which doesn't prove they're in a match.
• Carol

Not seen
They may still be playing, because the live list features only some games.
• Dan: Steam doesn't show them in Deadlock
• Erin: the bot can't see their Steam status
```

`/active` uses the same 60-second live list as `/live` and the Steam presence the bot already holds, so repeating it within a minute sends no extra Game Coordinator (GC) requests.

## `/matchalerts`

Alerts are off until a channel opts in, because they post without anyone asking.

```text
/matchalerts action:enable
/matchalerts action:disable
/matchalerts action:status
```

- **Who can change them:** a player tracked in that channel, or anyone with Manage Channels. Anyone can check the status. Replies are private.
- **What posts:** one message per match per channel, naming that channel's tracked players in the match. Players who have already left are not named, and don't trigger a post on their own. The message pings nobody.
- **Late joiners:** a tracked player who appears in an already announced match doesn't get a second post.
- **Timing:** checks run once per source poll, at least 90 seconds apart. An alert says "Started 3 minutes ago" rather than "just started", because a match can join Valve's list well after it begins.
- **Restarts:** announced matches are stored in `live_alert_posts` for 24 hours, so a restart or turning alerts off and on doesn't repeat one.
- **Failed posts:** a temporary failure is retried on the next poll. If the bot can't post to the channel (HTTP 403 or 404), it doesn't retry for that match.

Each match is recorded before it is posted, because a repeated unprompted post is worse than a missed one. A crash or shutdown in the middle can lose that one alert. One rare repeat remains possible: a network failure reported after Discord actually accepted the post.

### Request cost

Nothing extra is requested unless an opted-in channel has a tracked player. When one does, the scheduler reads the shared live list once per source poll, which is at most one extra request every 90 seconds, shared with `/live` and `/active`. Without Steam credentials, that request goes to deadlock-api instead.

An unanswered GC request resets the shared Steam session. So after a GC failure, alert checks use deadlock-api's copy of the list and don't ask the GC again for two hours. Commands can still ask it, subject to the GC client's own 30-minute pause.

### No "launched Deadlock" alerts

The bot does not post when Steam presence changes. Presence can't tell the menu from a match, opening the game isn't news a channel asked for, it only covers Steam friends of the bot, and reconnecting can look like everyone launching at once. `/active` shows the same information when someone asks.

## Coverage limits

- **Only listed matches are detected.** Valve's Watch list features only some live games, so a match outside it never appears in `/active` and never alerts. One sample of the public copy had 150 matches and 1,676 players.
- **Detection can be late.** The youngest match in that sample was about 3 minutes old. That is one snapshot, not a measured delay.
- **Hero level isn't available.** Each listed player has only `account_id`, `team`, `hero_id` and `abandoned`. Team souls and match start time are per match.
- **The list can lag.** It is cached for up to 60 seconds, so a match that just ended can still show briefly.
- **Steam presence is limited to Steam friends of the bot** and can't tell the menu from a match.

## Steam rich presence: promising, not used

Rich presence may remove the biggest limit, because it could show a Steam friend's match state without the Watch list. The bot doesn't use it yet, because nothing below has been checked against a real friend in a real match.

**Found in Valve's game files**, as tracked by [SteamDatabase/GameTracking-Deadlock](https://github.com/SteamDatabase/GameTracking-Deadlock) on 2026-10-30:

- `client_strings.txt` includes `CCitadelRichPresence`, `SteamSetRichPresence`, `steam_display` and `#Steam_RP_%s`, so the current client appears to set rich presence.
- `citadel_gc_english.txt` has separate strings for the main menu (`Steam_Citadel_RP_MainMenu`), a party hideout (`Steam_Citadel_RP_InParty`), finding a match (`Steam_Citadel_RP_FindingMatch`) and mode prefixes such as `Steam_Citadel_RP_MM_Ranked` and `Steam_Citadel_RP_StreetBrawl`.
- The same file has `Steam_Citadel_InGame_Hero` with the template `{%param1%} {%param2%} (%param0% min)`. It reads as mode, hero and minutes, but that reading is an inference.
- No rich presence string shows a hero level. `hero_level` does appear in the client's strings, but nothing connects it to rich presence.

**Found in JavaSteam 1.8.0:**

- `PersonaStateCallback.getRichPresence()` returns the key/value list, and `SteamFriends` doesn't cache it, so the bot would have to subscribe and keep the values itself.
- The default persona flags are `PlayerName`, `Presence`, `SourceID`, `GameExtraInfo` and `LastSeen`. `RichPresence` isn't included, so it has to be requested with `withDefaultPersonaStateFlags` or `requestFriendInfo(id, flags)`.
- `CMsgClientRichPresenceRequest` and `CMsgClientRichPresenceInfo` are also available as a direct request and response.

**Still unknown:**

- Whether Steam sends these values to a bot account.
- The actual keys and values, and whether the hero is a readable name.
- Whether the values update during a match.

Checking takes a logged-in bot account and a friend in a match. It wasn't attempted here because logging in could disturb the deployed bot's Steam session.

## Setup

Restart the bot to register `/active` and `/matchalerts` and to create the `live_alert_channels` and `live_alert_posts` tables. No configuration is needed.
