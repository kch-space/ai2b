from __future__ import annotations

import json
from pathlib import Path
import re
import signal
import subprocess
import sys
import tempfile
import unittest
import urllib.request
from unittest.mock import patch


class AppTests(unittest.TestCase):
    def test_telegram_only_runs_without_http_and_stops(self):
        from analyst.storage import Store
        with tempfile.TemporaryDirectory() as data:
            store = Store(data)
            store.save_settings({"telegram_chat_id": "123"})
            env = {"AI2B_TELEGRAM_BOT_TOKEN": "123:fake"}
            import os
            with patch.dict(os.environ, env):
                process = subprocess.Popen(
                    [sys.executable, str(Path(__file__).resolve().parent.parent / "app.py"),
                     "--demo", "--telegram-only", "--data-dir", data],
                    stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
            try:
                for line in process.stdout:
                    if "无网页" in line:
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

    def test_default_bind_and_sigterm_shutdown(self):
        with tempfile.TemporaryDirectory() as data:
            process = subprocess.Popen(
                [sys.executable, str(Path(__file__).resolve().parent.parent / "app.py"),
                 "--demo", "--port", "0", "--data-dir", data],
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
            )
            try:
                startup = []
                for line in process.stdout:
                    startup.append(line)
                    match = re.search(r"http://127\.0\.0\.1:(\d+)", line)
                    if match:
                        break
                else:
                    self.fail("应用未输出启动地址")
                self.assertIn(f"监听 0.0.0.0:{match[1]}", "".join(startup))
                with urllib.request.urlopen(f"http://127.0.0.1:{match[1]}/api/health", timeout=5) as response:
                    self.assertTrue(json.load(response)["ok"])
                process.send_signal(signal.SIGTERM)
                output, _ = process.communicate(timeout=10)
                self.assertEqual(process.returncode, 0, output)
                self.assertIn("AI2B 正在停止", output)
            finally:
                if process.poll() is None:
                    process.kill()
                    process.communicate(timeout=5)
