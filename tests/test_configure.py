from __future__ import annotations

import contextlib
import io
import os
import tempfile
import unittest
from unittest.mock import Mock, patch

from analyst.configure import apply_environment, ask, configure, ensure_configured, startup_error
from analyst.service import Analyst
from analyst.storage import Store


class ConfigurationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.service = Analyst(self.tmp.name, demo=True)
        self.output = io.StringIO()
        self.silent = contextlib.redirect_stdout(self.output)
        self.silent.__enter__()
        self.addCleanup(self.silent.__exit__, None, None, None)

    def test_first_setup_saves_hidden_token_and_monitoring_without_network(self):
        answers = iter(["123", "", "n", "BTC,ETH", "intraday", "15", "15", "2", "30", "0", "10", "n", "n"])
        reader, secret = Mock(side_effect=lambda _: next(answers)), Mock(return_value="123:secret_token")
        with patch("urllib.request.urlopen") as network:
            configure(self.service, input_fn=reader, secret_fn=secret)
            network.assert_not_called()
        self.assertEqual(self.service.store.watchlist(), ["BTCUSDT", "ETHUSDT"])
        self.assertEqual(self.service.store.settings()["horizon"], "intraday")
        self.assertEqual(self.service.store.settings()["monitor_report_minutes"], 0)
        self.assertEqual(self.service.store.settings()["telegram_daily_ai_calls"], 10)
        self.assertEqual(self.service.secrets.get("telegram_bot_token"), "123:secret_token")
        self.assertEqual(self.service.secrets.path.stat().st_mode & 0o777, 0o600)
        self.assertNotIn("secret_token", self.output.getvalue())
        self.assertEqual(startup_error(self.service), "")
        self.assertTrue(Store(self.tmp.name).settings()["telegram_chat_enabled"])

    def test_group_ai_config_rejects_bad_id_and_preserves_news_model_params(self):
        answers = iter(["bad-id", "-100", "", "9,10", "y", "https://example.com/v1", "test-model", "SOL",
                        "swing", "15", "15", "2", "30", "240", "50", "y", "bad", "08:30", "n"])
        secrets = iter(["bad-token", "123:token", "api_secret"])
        configure(self.service, input_fn=lambda _: next(answers), secret_fn=lambda _: next(secrets))
        settings = self.service.store.settings()
        self.assertEqual(settings["telegram_allowed_user_ids"], [9, 10])
        self.assertEqual(settings["llm_model"], "test-model")
        self.assertEqual(settings["daily_report_time"], "08:30")
        self.assertEqual(self.service.secrets.get("llm_api_key"), "api_secret")
        self.assertNotIn("api_secret", self.output.getvalue())
        self.assertEqual(startup_error(self.service), "")

    def test_reconfigure_keeps_hidden_secrets_and_clear_watchlist_is_explicit(self):
        self.service.save_settings({"telegram_chat_id": "123", "telegram_bot_token": "123:private",
                                   "llm_base_url": "https://example.com/v1", "llm_model": "test",
                                   "llm_api_key": "private-key"})
        answers = iter(["", "", "y", "", "", "-", "", "", "", "", "", "", "", "n", "n"])
        prompts = []
        def secret_reader(prompt):
            prompts.append(prompt)
            return ""
        configure(self.service, input_fn=lambda _: next(answers), secret_fn=secret_reader)
        self.assertEqual(self.service.secrets.get("telegram_bot_token"), "123:private")
        self.assertEqual(self.service.secrets.get("llm_api_key"), "private-key")
        self.assertEqual(self.service.store.watchlist(), [])
        self.assertEqual(Store(self.tmp.name).watchlist(), [])
        self.assertNotIn("private-key", "".join(prompts))
        self.assertNotIn("123:private", "".join(prompts))

    def test_cancel_setup_does_not_save_partial_settings_or_token(self):
        answers = iter(["123", ""])
        def reader(_):
            try:
                return next(answers)
            except StopIteration:
                raise EOFError
        with self.assertRaises(EOFError):
            configure(self.service, input_fn=reader, secret_fn=lambda _: "123:private")
        self.assertEqual(self.service.store.settings()["telegram_chat_id"], "")
        self.assertFalse(self.service.secrets.path.exists())

    def test_environment_can_bootstrap_noninteractive_and_never_overwrites_saved_values(self):
        env = {"AI2B_TELEGRAM_CHAT_ID": "123", "AI2B_TELEGRAM_BOT_TOKEN": "123:env",
               "AI2B_LLM_BASE_URL": "http://localhost:11434/v1", "AI2B_LLM_MODEL": "local-test"}
        with patch.dict(os.environ, env):
            apply_environment(self.service)
            ensure_configured(self.service, non_interactive=True)
            self.assertEqual(startup_error(self.service), "")
            self.service.store.save_settings({"telegram_chat_id": "456"})
            apply_environment(self.service)
            self.assertEqual(self.service.store.settings()["telegram_chat_id"], "456")

    def test_valid_existing_start_can_skip_setup_or_force_it(self):
        self.service.save_settings({"telegram_chat_id": "123", "telegram_bot_token": "123:fake"})
        with patch("sys.stdin.isatty", return_value=True), patch("builtins.input", return_value="n"), \
                patch("analyst.configure.configure") as wizard:
            ensure_configured(self.service)
            wizard.assert_not_called()
            ensure_configured(self.service, force=True)
            wizard.assert_called_once()

    def test_ask_retries_invalid_and_validation_does_not_persist(self):
        values = iter(["invalid", "-1", "5"])
        result = ask("数字", validate=lambda v: int(v) if int(v) > 0 else (_ for _ in ()).throw(ValueError()),
                     input_fn=lambda _: next(values))
        self.assertEqual(result, 5)
        self.service.store.validate_settings({"capital": 2000})
        self.assertEqual(self.service.store.settings()["capital"], 10000)
