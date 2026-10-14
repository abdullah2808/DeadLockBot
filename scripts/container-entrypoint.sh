#!/bin/sh
set -eu
# All production instances must mount the SAME local state directory. Never
# delete/replace this file while a bot runs: locks belong to the inode.
test -d /app/state || { echo 'Missing persistent /app/state directory' >&2; exit 1; }
umask 077
# -F replaces flock with the JVM, retaining the lock and normal signal handling.
exec flock --nonblock --no-fork /app/state/.bot.lock "$@"
