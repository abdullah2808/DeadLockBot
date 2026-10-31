# Stat comparison

Use `/compare` to put two players' recent matches side by side. The reply is private unless you set `public:True`.

| Option | What it does |
| --- | --- |
| `player` | A signed-up Discord user to compare. |
| `account_id` | Or any account ID, SteamID64 or Steam profile URL. Set this or `player`, not both. |
| `against` | The signed-up Discord user on the other side. Defaults to you. |
| `mode` | All modes (default), Ranked, Standard or Street Brawl. Ranked includes placement matches. |
| `matches` | Latest completed matches per player, from 5 to 40. Defaults to 20. |
| `public` | Posts the reply in the channel. |

Example layout, using sample values:

```text
                 Arrow       bay 
Games               20        20 
Win rate         80.0%*    50.0% 
KDA ratio         4.20*     3.50 
Kills              9.0*      7.0 
Deaths             5.0*      6.0 
Assists           12.0      14.0*
Souls/min         1086*      971 
Last hits/min      5.1       5.1 
Denies/min        0.40      0.40 
Damage/min         914*      771 
Healing/min        114       186*
```

A `*` marks the better value. Fewer deaths count as better, and values that print the same get no star. The reply also shows each player's most played hero and their shared matches: the record on the same team, and who won when they were on opposing teams.

## Where the data comes from

- **Signed-up players** use the bot's stored history, up to 40 matches, so comparing them spends no Steam lookups. If nothing is stored yet, they are looked up like any other account.
- **Other accounts** ask the Game Coordinator, then the public API, and use any older stored rows if both fail.

## Limits

- Rates are weighted by match minutes. Damage and healing only count matches that have that data, and the footer says when coverage is partial. Accounts looked up live often have no damage data.
- Shared matches only cover the history available for both players. A shared team does not prove they queued together.
- Heroes are mixed together, so the numbers describe recent form, not skill.

No schema change or configuration is needed. Restart the bot to register `/compare`.
