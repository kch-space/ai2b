from __future__ import annotations

import os
import signal
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from analyst.storage import Store

ROOT = Path(__file__).resolve().parent.parent


class AppTests(unittest.TestCase):
    def test_saved_configuration_starts_and_stops_without_http(self):
        with tempfile.TemporaryDirectory() as data:
            Store(data).save_settings({"telegram_chat_id": "123"})
            with patch.dict(os.environ, {"AI2B_TELEGRAM_BOT_TOKEN": "123:fake"}):
                process = subprocess.Popen(
                    [sys.executable, str(ROOT / "app.py"), "--demo", "--non-interactive", "--data-dir", data],
                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            try:
                for line in process.stdout:
                    if "Telegram 行情助手已启动" in line:
                        break
                else:
                    self.fail("Telegram 模式未启动")
                process.send_signal(signal.SIGTERM)
                output, _ = process.communicate(timeout=10)
                self.assertEqual(process.returncode, 0, output)
                self.assertNotIn("http://127.0.0.1:", output)
                self.assertIn("AI2B 正在停止", output)
            finally:
                if process.poll() is None:
                    process.kill()
                    process.communicate(timeout=5)

    def test_missing_config_noninteractive_explains_setup(self):
        with tempfile.TemporaryDirectory() as data:
            env = {k: v for k, v in os.environ.items() if not k.startswith("AI2B_")}
            result = subprocess.run([sys.executable, str(ROOT / "app.py"), "--non-interactive", "--data-dir", data],
                                    env=env, text=True, capture_output=True, timeout=10)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("docker compose run --rm ai2b --configure", result.stderr)
            self.assertNotIn("Traceback", result.stderr)

    def test_configure_requires_terminal_and_help_has_no_web_options(self):
        with tempfile.TemporaryDirectory() as data:
            result = subprocess.run([sys.executable, str(ROOT / "app.py"), "--configure", "--data-dir", data],
                                    text=True, input="", capture_output=True, timeout=10)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("终端交互", result.stderr)
        result = subprocess.run([sys.executable, str(ROOT / "app.py"), "--help"],
                                text=True, capture_output=True, timeout=10)
        self.assertEqual(result.returncode, 0)
        self.assertIn("--configure", result.stdout)
        for option in ("--port", "--host", "--telegram-only"):
            self.assertNotIn(option, result.stdout)
        self.assertFalse((ROOT / "static").exists())
        self.assertFalse((ROOT / "analyst" / "web.py").exists())
