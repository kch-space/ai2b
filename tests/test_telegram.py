from __future__ import annotations

import json
import tempfile
import time
import unittest
from unittest.mock import Mock, patch

from analyst.integrations import answer_question
from analyst.market import MARKET
from analyst.service import Analyst
from analyst.storage import Store


class TelegramTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.service = Analyst(self.tmp.name, demo=True)
        self.bot = self.service.telegram_bot
        self.service.save_settings({"telegram_chat_id": "123", "telegram_bot_token": "123:fake",
            "telegram_chat_enabled": True, "telegram_enabled": True, "monitor_enabled": True,
            "llm_base_url": "http://localhost:11434/v1", "llm_model": "test"})
        self.send = Mock()
        self.service.telegram.send = self.send
        self.message = {"chat": {"id": 123}, "from": {"id": 9}, "text": "/watch BTC"}

    def test_chat_and_group_authorization(self):
        self.assertTrue(self.bot.authorized(self.message))
        self.bot.handle({**self.message, "chat": {"id": 999}})
        self.send.assert_not_called()
        with self.assertRaisesRegex(ValueError, "群聊"):
            self.service.save_settings({"telegram_chat_id": "-123"})
        self.service.save_settings({"telegram_chat_id": "-123", "telegram_allowed_user_ids": [9]})
        self.assertTrue(self.bot.authorized({**self.message, "chat": {"id": -123}}))
        self.assertFalse(self.bot.authorized({**self.message, "chat": {"id": -123}, "from": {"id": 10}}))

    def test_watch_price_and_rules_do_not_call_ai(self):
        with patch("analyst.service.answer_question") as ai:
            for text in ("/watch XRP", "/price XRP", "/analyze XRP"):
                self.bot.handle({**self.message, "text": text})
            ai.assert_not_called()
        self.assertIn("XRPUSDT", self.service.store.watchlist())
        self.assertIn("非真实行情", self.send.call_args.args[0])
        self.assertIn("规则分析", self.send.call_args.args[0])
        self.bot.handle({**self.message, "text": "/unwatch XRP"})
        self.assertNotIn("XRPUSDT", self.service.store.watchlist())

    def test_followup_context_isolated_and_fresh(self):
        with patch.object(self.service, "telegram_answer", return_value="新快照的回答") as ai:
            self.bot.handle({**self.message, "text": "分析一下 btc"})
            ai.assert_called_with(["BTCUSDT"], "分析一下 btc", [])
            self.bot.handle({**self.message, "text": "关键支撑在哪？"})
            self.assertEqual(ai.call_args.args[0], ["BTCUSDT"])
            self.assertEqual(ai.call_args.args[2][1]["content"], "新快照的回答")
            with self.assertRaisesRegex(ValueError, "先指定"):
                self.bot.handle({**self.message, "from": {"id": 10}, "text": "支撑在哪？"})
        self.bot.handle({**self.message, "text": "/reset"})
        self.assertEqual(self.service.store.conversation(123, 9)["symbols"], [])

    def test_news_citations_and_snapshot_timestamp(self):
        reports = self.service.telegram_reports(["BTC"])
        now = time.time()
        reports[0]["news"] = {"items": [{"id": "N1", "source": "Test", "published": now,
            "url": "https://example.com/btc", "title": "Bitcoin flows"}]}
        poster = Mock(return_value={"choices": [{"message": {"content": json.dumps({
            "answer": "观察量能，不确定是否延续。", "event_refs": [{"symbol": "BTCUSDT", "id": "N1"}]})}}]})
        answer = answer_question(reports, "上涨怎么看", [], self.service.store.settings(), self.service.secrets, poster)
        self.assertIn("行情快照", answer)
        self.assertIn("https://example.com/btc", answer)
        self.assertIn("演示", answer)
        payload = json.loads(poster.call_args.args[1]["messages"][1]["content"])
        self.assertEqual(payload["reports"][0]["symbol"], "BTCUSDT")
        poster.return_value["choices"][0]["message"]["content"] = json.dumps({"answer": "x",
            "event_refs": [{"symbol": "ETHUSDT", "id": "N1"}]})
        with self.assertRaisesRegex(RuntimeError, "对话"):
            answer_question(reports, "x", [], self.service.store.settings(), self.service.secrets, poster)

    def test_ai_uses_fresh_snapshot_and_persistent_daily_limit(self):
        self.service.save_settings({"telegram_daily_ai_calls": 1})
        with patch.object(self.service.market, "snapshot", wraps=self.service.market.snapshot) as snapshot:
            with patch("analyst.service.answer_question", return_value="answer"):
                self.service.telegram_answer(["BTCUSDT"], "分析", [])
                snapshot.assert_called_once()
                with self.assertRaisesRegex(ValueError, "次数"):
                    self.service.telegram_answer(["BTCUSDT"], "再问", [])
        store = Store(self.tmp.name)
        with self.assertRaisesRegex(ValueError, "次数"):
            store.claim_telegram_ai_call(1)

    def test_telegram_long_messages_are_delivered_in_plain_text(self):
        poster = Mock(return_value={"ok": True})
        self.service.telegram.poster = poster
        del self.service.telegram.send
        text = "🟢<新闻>" * 1200
        self.service.telegram.send(text)
        chunks = [call.args[1]["text"] for call in poster.call_args_list]
        self.assertEqual("".join(chunks), text)
        self.assertTrue(all(len(chunk.encode("utf-16-le")) // 2 < 4096 for chunk in chunks))
        self.assertTrue(all("parse_mode" not in call.args[1] for call in poster.call_args_list))

    def test_window_warmup_downside_outage_cooldown_restart(self):
        self.service.save_settings({"monitor_window_minutes": 1, "poll_seconds": 10,
                                   "monitor_change_pct": 2, "monitor_report_minutes": 0})
        start = time.time()
        def tick(stamp, price, demo=False):
            self.bot.monitor_tick({"BTCUSDT": {"price": price, "timestamp": stamp,
                "market": MARKET, "demo": demo}}, now=stamp)
        for i in range(0, 60, 10):
            tick(start + i, 100)
        self.assertTrue(self.bot.tasks.empty())
        tick(start + 60, 97)
        task = self.bot.tasks.get_nowait()
        self.assertEqual(task[:3], ("automatic", "BTCUSDT", "alert"))
        self.assertIn("-3.00%", task[3])
        self.service.store.set("monitor:BTCUSDT", {"report": start, "alert": start + 60})
        self.bot.pending.clear()
        tick(start + 70, 96)
        self.assertTrue(self.bot.tasks.empty())
        tick(start + 150, 50)
        self.assertTrue(self.bot.tasks.empty())
        self.assertEqual(len(self.bot.history["BTCUSDT"]), 1)
        self.assertEqual(Store(self.tmp.name).get("monitor:BTCUSDT")["alert"], start + 60)
        self.bot.clear_monitor()
        tick(start + 200, 10, demo=True)
        self.assertNotIn("BTCUSDT", self.bot.history)

    def test_auto_paused_unwatched_and_expired_events_do_not_send(self):
        self.service.market.demo = False
        self.service.store.set("telegram_monitor_paused", True)
        self.bot.automatic("BTCUSDT", "alert", "上涨", time.time())
        self.send.assert_not_called()
        self.service.store.set("telegram_monitor_paused", False)
        self.bot.automatic("BTCUSDT", "alert", "上涨", time.time() - 1000)
        self.send.assert_not_called()
        self.service.store.delete_symbol("BTC")
        self.bot.automatic("BTCUSDT", "alert", "上涨", time.time())
        self.send.assert_not_called()

    def test_long_poll_discards_old_messages_and_queues_only_authorized_updates(self):
        updates = [
            {"update_id": 41, "message": {**self.message, "chat": {"id": 999}}},
            {"update_id": 42, "message": {**self.message, "forward_origin": {"type": "user"}}},
            {"update_id": 43, "message": self.message},
        ]
        def poster(url, data, timeout):
            if data["offset"] == -1:
                return {"ok": True, "result": [{"update_id": 40, "message": self.message}]}
            self.service.stop_event.set()
            return {"ok": True, "result": updates}
        self.bot.poster = Mock(side_effect=poster)
        self.bot.listen()
        self.assertEqual(self.bot.offset, 44)
        self.assertEqual(self.bot.tasks.qsize(), 1)
        self.assertEqual(self.bot.tasks.get_nowait(), ("message", self.message))
        self.assertEqual(self.bot.poster.call_args_list[1].args[1]["offset"], 41)

    def test_monitor_command_validates_and_saves_without_web(self):
        self.bot.handle({**self.message, "text": "/monitor 5 3 10 0"})
        settings = self.service.store.settings()
        self.assertEqual(settings["monitor_change_pct"], 3)
        self.assertEqual(settings["monitor_report_minutes"], 0)
        with self.assertRaises(ValueError):
            self.bot.handle({**self.message, "text": "/monitor 0 3 10 0"})

    def test_periodic_report_and_alert_analysis(self):
        self.service.save_settings({"monitor_report_minutes": 1})
        now = time.time()
        self.service.market.demo = False
        self.service.store.set("monitor:BTCUSDT", {"report": now - 100, "alert": 0})
        self.bot.monitor_tick({"BTCUSDT": {"price": 100, "timestamp": now,
            "market": MARKET, "demo": False}}, now)
        task = self.bot.tasks.get_nowait()
        self.assertEqual(task[2], "report")
        with patch.object(self.service, "telegram_answer", return_value="综合分析") as ai:
            self.bot.automatic(*task[1:])
            self.assertEqual(self.send.call_args.args[0], "综合分析")
            ai.assert_called_once()
        self.assertGreaterEqual(self.service.store.get("monitor:BTCUSDT")["report"], now)


if __name__ == "__main__":
    unittest.main()
