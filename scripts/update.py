#!/usr/bin/env python3
"""Update an existing Linux Compose installation to the published main image."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time

IMAGE = "ghcr.io/abdullah2808/deadlockbot"


class UpdateError(RuntimeError):
    pass


def set_tag(contents, tag):
    pattern = r"(?m)^[ \t]*(?:export[ \t]+)?IMAGE_TAG[ \t]*=[^\r\n]*"
    updated, count = re.subn(pattern, "IMAGE_TAG=" + tag, contents)
    return updated if count else contents.rstrip("\r\n") + "\nIMAGE_TAG=" + tag + "\n"


def write_private(path, contents):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", newline="", dir=path.parent, delete=False) as handle:
            temporary = Path(handle.name)
            os.chmod(temporary, 0o600)
            handle.write(contents)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        if temporary is not None and temporary.exists():
            temporary.unlink()


class Updater:
    def __init__(self, root, runner=None, sleep=time.sleep):
        self.root = Path(root)
        self.env_file = self.root / ".env"
        self.state = self.root / "backups" / "deployment"
        self.previous = self.state / "previous.json"
        self.runner = runner or self.run
        self.sleep = sleep

    def run(self, args, capture=False):
        environment = os.environ.copy()
        environment.pop("IMAGE_TAG", None)  # The selected value must come from this installation's .env.
        result = subprocess.run(args, cwd=self.root, env=environment, text=True, check=True,
                                stdout=subprocess.PIPE if capture else None)
        return result.stdout.strip() if capture else ""

    def command(self, *args, capture=False):
        return self.runner(list(args), capture=capture)

    def compose(self, *args, capture=False):
        return self.command("docker", "compose", *args, capture=capture)

    def preflight(self):
        if (self.root / ".coolify-managed").exists():
            raise UpdateError("Coolify owns the bot. Select an approved digest in its UI; do not run the legacy updater.")
        if not self.env_file.is_file():
            raise UpdateError("Create .env and complete initial setup first.")
        if self.command("git", "status", "--porcelain", "--untracked-files=no", capture=True):
            raise UpdateError("Tracked files have local changes. Commit or preserve them before updating.")
        self.state.mkdir(parents=True, exist_ok=True)
        os.chmod(self.state, 0o700)

    def snapshot(self):
        container = self.compose("ps", "-q", "app", capture=True)
        if not container or "\n" in container:
            raise UpdateError("Start the existing app before using the updater.")
        image_id = self.command("docker", "inspect", "--format", "{{.Image}}", container, capture=True)
        if not re.fullmatch(r"sha256:[0-9a-f]{64}", image_id):
            raise UpdateError("Could not identify the running app image.")
        tag = "rollback-" + image_id[7:23]
        self.command("docker", "tag", image_id, IMAGE + ":" + tag)
        return {"commit": self.command("git", "rev-parse", "HEAD", capture=True), "tag": tag}

    def start(self):
        self.compose("config", "--quiet")
        self.compose("up", "-d", "--no-build", "--no-deps", "app")
        container = self.compose("ps", "-q", "app", capture=True)
        if not container:
            raise UpdateError("The updated app stopped during startup.")
        before = self.command("docker", "inspect", "--format", "{{.State.Running}} {{.RestartCount}}", container, capture=True)
        self.sleep(10)
        after = self.command("docker", "inspect", "--format", "{{.State.Running}} {{.RestartCount}}", container, capture=True)
        if not after.startswith("true ") or after != before:
            raise UpdateError("The updated app is stopping or restarting during startup.")

    def restore(self, previous):
        self.command("git", "checkout", "--detach", previous["commit"])
        write_private(self.env_file, set_tag(self.env_file.read_bytes().decode("utf-8"), previous["tag"]))
        self.start()
        print("Restored the previous app image. Database and Steam state remain mounted.")

    def update(self):
        self.preflight()
        self.command("git", "fetch", "--no-tags", "origin", "main")
        revision = self.command("git", "rev-parse", "FETCH_HEAD", capture=True)
        if not re.fullmatch(r"[0-9a-f]{40}", revision):
            raise UpdateError("Could not identify origin/main's commit.")
        print("Downloading published image for " + revision)
        try:
            self.command("docker", "pull", IMAGE + ":" + revision)
        except subprocess.CalledProcessError as error:
            raise UpdateError("Image unavailable. Wait for main's publish job, or authenticate to GHCR. The running app was left unchanged.") from error
        previous = self.snapshot()
        environment = self.env_file.read_bytes().decode("utf-8")
        write_private(self.previous, json.dumps(previous))
        try:
            self.command("git", "checkout", "--detach", revision)
            write_private(self.env_file, set_tag(environment, revision))
            self.start()
        except (Exception, KeyboardInterrupt) as error:
            print("Update failed; restoring the previous app image.")
            try:
                self.restore(previous)
            except Exception as rollback_error:
                raise UpdateError("Automatic rollback failed. Inspect Docker logs and use the saved deployment snapshot.") from rollback_error
            raise UpdateError("Update failed and was rolled back.") from error
        print("Updated to " + revision + ". Check Steam/GC welcome in docker compose logs --tail=100 app.")

    def rollback(self):
        self.preflight()
        if not self.previous.is_file():
            raise UpdateError("No previous deployment snapshot exists yet.")
        self.restore(json.loads(self.previous.read_text(encoding="utf-8")))


def main():
    import fcntl  # Linux server command; no extra packages required.
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--rollback", action="store_true", help="restore the previous image and configuration")
    args = parser.parse_args()
    updater = Updater(Path(__file__).resolve().parent.parent)
    try:
        updater.preflight()
        lock_path = updater.state / "update.lock"
        with lock_path.open("a") as lock:
            os.chmod(lock_path, 0o600)
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise UpdateError("Another update is already running.")
            updater.rollback() if args.rollback else updater.update()
    except (UpdateError, subprocess.CalledProcessError) as error:
        parser.exit(1, "Update stopped: " + str(error) + "\n")


if __name__ == "__main__":
    main()
