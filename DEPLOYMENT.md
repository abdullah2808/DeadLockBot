# Manual production releases: Lenovo M910q / Linux Mint

This is a prepared runbook, not a record of server changes. No server access,
disk layout, Coolify version or live Steam/Discord behavior has been verified.
Do not execute the cutover without the owner's explicit production approval.

GitHub Actions tests PRs and builds linux/amd64. Reviewed main commits publish
full-SHA tags to GHCR; reruns reuse an existing tag. There is no `latest` update,
deploy webhook, SSH job, Tailscale credential or home runner in CI. Tags are a
convention, not a registry immutability guarantee: production uses the **digest**
reported in the successful publish job summary. A push or merge does not deploy.

Coolify's manual **Deploy** button is the production approval. This is a single
operator UI gate, not a GitHub Environment required-reviewer gate. Restrict
Coolify deploy rights to trusted operators; do not create API tokens or hooks for
automation. GitHub environment approval alone does not constrain Coolify.

## Architecture and limits

Only the bot moves to Coolify. The original Compose project keeps its running
PostgreSQL 17.7 container, actual named volume and existing network. The Coolify
file intentionally contains **no DB service and no volume declarations**. Its
external network reaches the existing `db` alias. Never import the original
two-service Compose file into Coolify or create a new Coolify database.

The bot uses the original `.env` as an absolute `env_file` and the original
`state` directory as an absolute bind mount. Coolify's own generated `.env` is
separate. No secrets are copied into GitHub or changed by CI. An incorrect state
path fails instead of creating an empty directory. The token path is fixed to
`/app/state/steam_refresh_token.txt`; reconcile any previous custom path first.

One bot needs a brief maintenance interruption during cutover and each update.
Zero downtime with no overlapping Steam/Discord session is not available with
this architecture. PostgreSQL remains running. Pre-pull before stopping the bot;
disable rolling/parallel/preview deployments. The fixed container name prevents
scaling this resource, and new images take an OS file lock in the shared state
directory. Old images do not have that lock: disabling their restart and all
other launch paths remains essential. Never delete `.bot.lock` while running.

## 1. Inventory and storage gates (read-only first)

From the **existing installation directory** on the server, record the exact
directory, original Compose project, deployed commit, app image ID/digest, DB
container ID, DB mount name/path, DB network and state source path privately:

```sh
pwd -P
git rev-parse HEAD
docker compose ps
docker compose ps -q app
docker compose ps -q db
docker inspect --format '{{.Image}} {{json .Config.Labels}}' "$(docker compose ps -q app)"
docker inspect --format '{{json .Mounts}}' "$(docker compose ps -q db)"
docker inspect --format '{{json .NetworkSettings.Networks}}' "$(docker compose ps -q db)"
docker inspect --format '{{json .Mounts}}' "$(docker compose ps -q app)"
docker info --format '{{.DockerRootDir}}'
findmnt -T /srv/data
findmnt -T /srv/data/docker
df -h / /srv/data
df -i / /srv/data
docker system df
docker compose version
```

Do not assume the volume is literally `deadlock_db_data`: Compose normally
prefixes it with the project name. Keep that exact volume and project. Confirm
the selected network contains only one `db` alias pointing at this DB, and that
the DB is healthy, registrations exist, and the state file is present (use
`test -s`, never print tokens). Do not print full container environments or
`compose config` without `--quiet`. Do not source `.env` as shell code.

Stop here unless `/srv/data` is the mounted HDD and Docker's data root is on it.
Inspect the active containerd configuration too: a separate image store under
`/var/lib/containerd` can fill the OS disk even with Docker data-root relocated.
Use `findmnt -T` on both actual roots. Changing either root requires a separately
planned, backed-up maintenance window; this runbook does not move a running DB
or restart Docker. Leave the desktop/VNC session intact. Earlier documentation
mentioned HDD reallocated sectors; inspect current SMART health and keep off-host
copies regardless. A path existing is not proof the HDD is mounted.

Require at least 5 GiB free on `/` and 10 GiB on the HDD before pulls, plus enough
space for dump + state backup + candidate + previous image. Increase these
minimums for actual sizes; stop below 15% free space or on inode exhaustion.
Build only in GitHub, never on this 8 GB host. Alert daily on disk/inode usage,
SMART changes and backup failures. Bound Coolify logs with the supplied override,
set a journald size limit during host maintenance, and review Coolify backup/log
retention. Disable automatic Docker/image cleanup until rollback retention is
configured. Keep current and previous digest images; remove only specifically
reviewed older app images. Never run volume prune, system prune with volumes,
`compose down -v`, or delete Docker/containerd directories.

## 2. Private Coolify first-time setup (before bot migration)

Linux Mint is not explicitly listed in Coolify's supported OS table. Inspect its
Ubuntu base, existing Docker installation and resources; use the official manual
installation procedure and test on a disposable machine with the same versions.
Do not run the convenience installer blindly on this occupied host: it can alter
Docker/networking and starts with broadly bound dashboard ports.

1. Install/enroll Tailscale on the host and clients using its official Linux
   instructions. Record `tailscale ip -4`. Use device approval/MFA and tailnet
   policy grants for your admin identity to this host's TCP 8000, 6001 and 6002;
   remove any default broad grants that would bypass that restriction. Do not
   enable Funnel, subnet routing or router port forwarding for administration.
2. With `/srv/data` verified mounted, create `/srv/data/coolify` (root-owned,
   restricted). For a **fresh** `/data/coolify`, bind mount that directory at
   `/data/coolify` and persist the bind in `/etc/fstab`. Do not overlay an existing
   Coolify directory: stop and plan a backup/copy migration if one exists. Add
   systemd `RequiresMountsFor=/srv/data /data/coolify` to Docker during the planned
   host maintenance window; verify boot ordering with `findmnt` before enabling
   service startup. A missing HDD must fail closed, not write onto `/`.
3. Stage the official manual-install files/secrets under `/data/coolify/source`
   without starting them. Pin a reviewed Coolify release; do not track `latest`.
   This repo's override targets the **4.4+ layout** with realtime inside `coolify`;
   older releases use a separate realtime service and need a reviewed override
   for that service too. Do not guess the installed layout or run unmatched files.
   Docker Compose must support `!override` (2.24.4+).
4. Copy `deploy/coolify-dashboard.override.yml` to
   `/data/coolify/source/docker-compose.custom.yml`. Add `TAILSCALE_IPV4` with the
   actual host address to Coolify's source `.env`. Render the base + production +
   custom files **before startup**, using the official manual install command
   plus `-f docker-compose.custom.yml`; `config --quiet` must pass. Inspect only
   the rendered port mappings locally: all three must bind the Tailscale IPv4,
   with no wildcard/IPv6 bindings. Include the custom file on every startup and
   upgrade; re-audit after upgrades. Do not start the public proxy for this bot.
5. Finish the manual install with that merged configuration. Coolify DB/Redis
   named volumes live on the HDD because Docker root was verified there; the
   `/data/coolify` bind keeps configuration/SSH keys/backups on the HDD too.
   Create the first admin account immediately via `http://TAILSCALE_IP:8000`
   on an enrolled client. Traffic is protected by the Tailscale tunnel. Back up
   Coolify's source `.env` including APP_KEY, database, and SSH keys privately.
6. Apply a Docker-aware host firewall policy: allow admin traffic arriving on
   `tailscale0` only, with local/Coolify-to-host SSH allowed as required. UFW
   INPUT rules alone do not filter Docker-published ports. On the iptables
   backend use DOCKER-USER rules matching the original destination Tailscale IP
   and original ports 8000/6001/6002 to drop traffic from physical interfaces;
   persist and test them. On nftables use Docker's documented backend-specific
   policy instead. Do not blindly replace the existing firewall. Keep console
   access while testing SSH. Never publish Docker API 2375/2376 or PostgreSQL.
7. Validate dashboard, realtime and terminal from an authorized tailnet device.
   From a LAN client with Tailscale off and an outside network verify ports
   8000/6001/6002/80/443/5432/2375 are unreachable on LAN/public addresses,
   including IPv6; test the Tailscale address from an unauthorized tailnet user
   too. Check `docker ps` port mappings and `ss -lnt`. Reboot validation belongs
   in an approved maintenance window. Do not proceed if bindings, firewall,
   HDD mount ordering, or tailnet access restrictions are unverified.

## 3. Prepare the Coolify resource without deploying

Create a Git-based Docker Compose **Application** for this repository, using
`/deploy/compose.coolify.yml` as the Compose location. Use **Raw (deploy file
as-is)** so Coolify preserves container name, external network and mounts. Choose
the local Docker server; validate Coolify's SSH connection without changing the
existing SSH access. Grant GHCR read access to Coolify's actual pull identity if
the package is private; a login under your personal shell may not be used by
Coolify. Use a read:packages credential through its private registry settings.

Disable auto deployments, PR previews, scheduled deploys and rolling updates.
Do not create a webhook/deploy key with write access or a public domain/port.
Configure runtime variables in Coolify (these four contain no credentials):

| Variable | Value |
| --- | --- |
| `IMAGE_DIGEST` | `sha256:` + 64 hex characters from the successful publish summary |
| `BOT_ENV_FILE` | Existing absolute `.env` path on the HDD |
| `BOT_STATE_DIR` | Existing absolute state source path on the HDD |
| `EXISTING_DB_NETWORK` | Exact inspected existing DB Compose network name |

Keep the original `.env` and permissions unchanged. `DB_USER`, `DB_PASSWORD`,
`DISCORD_TOKEN` and Steam settings are read from that file, not regenerated in
Coolify. Confirm a custom STEAM_TOKEN_FILE is reconciled before proceeding.
Avoid storing Steam password/inline refresh token when saved-token login works.

Review the generated Compose locally: exactly one app, no build, DB, public
ports, new volumes or replica scaling; exact original state path, external
network, env_file and digest. Validate with Compose on the server without
printing secret values. Raw mode/env_file support must pass this check on the
chosen Coolify version. Record the resource settings and selected config commit.

Pre-pull the digest without running it, then inspect its OCI revision label and
compare to the successful main publish SHA. Retain the old image locally with a
private rollback tag. No temporary test container may use production Discord or
Steam credentials. Rehearse using disposable state/DB and test credentials only.

## 4. Backup and approved one-time handover

Take an initial off-host backup and rehearse restore before scheduling cutover.
For the final matching backup, explicitly approve the interruption, stop the
original bot first, and leave PostgreSQL running. Use the original directory
and original Compose project for every legacy command below. Set a private
`stamp` and use the existing backups directory on the HDD:

```sh
umask 077
mkdir -p backups
stamp=$(date -u +%Y%m%dT%H%M%SZ)
docker compose stop -t 60 app
docker compose exec -T db sh -c 'pg_dump -U "$POSTGRES_USER" -d main -Fc' > "backups/main-$stamp.dump"
sudo tar -czf "backups/state-$stamp.tgz" state .env
sudo chmod 600 "backups/state-$stamp.tgz"
sha256sum "backups/main-$stamp.dump" "backups/state-$stamp.tgz" > "backups/checksums-$stamp.txt"
```

Run each step only after the previous succeeds; if backup fails, abort cutover
and restart the original app. Do not treat a nonempty dump as proof of success:
check pg_dump's exit code and `pg_restore --list`. Copy the dump, state archive,
checksums, exact Compose/config commit, volume/network inventory and rollback
image reference to an **encrypted off-host** backup repository (e.g. restic to
SFTP/S3 or another machine over Tailscale). Verify checksums after transfer and
perform a restore drill. Set an off-host daily job, monitor failures, retain at
least 7 daily + 4 weekly copies and a pre-release copy; prune only after a newer
verified copy exists. A second directory on this HDD is not an off-host backup.
The archive contains live credentials: restrict access, never upload it to CI.

Before handover remove/disable any cron, systemd updater, Watchtower and other
bot copies (including Railway). Record their original settings. Mark the legacy
checkout and disable the stopped container's restart so a reboot cannot revive
it, while retaining it for rollback:

```sh
touch .coolify-managed
docker update --restart=no "$(docker compose ps -a -q app)"
```

The updated `scripts/update.py` refuses update/rollback when this marker exists.
Install that reviewed guard before cutover and disable old scheduled copies;
older checkouts will not honor it. Never run legacy `compose up` without a service
name after handover. Do not use `compose down`: it could remove the DB network.

Confirm the old app is stopped, no other bot runs, DB container ID/volume are
unchanged and healthy, and final off-host backup is verified. Only now use the
Coolify **Deploy** button for the explicitly selected digest. Do not start both
managers concurrently. If any check fails, stop and roll back as below.

## 5. Acceptance and future UI releases

In Coolify check runtime logs locally and restart count over several minutes.
Container running is not Steam/Discord readiness. Require saved-token Steam
login, GC welcome, correct `/steambot`, successful `/recentmatch`, existing
registrations and one expected match notification. Verify restart does not
normally repost a processed match. Check state mount/source, token file presence,
DB ID/volume, absence of published ports, memory, disk space and log rotation.
Check all bot processes across old/new platforms, not just container names.
The file lock protects new images sharing the same path, not remote copies or
legacy images. Delivery is not exactly once if persistence fails after sending.

For each later release: review successful main CI → record current digest and
config → take/verify off-host backup → pre-pull candidate → set `IMAGE_DIGEST` in
Coolify → explicitly **Stop** the current bot and wait until it has exited →
**Deploy** once → run acceptance. Keep deployment actions serial. Never click
Deploy while another operation is running. The DB stays outside Coolify's bot
lifecycle. If CI publishes while you're away, production remains unchanged.

## 6. Rollback and disaster recovery

For a bot release failure, Stop in Coolify, wait for exit, restore the previous
digest and matching reviewed Compose/settings, then Deploy manually. Check live
acceptance. Do not roll the schema back by guessing: inspect code for incompatible
DB changes before every release. Image rollback does not reverse schema/data.

For a failed initial handover, Stop the Coolify app first and confirm it exited.
If Coolify is unavailable, an authorized operator must stop its container via
the host console and set restart=no before reviving legacy. In the original
directory restore the recorded legacy config/image, remove `.coolify-managed`,
restore its previous restart policy, then start **only** the original app:
`docker compose up -d --no-build --no-deps app`. Never start DB from a new project.
Remove the unused Coolify app only after verifying it cannot remove the external
network or original bind sources. Leave existing DB volume untouched.

Rehearse disaster restore on a different isolated host/volume: verify dump
listing, restore using PostgreSQL 17 tooling, check registrations/row counts, and
restore the matching restricted `.env`/state archive while the bot is stopped.
Do not start a clone with production credentials during the drill. For an actual
production DB restore, obtain explicit approval, stop all writers, take a fresh
backup, verify the target volume/database and selected dump, then run pg_restore
with the intended overwrite options. Never restore two PostgreSQL servers onto
one data volume. A raw tar of a running PostgreSQL data directory is not a valid
replacement for pg_dump. Restore Coolify's DB together with its matching APP_KEY,
source `.env` and SSH keys; test on isolated storage before switching management.

Steam bootstrap, application behavior and the pre-Coolify commands are retained
in [LEGACY_COMPOSE.md](deploy/LEGACY_COMPOSE.md). Those launch/update commands are
for legacy ownership only. Keep the original DB project available for DB-only
operations and backups; a later DB migration is a separate approved change.

## References and verification boundaries

- [Coolify installation / supported OS and manual setup](https://coolify.io/docs/start-with-self-hosted)
- [Coolify Compose raw mode, external networks and mounts](https://coolify.io/docs/applications/builds/docker-compose)
- [Coolify dashboard ports and firewall](https://coolify.io/docs/core/infrastructure/servers/firewall)
- [Coolify upstream production layout](https://github.com/coollabsio/coolify/blob/v4.x/docker-compose.prod.yml)
- [Docker published ports and firewall behavior](https://docs.docker.com/engine/network/packet-filtering-firewalls/)
- [Tailscale Linux installation](https://tailscale.com/docs/install/linux)

CI validates both Compose files with disposable values, tests the updater and
singleton lock, runs JVM tests/shadowJar and builds the image. It does not access
the server or verify Coolify, HDD mounts, backups, tailnet policy, Steam or Discord.
