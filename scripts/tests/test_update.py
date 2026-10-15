import contextlib
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from update import Updater, UpdateError, set_tag


class FakeCommands:
    def __init__(self, fail_pull=False, fail_start=False, dirty=False):
        self.calls = []
        self.commit = "b" * 40
        self.fail_pull = fail_pull
        self.fail_start = fail_start
        self.dirty = dirty

    def __call__(self, args, capture=False):
        self.calls.append(args)
        if args[:3] == ["git", "status", "--porcelain"]:
            return " M docker-compose.yml" if self.dirty else ""
        if args[:2] == ["git", "rev-parse"]:
            return "a" * 40 if args[2] == "FETCH_HEAD" else self.commit
        if args[:2] == ["git", "checkout"]:
            self.commit = args[-1]
        if args[:2] == ["docker", "pull"] and self.fail_pull:
            raise subprocess.CalledProcessError(1, args)
        if args[:4] == ["docker", "compose", "ps", "-q"]:
            return "container-id"
        if args[:2] == ["docker", "inspect"]:
            return "sha256:" + "c" * 64 if args[3] == "{{.Image}}" else "true 0"
        if args[:3] == ["docker", "compose", "up"] and self.fail_start:
            self.fail_start = False
            raise subprocess.CalledProcessError(1, args)
        return ""


class UpdateTests(unittest.TestCase):
    def run_update(self, commands):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        original = "DISCORD_TOKEN=private-token\nDB_PASSWORD=private-password\nIMAGE_TAG=latest-test\n"
        (root / ".env").write_bytes(original.encode("utf-8"))
        updater = Updater(root, commands, sleep=lambda seconds: None)
        return updater, original

    def test_selects_main_sha_and_preserves_credentials(self):
        commands = FakeCommands()
        updater, original = self.run_update(commands)
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            updater.update()
        self.assertIn("IMAGE_TAG=" + "a" * 40, updater.env_file.read_text())
        self.assertIn("DB_PASSWORD=private-password", updater.env_file.read_text())
        self.assertNotIn("private-password", output.getvalue())
        self.assertNotIn("private-password", updater.previous.read_text())
        self.assertEqual(json.loads(updater.previous.read_text())["commit"], "b" * 40)

    def test_unpublished_image_leaves_checkout_environment_and_app_untouched(self):
        commands = FakeCommands(fail_pull=True)
        updater, original = self.run_update(commands)
        with self.assertRaises(UpdateError):
            updater.update()
        self.assertEqual(updater.env_file.read_text(), original)
        self.assertEqual(commands.commit, "b" * 40)
        self.assertFalse(any(args[:3] == ["docker", "compose", "up"] for args in commands.calls))

    def test_failed_start_restores_exact_previous_image(self):
        commands = FakeCommands(fail_start=True)
        updater, original = self.run_update(commands)
        with self.assertRaises(UpdateError):
            updater.update()
        self.assertEqual(commands.commit, "b" * 40)
        self.assertIn("IMAGE_TAG=rollback-" + "c" * 16, updater.env_file.read_text())
        self.assertEqual(sum(args[:3] == ["docker", "compose", "up"] for args in commands.calls), 2)

    def test_manual_rollback_reuses_saved_image_without_registry_pull(self):
        commands = FakeCommands()
        updater, original = self.run_update(commands)
        updater.update()
        commands.calls.clear()
        updater.rollback()
        self.assertEqual(commands.commit, "b" * 40)
        self.assertFalse(any(args[:2] == ["docker", "pull"] for args in commands.calls))

    def test_rollback_preserves_credentials_changed_since_deployment(self):
        commands = FakeCommands()
        updater, original = self.run_update(commands)
        updater.update()
        updater.env_file.write_text(updater.env_file.read_text().replace("private-password", "rotated-password"))
        updater.rollback()
        self.assertIn("DB_PASSWORD=rotated-password", updater.env_file.read_text())

    def test_dirty_checkout_is_preserved(self):
        commands = FakeCommands(dirty=True)
        updater, original = self.run_update(commands)
        with self.assertRaises(UpdateError):
            updater.update()
        self.assertEqual(updater.env_file.read_text(), original)
        self.assertEqual(len(commands.calls), 1)

    def test_coolify_handover_blocks_update_and_rollback_before_any_commands(self):
        commands = FakeCommands()
        updater, original = self.run_update(commands)
        (updater.root / ".coolify-managed").touch()
        for operation in (updater.update, updater.rollback):
            with self.assertRaisesRegex(UpdateError, "Coolify owns"):
                operation()
        self.assertEqual(commands.calls, [])
        self.assertEqual(updater.env_file.read_text(), original)

    def test_tag_edit_does_not_evaluate_shell_values(self):
        original = "SECRET='$(do-not-run)'\r\nIMAGE_TAG='old'\r\n"
        self.assertEqual(set_tag(original, "abc"), "SECRET='$(do-not-run)'\r\nIMAGE_TAG=abc\r\n")


if __name__ == "__main__":
    unittest.main()
