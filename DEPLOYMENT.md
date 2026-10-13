# Home-lab deployment (Linux Mint / x86_64)

CI tests and builds on PRs without publishing. After a reviewed merge to main,
CI publishes linux/amd64 to ghcr.io/abdullah2808/deadlockbot as `latest` and the
full commit SHA. Wait for that workflow to succeed before deploying. No image
from this PR is published before merge. Prefer full SHA tags for reproducibility.

## Inspect nas first

Run from your own machine (`nas` is your SSH alias):

```sh
ssh nas
hostnamectl
uname -m
free -h
df -h / /home /srv/data/docker
docker info --format '{{.DockerRootDir}}'
containerd config dump | grep '^root'
docker compose version
docker ps --format 'table {{.Names}}\t{{.Image}}\t{{.Status}}'
```

Check `gh auth status` if using GitHub CLI on nas. Stop any older bot instance
elsewhere (for example Railway) before starting this one to avoid duplicate posts.
Leave the Cinnamon/Chrome/Claude Desktop session alone.

Confirm Docker data is on the HDD at /srv/data/docker.
On installations using the containerd image store, its root must also be on the
HDD (for example /srv/data/containerd). Docker's data-root does not relocate it.
If pulls fail under /var/lib/containerd with no space left, inspect both storage
paths before retrying; do not remove runtime data to make room.

Keep application files,
state and backups under /srv/data/stacks (backed by /home/srv), not the nearly full OS disk. The reported
8 reallocated HDD sectors warrant monitoring and off-host backups; do not keep
the only backup on that HDD. This session did not inspect or change nas.

## First deployment

On nas after merging and successful image publication:

```sh
umask 077
mkdir -p /srv/data/stacks
cd /srv/data/stacks
git clone https://github.com/abdullah2808/DeadLockBot.git deadlockbot
cd deadlockbot
cp .env.example .env
mkdir -p state backups
chmod 700 state backups
chmod 600 .env
nano .env
```

Fill DISCORD_TOKEN, DB_USER and a strong DB_PASSWORD locally. Set IMAGE_TAG to
the full published main commit SHA. Initially leave all Steam credentials empty: the dedicated account does not yet
have Deadlock access, so deploy the active-feed fallback first. Once it has
access, set STEAM_USERNAME and follow the bootstrap below. Leave password and
refresh-token empty for normal unattended operation.
Do not paste credentials into chat or shell command arguments. Compose supplies
`postgresql://db:5432/main`; the DB has no published host ports. Do not use
`docker compose config` without `--quiet`, because its output includes secrets.

For a private GHCR package, authenticate using a read:packages token entered at
Docker's password prompt (never commit or paste it into commands):

```sh
docker login ghcr.io -u abdullah2808
docker compose config --quiet
docker compose pull
docker compose up -d --no-build
docker compose ps
```

Run the live acceptance checks below for the active-feed deployment. Bootstrap
Steam only once the dedicated account has access.

## Interactive Steam Guard bootstrap

Ensure the Steam account has Deadlock access. Stop the normal app, edit .env to
set STEAM_PASSWORD temporarily, then run the app in the foreground with a TTY:

```sh
docker compose stop app
nano .env
docker compose run --rm app
```

Answer Steam Guard on the terminal. Wait for the saved-token message and GC
welcome, then Ctrl-C. The token is saved under `state/steam_refresh_token.txt`.
If changing STEAM_TOKEN_FILE, keep its container path under /app/state.
Remove STEAM_PASSWORD and leave STEAM_REFRESH_TOKEN empty in .env. Never copy
the saved token into chat. Verify the file exists without displaying it:

```sh
test -s state/steam_refresh_token.txt
chmod 600 state/steam_refresh_token.txt
nano .env
docker compose up -d --no-build app
```

Username plus the saved file selects the GC source without a password. A revoked
or expired token requires repeating bootstrap; failure is not evidence of GC
readiness. File write failures prevent claiming bootstrap success. The image
currently runs as root, so a root-owned token may require sudo for chmod or
backup. Protect the entire state directory and backups.

## Steam account inputs

`/signup` and `/recentmatch` accept a numeric account ID, a 17-digit individual
SteamID64, a numeric Steam profile URL (`steamcommunity.com/profiles/...`), or
a vanity profile URL (`steamcommunity.com/id/...`). Numeric inputs are converted
locally. Vanity names resolve through Steam Community's public XML profile
response with a ten-second timeout; no additional Steam Web API key is needed.
If Steam cannot return the profile identity, use the numeric SteamID64/account
ID or retry later. Signup still separately verifies Deadlock history, and stores
the resolved numeric account ID so polling does not repeat vanity lookups.

## Updates and rollback

After the PR is merged and main's publish job succeeds, bootstrap the updater
from your existing repository directory:

```sh
git fetch origin
git switch main
git pull --ff-only origin main
python3 scripts/update.py
```

For subsequent registry updates, the only command is:

```sh
python3 scripts/update.py
```

The updater fetches main's SHA, downloads its published image before changing
the running app, checks out matching configuration, and sets IMAGE_TAG itself.
If publication is still pending or registry login is missing, it leaves the app
running and asks you to retry. Authenticate to private GHCR once as described
above. It requires an existing running app, Python 3, Git, Docker and Compose.
Run it as your usual Docker-capable user. It leaves the checkout detached at the
deployed SHA; use the updater again instead of git pull for registry deployments.

It retains the previous image under a local rollback tag and detects startup
exits/restarts over ten seconds. A failed update attempts automatic rollback.
To roll back manually:

```sh
python3 scripts/update.py --rollback
```

Only IMAGE_TAG is changed in .env; other settings, database volumes, and Steam
state are preserved. The rollback record contains no credentials and is stored
under backups/deployment. Keep the previous local rollback image until the new
version passes live acceptance. These checks verify the container stays running;
inspect Steam/GC welcome and Discord notifications as well. Image rollback does
not undo database migrations, so continue taking backups before updates.

For PR testing before an image is published, use a local image tag in .env
(`IMAGE_TAG=local-test`) and build the checked-out branch. The same commands
apply when updating a local test installation after pulling new commits:

```sh
git pull --ff-only
docker compose config --quiet
docker compose build app
docker compose up -d --no-build app
docker compose logs --tail=100 app
```

To start an existing installation, run `docker compose up -d --no-build` from
its repository directory. To stop only the bot, use `docker compose stop app`.
The saved Steam token and database survive these operations.

Hero and rank catalogs use https://api.deadlock-api.com/v1/assets. They refresh
after six hours, or when an ID is missing with at most one request per minute.
Failed refreshes retain the previous catalog; rank assets can be omitted during
an outage. Existing Discord messages are not edited by a catalog refresh; rerun
/lastfive to see updated names. An HTTP 503 fetching metadata means the upstream
API could not retrieve that match from Steam, even if the bot's GC login succeeded.
GC match history supplies complete basic summaries when available. The bot
retains up to 25 matches per account in PostgreSQL, sharing one match record and
one metadata document across all participants, with separate player stat rows.
Account trimming preserves matches referenced by another account. Scheduler and
commands reuse GC snapshots for up to 30 seconds; successful metadata is also
cached by match ID. /recentmatch and /lastfive use GC history when configured,
and identify missing details instead of presenting stale API history as current.
Basic notifications can be delivered without external metadata; damage/healing
and team rank are optional enhancements. HTTP 429 honors Retry-After and a
shared cooldown. Missing basic data remains pending with retry delays capped at
15 minutes, rather than being discarded after five failures.

Back up first. Record the current IMAGE_TAG privately. Pull the reviewed main
configuration, then edit IMAGE_TAG to the new full SHA:

```sh
git pull --ff-only origin main
nano .env
docker compose config --quiet
docker compose pull app
docker compose up -d --no-build app
docker compose ps
```

To roll back, set IMAGE_TAG to the previous full published SHA and run the last
four commands again. The database and state remain mounted. An image rollback
does not undo schema changes: retain matching config and backup for any future
migration. Do not use `down -v`, which deletes the database. Prune only unused
images after acceptance (`docker image prune`); avoid broad volume pruning.

## Backups and restore

On nas in the project directory (umask protects credential-bearing archives):

```sh
umask 077
stamp=$(date -u +%Y%m%dT%H%M%SZ)
docker compose exec -T db sh -c 'pg_dump -U "$POSTGRES_USER" -d main -Fc' > "backups/main-$stamp.dump"
docker compose stop app
sudo tar -czf "backups/state-$stamp.tgz" state .env
docker compose up -d --no-build app
sudo chmod 600 "backups/state-$stamp.tgz"
```

Check command exit statuses and archive sizes. Copy backups to another machine
or drive with restricted access; .env and state contain credentials. Test restore
on an isolated stack before relying on backups. A restore to the production DB
is destructive: stop app, retain a fresh backup, then use pg_restore with
`--clean --if-exists` only after choosing the intended dump. For example:

```sh
docker compose stop app
docker compose exec -T db sh -c 'pg_restore -U "$POSTGRES_USER" -d main --clean --if-exists' < backups/main-CHOSEN_TIMESTAMP.dump
docker compose up -d --no-build app
```

Restore the matching state archive privately while app is stopped. Changing
DB_PASSWORD in .env does not change an existing PostgreSQL user's password;
coordinate that separately rather than deleting the volume.

## Live acceptance (required before calling deployment verified)

- `docker compose ps` shows healthy DB and running app. Check bounded logs locally
  with `docker compose logs --tail=100 app`; review/redact before sharing.
- Confirm no host DB port: `docker compose port db 5432` should return no mapping.
- GC logs show stored-token login and Deadlock welcome after a restart with no
  password. Steam authentication and wire behavior require this real account test.
- Use /signup in an accessible Discord text channel; finish a real match and
  confirm the correct notification and persisted last_match_id. Polling is 90s
  plus metadata availability. Remove send permission, finish another match,
  then restore permission and confirm retry sends it without premature persistence.
- Restart app and verify the same match is not reposted normally; restart the
  stack and confirm database registrations and Steam token survive.
- Check resource use with `docker stats --no-stream`, disk space and restart counts.
  App limit is 1 GiB with 512 MiB heap; DB limit is 512 MiB. Adjust only after
  measuring, leaving room for Mint, Chrome and VNC on the 8 GB host.

GC notifications discover only the newest match per account, while commands can
show recent GC summaries. Detailed metadata still comes from deadlock-api.com.
Reconnect readiness and late-response correlation remain
known gaps. Sending before persistence can duplicate a notification if the
process crashes or DB persistence fails after Discord accepts it; delivery is
not exactly once. Missing basic data is retained for retry; send errors retain the
pending match for retry. No Steam/Discord/API live checks are part of offline CI.

## Build verification

Use `bash gradlew test shadowJar --no-daemon` with Java 21 and
`docker build --platform linux/amd64 -t deadlockbot:local .`. The Dockerfile also
accepts optional GRADLE_OPTS proxy JVM properties and a BuildKit `proxy_ca`
secret for managed environments; the temporary trust store is removed in the
build step and is absent from the runtime image. Do not pass credentials as
build arguments.
