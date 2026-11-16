from __future__ import annotations

import copy
import json
import math
import random
import tempfile
import time
import unittest
from datetime import datetime
from pathlib import Path
from unittest.mock import patch
from zoneinfo import ZoneInfo

from analyst.analysis import allocation, atr, build_report, ema, indicators, rsi
from analyst.integrations import Secrets, Telegram, enrich_report, report_text, valid_llm_url
from analyst.market import INTERVALS, MARKET, Market
from analyst.service import Analyst
from analyst.storage import DEFAULT_SETTINGS, Store, number, symbol_name


def fixture_snapshot():
    return Market(demo=True).snapshot("BTCUSDT", "swing")


class IndicatorTests(unittest.TestCase):
    def test_rsi_flat_is_neutral(self):
        self.assertEqual(rsi([10.] * 50), 50)

    def test_rsi_monotonic_up_and_down(self):
        self.assertEqual(rsi(list(range(1, 60))), 100)
        self.assertEqual(rsi(list(range(60, 1, -1))), 0)

    def test_ema_constant(self):
        self.assertEqual(ema([42] * 100, 20), 42)

    def test_rsi_requires_warmup(self):
        with self.assertRaises(ValueError):
            rsi([1, 2, 3])

    def test_atr_uses_gaps(self):
        bars = [{"high": 11, "low": 9, "close": 10}] + [{"high": 22, "low": 20, "close": 21}] * 20
        self.assertGreater(atr(bars), 2)


class AllocationTests(unittest.TestCase):
    def test_risk_budget_includes_cost(self):
        result = allocation(100, 95, DEFAULT_SETTINGS)
        self.assertLessEqual(result["planned_loss"], result["risk_budget"])
        self.assertAlmostEqual(result["estimated_cost"], result["amount"] * .003)

    def test_position_occupancy_limits_new_capital(self):
        result = allocation(100, 98, DEFAULT_SETTINGS, occupied=6800)
        self.assertEqual(result["margin_amount"], 200)

    def test_reserve_exhaustion_blocks_new_amount(self):
        self.assertEqual(allocation(100, 95, DEFAULT_SETTINGS, occupied=8000)["amount"], 0)

    def test_existing_same_asset_reduces_concentration_room(self):
        result = allocation(100, 99, DEFAULT_SETTINGS, occupied=1900, asset_occupied=1900)
        self.assertEqual(result["margin_amount"], 100)
        self.assertEqual(allocation(100, 99, DEFAULT_SETTINGS, asset_occupied=2500)["amount"], 0)

    def test_disabled_signal_and_invalid_stop(self):
        self.assertEqual(allocation(100, 95, DEFAULT_SETTINGS, enabled=False)["amount"], 0)
        self.assertEqual(allocation(100, 100, DEFAULT_SETTINGS)["amount"], 0)
        self.assertEqual(allocation(100, 110, DEFAULT_SETTINGS)["amount"], 0)

    def test_randomized_allocation_invariants(self):
        rng = random.Random(42)
        for _ in range(300):
            settings = {**DEFAULT_SETTINGS, "capital": rng.uniform(100, 1000000),
                "risk_pct": rng.uniform(.1, 5), "allocation_pct": rng.uniform(1, 100),
                "reserve_pct": rng.uniform(0, 95), "round_trip_cost_bps": rng.uniform(0, 200)}
            entry = rng.uniform(1, 100000)
            result = allocation(entry, entry * rng.uniform(.5, .99), settings, occupied=rng.uniform(0, settings["capital"]))
            self.assertLessEqual(result["margin_amount"], result["leg_cap"] + 1e-8)
            self.assertLessEqual(result["margin_amount"], result["available"] + 1e-8)
            self.assertLessEqual(result["planned_loss"], result["risk_budget"] + 1e-8)
            self.assertGreaterEqual(result["amount"], 0)


class ReportTests(unittest.TestCase):
    def test_report_has_traceable_data_and_serializes(self):
        report = build_report(fixture_snapshot(), DEFAULT_SETTINGS)
        self.assertTrue(report["demo"])
        self.assertEqual(report["engine"], "rules")
        self.assertGreater(report["levels"]["entry"], report["levels"]["stop"])
        self.assertGreater(report["levels"]["target"], report["levels"]["entry"])
        json.dumps(report, allow_nan=False)

    def test_stale_quote_blocks_allocation(self):
        snapshot = fixture_snapshot()
        snapshot["quote"]["timestamp"] = time.time() - 120
        report = build_report(snapshot, DEFAULT_SETTINGS)
        self.assertTrue(report["stale"])
        self.assertEqual(report["allocation"]["amount"], 0)
        self.assertEqual(report["signal"], "wait")

    def test_gap_in_recent_bars_blocks_allocation(self):
        snapshot = fixture_snapshot()
        snapshot["series"]["4h"]["bars"].pop(-10)
        report = build_report(snapshot, DEFAULT_SETTINGS)
        self.assertTrue(report["stale"])
        self.assertIn("缺口", "".join(report["warnings"]))

    def test_position_diagnosis_uses_mark_to_market(self):
        snapshot = fixture_snapshot()
        price = snapshot["quote"]["mark_price"]
        report = build_report(snapshot, DEFAULT_SETTINGS,
            [{"symbol": "BTCUSDT", "quantity": .1, "entry": price / 2, "market": MARKET, "side": "long", "leverage": 2}])
        self.assertAlmostEqual(report["position"]["pnl_pct"], 200)
        self.assertAlmostEqual(report["position"]["pnl"], price * .05)
        self.assertAlmostEqual(report["allocation"]["occupied"], price * .025)

    def test_other_position_without_fresh_mark_blocks_new_amount(self):
        snapshot = fixture_snapshot()
        positions = [{"symbol": "ETHUSDT", "quantity": 1, "entry": 1000, "market": MARKET, "side": "long", "leverage": 3}]
        for quotes in ({}, {"ETHUSDT": {"price": 2000, "timestamp": time.time() - 120, "demo": True}}):
            report = build_report(snapshot, DEFAULT_SETTINGS, positions, quotes)
            self.assertEqual(report["allocation"]["amount"], 0)
            self.assertTrue(report["stale"])
            self.assertIn("持仓", "".join(report["warnings"]))


class MarketTests(unittest.TestCase):
    def test_demo_never_fetches_external_data(self):
        market = Market(demo=True, fetcher=lambda url: self.fail("unexpected network"))
        self.assertTrue(market.quotes(["BTC"])["BTCUSDT"]["demo"])
        self.assertEqual(len(market.candles("BTC")["bars"]), 160)

    def test_live_failure_never_invents_demo_prices(self):
        def fail(url):
            raise OSError("offline")
        market = Market(fetcher=fail)
        with self.assertRaisesRegex(RuntimeError, "不会用模拟价格"):
            market.quotes(["BTC"])
        with self.assertRaises(RuntimeError):
            market.candles("BTC")

    def test_futures_failure_never_falls_back_to_spot_or_okx(self):
        calls = []
        def fetch(url):
            calls.append(url)
            raise OSError("unavailable")
        with self.assertRaisesRegex(RuntimeError, "现货或其他交易所"):
            Market(fetcher=fetch).quotes(["BTC"])
        self.assertTrue(all(url.startswith("https://fapi.binance.com/") for url in calls))

    def test_unclosed_binance_candle_is_excluded(self):
        seconds = 14400
        end = int(time.time() // seconds) * seconds
        rows = [[(end - (70 - i) * seconds) * 1000, "100", "102", "99", "101", "1",
                 (end - (69 - i) * seconds) * 1000 - 1] for i in range(71)]
        market = Market(fetcher=lambda url: rows)
        market.cache["contracts"] = (time.time(), {"BTCUSDT"})
        data = market.candles("BTC")
        self.assertEqual(len(data["bars"]), 70)
        self.assertTrue(all(b["time"] + seconds <= time.time() for b in data["bars"]))

    def test_invalid_candle_shape_rejected(self):
        bars = Market(demo=True).candles("BTC")["bars"]
        bars[-1]["low"] = bars[-1]["high"] + 100
        with self.assertRaises(ValueError):
            Market._validate_bars(bars)

    def test_nonfinite_and_future_market_timestamps_rejected(self):
        market = Market(demo=True)
        quote = market.quotes(["BTC"])["BTCUSDT"]
        for field, value in (("timestamp", math.nan), ("timestamp", time.time() + 1000), ("change_pct", math.inf)):
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                Market._validate_quote({**quote, field: value})
        bars = market.candles("BTC")["bars"]
        bars[-1]["time"] = math.nan
        with self.assertRaises(ValueError):
            Market._validate_bars(bars)

    def test_insufficient_and_unsorted_candles_rejected(self):
        with self.assertRaises(ValueError):
            Market._validate_bars([])
        bars = Market(demo=True).candles("BTC")["bars"]
        bars[-1]["time"] = bars[-2]["time"]
        with self.assertRaises(ValueError):
            Market._validate_bars(bars)


class StoreTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = Store(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_invalid_numeric_and_symbols_rejected(self):
        for value in (math.nan, math.inf, True, "bad"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                number(value, "资金", 1, 100)
        with self.assertRaises(ValueError):
            symbol_name("<script>")
        self.assertEqual(symbol_name("btc/usdt"), "BTCUSDT")

    def test_setting_constraints_and_persistence(self):
        self.store.save_settings({"capital": 2000, "risk_pct": .5})
        self.assertEqual(Store(self.tmp.name).settings()["capital"], 2000)
        with self.assertRaises(ValueError):
            self.store.save_settings({"risk_pct": 80})
        with self.assertRaises(ValueError):
            self.store.save_settings({"daily_report_time": "25:00"})
        with self.assertRaises(ValueError):
            self.store.save_settings({"telegram_enabled": "false"})

    def test_empty_watchlist_stays_empty_after_restart(self):
        for symbol in self.store.watchlist():
            self.store.delete_symbol(symbol)
        self.assertEqual(Store(self.tmp.name).watchlist(), [])

    def test_position_updates_instead_of_duplicates(self):
        self.store.save_position({"symbol": "ETH", "quantity": 1, "entry": 2000})
        self.store.save_position({"symbol": "ETH", "quantity": 2, "entry": 2500})
        self.assertEqual(len(self.store.positions()), 1)
        self.assertEqual(self.store.positions()[0]["quantity"], 2)

    def test_alert_fire_is_atomic_and_once_only(self):
        self.store.add_alert({"symbol": "BTC", "direction": "above", "price": 100})
        ident = self.store.alerts()[0]["id"]
        self.assertTrue(self.store.fire_alert(ident, 110))
        self.assertFalse(self.store.fire_alert(ident, 120))


class IntegrationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.store = Store(self.tmp.name)
        self.secrets = Secrets(self.tmp.name)

    def tearDown(self):
        self.tmp.cleanup()

    def test_secrets_have_private_permissions(self):
        self.secrets.update({"llm_api_key": "test-secret"})
        self.assertEqual(self.secrets.get("llm_api_key"), "test-secret")
        self.assertEqual(self.secrets.path.stat().st_mode & 0o777, 0o600)
        self.secrets.update({"llm_api_key": ""})
        self.assertEqual(self.secrets.get("llm_api_key"), "test-secret")

    def test_model_url_validation(self):
        self.assertEqual(valid_llm_url("http://localhost:11434/v1/"), "http://localhost:11434/v1")
        for url in ("http://example.com/v1", "https://user:key@example.com", "https://example.com?api_key=secret"):
            with self.assertRaises(ValueError):
                valid_llm_url(url)

    def test_ai_cannot_override_numeric_plan(self):
        report = build_report(fixture_snapshot(), DEFAULT_SETTINGS)
        allocation_before = copy.deepcopy(report["allocation"])
        self.secrets.update({"llm_api_key": "test-key"})
        response = {"summary": "市场处于观察阶段", "bull_case": "趋势偏上", "bear_case": "量能不足",
                    "watch_next": "等待确认", "event_assessment": "未纳入热点", "event_refs": [], "allocation": {"amount": 9999999}}
        result = enrich_report(report, {"llm_base_url": "https://example.com/v1", "llm_model": "test"}, self.secrets,
            poster=lambda *a, **k: {"choices": [{"message": {"content": json.dumps(response)}}]})
        self.assertEqual(result["engine"], "ai")
        self.assertEqual(result["allocation"], allocation_before)

    def test_ai_failure_is_explicit_without_leaking_secrets(self):
        report = build_report(fixture_snapshot(), DEFAULT_SETTINGS)
        self.secrets.update({"llm_api_key": "private-key"})
        def fail(*a, **kw):
            raise RuntimeError("private-key")
        with self.assertRaises(RuntimeError) as raised:
            enrich_report(report, {"llm_base_url": "https://example.com/v1", "llm_model": "test"}, self.secrets, poster=fail)
        self.assertEqual(report["engine"], "rules")
        self.assertNotIn("private-key", str(raised.exception))

    def test_telegram_plain_text_and_demo_label(self):
        calls = []
        self.secrets.update({"telegram_bot_token": "12345:fake_token"})
        self.store.save_settings({"telegram_chat_id": "-100123"})
        telegram = Telegram(self.store, self.secrets, poster=lambda *a, **k: calls.append((a, k)) or {"ok": True})
        telegram.send_report(build_report(fixture_snapshot(), DEFAULT_SETTINGS))
        payload = calls[0][0][1]
        self.assertEqual(payload["chat_id"], "-100123")
        self.assertIn("演示报告", payload["text"])
        self.assertNotIn("parse_mode", payload)

    def test_telegram_failure_does_not_expose_token(self):
        self.secrets.update({"telegram_bot_token": "12345:private_token"})
        self.store.save_settings({"telegram_chat_id": "42"})
        def fail(*args, **kwargs):
            raise RuntimeError(args[0])
        with self.assertRaises(RuntimeError) as raised:
            Telegram(self.store, self.secrets, poster=fail).send("test")
        self.assertNotIn("private_token", str(raised.exception))


class MonitorTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.service = Analyst(self.tmp.name, demo=True)

    def tearDown(self):
        self.service.close()
        self.tmp.cleanup()

    def quote(self, price=110, demo=False, age=0):
        return {"BTCUSDT": {"price": price, "demo": demo, "timestamp": time.time() - age, "source": "test", "market": MARKET}}

    def test_stale_and_demo_quotes_never_fire_alerts(self):
        self.service.store.add_alert({"symbol": "BTC", "direction": "above", "price": 100})
        self.service.check_alerts(self.quote(demo=True))
        self.service.check_alerts(self.quote(age=200))
        self.assertTrue(self.service.store.alerts()[0]["active"])

    def test_alert_notifies_exactly_once(self):
        self.service.store.add_alert({"symbol": "BTC", "direction": "above", "price": 100})
        self.service.check_alerts(self.quote())
        self.service.check_alerts(self.quote())
        self.assertEqual(len(self.service.store.notifications()), 1)

    def test_downward_alert(self):
        self.service.store.add_alert({"symbol": "BTC", "direction": "below", "price": 100})
        self.service.check_alerts(self.quote(price=101))
        self.assertTrue(self.service.store.alerts()[0]["active"])
        self.service.check_alerts(self.quote(price=99))
        self.assertFalse(self.service.store.alerts()[0]["active"])

    def test_settings_response_never_contains_secret(self):
        self.service.save_settings({"llm_api_key": "private-value", "telegram_bot_token": "42:private"})
        result = json.dumps(self.service.settings())
        self.assertNotIn("private-value", result)
        self.assertNotIn("42:private", result)
        self.assertTrue(self.service.settings()["has_llm_key"])

    def test_analyze_saves_report_and_persists(self):
        self.service.poll()
        report = self.service.analyze("BTC")
        self.assertEqual(self.service.store.report(report["id"])["symbol"], "BTCUSDT")
        self.assertEqual(Store(self.tmp.name).reports()[0]["id"], report["id"])

    def test_mode_cannot_change_during_analysis(self):
        self.service.worker_lock.acquire()
        try:
            with self.assertRaises(ValueError):
                self.service.switch_mode(False)
        finally:
            self.service.worker_lock.release()

    def test_daily_schedule_is_once_per_beijing_day(self):
        self.service.market.demo = False
        self.service.save_settings({"telegram_bot_token": "42:fake", "telegram_chat_id": "1",
            "telegram_enabled": True, "daily_report_enabled": True, "daily_report_time": "09:00"})
        before = datetime(2026, 11, 15, 8, 59, tzinfo=ZoneInfo("Asia/Shanghai"))
        after = before.replace(hour=9, minute=0)
        with patch.object(self.service, "submit", return_value="job") as submit:
            self.service.daily_tick(before)
            submit.assert_not_called()
            self.service.daily_tick(after)
            self.service.daily_tick(after)
            submit.assert_called_once_with("briefing")
        self.assertEqual(self.service.store.get("last_daily_day"), "2026-11-15")

    def test_daily_synthetic_reports_never_auto_send(self):
        self.service.save_settings({"telegram_bot_token": "42:fake", "telegram_chat_id": "1",
            "telegram_enabled": True, "daily_report_enabled": True})
        with patch.object(self.service, "submit") as submit:
            self.service.daily_tick(datetime(2026, 11, 15, 12, tzinfo=ZoneInfo("Asia/Shanghai")))
            submit.assert_not_called()


if __name__ == "__main__":
    unittest.main()
