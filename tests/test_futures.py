from __future__ import annotations

import json
import sqlite3
import tempfile
import time
import unittest
from unittest.mock import patch
from email.utils import formatdate

from analyst.analysis import allocation, build_report, position_metrics
from analyst.market import MARKET, Market
from analyst.news import News, parse_feed
from analyst.storage import DEFAULT_SETTINGS, Store
from analyst.service import Analyst
from analyst.integrations import Secrets, enrich_report, report_text


def contract_rows():
    return {"symbols": [{"symbol": "BTCUSDT", "contractType": "PERPETUAL", "status": "TRADING",
                          "quoteAsset": "USDT", "marginAsset": "USDT"},
                         {"symbol": "ETHUSDT", "contractType": "CURRENT_QUARTER", "status": "TRADING",
                          "quoteAsset": "USDT", "marginAsset": "USDT"}]}


def futures_fetch(url):
    now = time.time() * 1000
    if "exchangeInfo" in url:
        return contract_rows()
    if "ticker/24hr" in url:
        return [{"symbol": "BTCUSDT", "lastPrice": "100", "priceChangePercent": "2",
                 "highPrice": "105", "lowPrice": "95", "quoteVolume": "10000", "closeTime": now}]
    if "premiumIndex" in url:
        return [{"symbol": "BTCUSDT", "markPrice": "101", "indexPrice": "99", "lastFundingRate": "0.0002",
                 "nextFundingTime": now + 3600000, "time": now}]
    if "openInterest" in url:
        return {"openInterest": "123", "time": now}
    raise AssertionError(url)


class FuturesMarketTests(unittest.TestCase):
    def test_contract_prices_are_distinct_and_typed(self):
        quote = Market(fetcher=futures_fetch).quotes(["BTC"])["BTCUSDT"]
        self.assertEqual(quote["market"], MARKET)
        self.assertEqual((quote["price"], quote["mark_price"], quote["index_price"]), (100, 101, 99))
        self.assertEqual(quote["funding_rate"], .0002)

    def test_delivery_contracts_are_not_perpetual(self):
        market = Market(fetcher=futures_fetch)
        self.assertEqual(market.contracts(), {"BTCUSDT"})
        with self.assertRaises(RuntimeError):
            market.quotes(["ETH"])

    def test_open_interest_degrades_explicitly(self):
        self.assertEqual(Market(fetcher=futures_fetch).derivatives("BTCUSDT")["open_interest"], 123)
        def fail(url):
            raise OSError("offline")
        result = Market(fetcher=fail).derivatives("BTCUSDT")
        self.assertIsNone(result["open_interest"])
        self.assertIn("暂不可用", result["error"])

    def test_bad_mark_price_is_not_accepted(self):
        def fetch(url):
            data = futures_fetch(url)
            if "premiumIndex" in url:
                data[0]["markPrice"] = "NaN"
            return data
        with self.assertRaises(RuntimeError):
            Market(fetcher=fetch).quotes(["BTC"])


class FuturesRiskTests(unittest.TestCase):
    def test_long_and_short_have_symmetric_stop_risk(self):
        long = allocation(100, 98, DEFAULT_SETTINGS, side="long")
        short = allocation(100, 102, DEFAULT_SETTINGS, side="short")
        self.assertEqual(long["amount"], short["amount"])
        self.assertLessEqual(short["planned_loss"], short["risk_budget"])
        self.assertAlmostEqual(short["margin_amount"] * 3, short["amount"])
        self.assertEqual(allocation(100, 98, DEFAULT_SETTINGS, side="short")["amount"], 0)

    def test_leverage_does_not_increase_risk_budget(self):
        low = allocation(100, 98, {**DEFAULT_SETTINGS, "leverage": 2, "allocation_pct": 100})
        high = allocation(100, 98, {**DEFAULT_SETTINGS, "leverage": 5, "allocation_pct": 100})
        self.assertEqual(low["planned_loss"], high["planned_loss"])
        self.assertGreater(low["margin_amount"], high["margin_amount"])

    def test_funding_sign_and_margin_guard(self):
        long = allocation(100, 98, DEFAULT_SETTINGS, funding_rate=.001)
        short = allocation(100, 102, DEFAULT_SETTINGS, side="short", funding_rate=.001)
        self.assertGreater(long["next_funding_estimate"], 0)
        self.assertLess(short["next_funding_estimate"], 0)
        result = allocation(100, 90, {**DEFAULT_SETTINGS, "leverage": 20})
        self.assertTrue(result["margin_guard"])
        self.assertEqual(result["amount"], 0)

    def test_short_position_pnl_uses_mark_and_direction(self):
        quote = {**Market(demo=True).quotes(["BTC"])["BTCUSDT"], "mark_price": 90, "price": 95}
        position = {"symbol": "BTCUSDT", "market": MARKET, "side": "short", "quantity": 2, "entry": 100, "leverage": 5}
        result = position_metrics(position, quote)
        self.assertEqual((result["pnl"], result["margin"], result["pnl_pct"]), (20, 40, 50))

    def test_short_report_has_correct_price_order(self):
        snapshot = Market(demo=True).snapshot("BTCUSDT", "swing")
        data = {"trend": "down", "rsi": 50, "support": 100, "resistance": 105, "atr": 1,
                "volume_ratio": 1.3, "ema20": 103, "ema50": 105}
        snapshot["quote"].update(price=101, mark_price=101)
        with patch("analyst.analysis.indicators", return_value=data):
            report = build_report(snapshot, DEFAULT_SETTINGS)
        self.assertEqual(report["direction"], "short")
        self.assertLess(report["levels"]["target"], report["levels"]["entry"])
        self.assertLess(report["levels"]["entry"], report["levels"]["stop"])
        self.assertGreater(report["allocation"]["amount"], 0)

    def test_market_mismatch_is_rejected(self):
        snapshot = Market(demo=True).snapshot("BTCUSDT", "swing")
        snapshot["series"]["4h"]["market"] = "spot"
        with self.assertRaises(ValueError):
            build_report(snapshot, DEFAULT_SETTINGS)

    def test_old_spot_positions_do_not_consume_futures_margin(self):
        snapshot = Market(demo=True).snapshot("BTCUSDT", "swing")
        result = build_report(snapshot, DEFAULT_SETTINGS, [{"symbol": "BTCUSDT", "quantity": 99, "entry": 100}])
        self.assertIsNone(result["position"])
        self.assertEqual(result["allocation"]["occupied"], 0)
        self.assertIn("旧现货", "".join(result["warnings"]))


class MigrationTests(unittest.TestCase):
    def test_legacy_rows_survive_and_new_contracts_do_not_replace_spot(self):
        with tempfile.TemporaryDirectory() as directory:
            with sqlite3.connect(directory + "/ai2b.sqlite3") as db:
                db.executescript("""CREATE TABLE positions(id INTEGER PRIMARY KEY, symbol TEXT UNIQUE,
                    quantity REAL, entry REAL, created REAL);
                    CREATE TABLE alerts(id INTEGER PRIMARY KEY, symbol TEXT, direction TEXT, price REAL,
                    active INTEGER DEFAULT 1, created REAL, fired REAL, triggered_price REAL);
                    INSERT INTO positions VALUES(7,'BTCUSDT',1,100,1);
                    INSERT INTO alerts(id,symbol,direction,price,created) VALUES(4,'BTCUSDT','above',110,1);""")
            store = Store(directory)
            self.assertEqual(store.positions()[0]["market"], "spot")
            self.assertFalse(store.alerts()[0]["active"])
            store.save_position({"symbol": "BTC", "entry": 200, "quantity": 2, "side": "short", "leverage": 5})
            store.add_alert({"symbol": "BTC", "direction": "above", "price": 120})
            reopened = Store(directory)
            self.assertEqual(len(reopened.positions()), 2)
            self.assertEqual(next(p for p in reopened.positions() if p["id"] == 7)["entry"], 100)
            self.assertTrue(reopened.alerts()[0]["active"])


class NewsTests(unittest.TestCase):
    def rss(self, title="Bitcoin ETF news", url="https://www.coindesk.com/markets/story", age=10):
        return f'<rss><channel><item><title>{title}</title><link>{url}</link><pubDate>{formatdate(time.time() - age, usegmt=True)}</pubDate><description>Recent market news</description></item></channel></rss>'.encode()

    def test_recent_sources_and_times_are_preserved(self):
        rows = parse_feed(self.rss(), "CoinDesk", "coindesk.com", time.time())
        self.assertEqual(rows[0]["source"], "CoinDesk")
        self.assertTrue(rows[0]["published"] > time.time() - 60)
        self.assertEqual(parse_feed(self.rss(age=90000), "CoinDesk", "coindesk.com", time.time()), [])
        self.assertEqual(parse_feed(self.rss(age=-300), "CoinDesk", "coindesk.com", time.time()), [])

    def test_untrusted_links_are_dropped(self):
        for url in ("javascript:alert(1)", "https://coindesk.com.evil.example/story", "https://user@coindesk.com/story"):
            self.assertEqual(parse_feed(self.rss(url=url), "CoinDesk", "coindesk.com", time.time()), [])

    def test_news_cache_filter_and_source_failure_are_explicit(self):
        calls = []
        def fetch(url):
            calls.append(url)
            if "cointelegraph" in url:
                raise OSError("offline")
            return self.rss()
        news = News(fetch)
        result = news.headlines("BTCUSDT")
        self.assertEqual(result["status"], "partial")
        self.assertEqual(result["items"][0]["id"], "N1")
        news.headlines("BTCUSDT")
        self.assertEqual(len(calls), 2)
        result["items"][0]["title"] = "changed"
        self.assertNotEqual(news.headlines("BTCUSDT")["items"][0]["title"], "changed")

    def test_unavailable_and_demo_never_invent_events(self):
        def fail(url):
            raise OSError("offline")
        news = News(fail)
        self.assertEqual(news.headlines("BTCUSDT")["status"], "unavailable")
        self.assertEqual(news.headlines("BTCUSDT")["items"], [])
        self.assertEqual(news.headlines("BTCUSDT", demo=True)["status"], "demo")


class AnalysisModeTests(unittest.TestCase):
    def test_rule_mode_never_calls_model_even_if_configured(self):
        with tempfile.TemporaryDirectory() as directory:
            service = Analyst(directory, demo=True)
            service.save_settings({"llm_base_url": "https://example.com/v1", "llm_model": "test", "llm_api_key": "fake"})
            with patch("analyst.service.enrich_report", side_effect=AssertionError("AI called")):
                report = service.analyze("BTC", "rules")
            self.assertEqual(report["engine"], "rules")
            self.assertEqual(report["news"]["status"], "demo")

    def test_missing_ai_credentials_fail_before_starting_job(self):
        with tempfile.TemporaryDirectory() as directory:
            service = Analyst(directory, demo=True)
            service.save_settings({"llm_base_url": "https://example.com/v1", "llm_model": "test"})
            self.assertFalse(service.settings()["ai_configured"])
            with self.assertRaises(ValueError):
                service.submit("analysis", "BTC", "ai")
            self.assertFalse(service.jobs)

    def test_default_ai_cannot_be_saved_without_model(self):
        with tempfile.TemporaryDirectory() as directory:
            service = Analyst(directory, demo=True)
            with self.assertRaises(ValueError):
                service.save_settings({"analysis_mode": "ai"})
            self.assertEqual(service.store.settings()["analysis_mode"], "rules")

    def test_context_changes_invalidate_existing_plans(self):
        with tempfile.TemporaryDirectory() as directory:
            service = Analyst(directory, demo=True)
            report = service.analyze("BTC")
            self.assertEqual(service.state()["plan_context"], report["plan_context"])
            service.save_settings({"leverage": 5})
            self.assertNotEqual(service.state()["plan_context"], report["plan_context"])
            self.assertLessEqual(report["valid_until"], report["created"] + 300)

    def test_ai_failure_does_not_return_fake_ai_report(self):
        with tempfile.TemporaryDirectory() as directory:
            service = Analyst(directory, demo=True)
            service.save_settings({"llm_base_url": "https://example.com/v1", "llm_model": "test", "llm_api_key": "private"})
            with patch("analyst.integrations.post_json", side_effect=RuntimeError("private")):
                with self.assertRaisesRegex(RuntimeError, "未生成冒充 AI") as raised:
                    service.analyze("BTC", "ai")
            self.assertNotIn("private", str(raised.exception))
            self.assertFalse(service.store.reports())

    def test_ai_success_preserves_sources_and_numeric_plan(self):
        with tempfile.TemporaryDirectory() as directory:
            service = Analyst(directory, demo=True)
            service.save_settings({"llm_base_url": "https://example.com/v1", "llm_model": "test", "llm_api_key": "fake"})
            event = {"id": "N1", "title": "Bitcoin event", "source": "CoinDesk", "published": time.time(),
                     "url": "https://www.coindesk.com/markets/test", "excerpt": "A report", "scope": "币种相关"}
            news = {"items": [event], "status": "ok", "errors": [], "fetched_at": time.time()}
            response = {"summary": "仅为模拟模型输出", "bull_case": "存在支撑", "bear_case": "未确认",
                        "watch_next": "等待确认", "event_assessment": "N1 的影响尚不确定", "event_refs": ["N1"],
                        "allocation": {"amount": 999999}}
            calls = []
            def poster(url, payload, **kwargs):
                calls.append(payload)
                return {"choices": [{"message": {"content": json.dumps(response)}}]}
            with patch.object(service.news, "headlines", return_value=news), patch("analyst.integrations.post_json", side_effect=poster):
                report = service.analyze("BTC", "ai")
            self.assertEqual(report["engine"], "ai")
            self.assertEqual(report["ai_scope"], "market_and_events")
            self.assertEqual(report["ai"]["event_refs"], ["N1"])
            self.assertNotEqual(report["allocation"]["amount"], 999999)
            supplied = json.loads(calls[0]["messages"][1]["content"])
            self.assertEqual(supplied["news"]["items"][0]["url"], event["url"])
            self.assertIn("CoinDesk", report_text(report))
            self.assertIn("初始保证金", report_text(report))

    def test_ai_unknown_citation_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            report = build_report(Market(demo=True).snapshot("BTCUSDT", "swing"), DEFAULT_SETTINGS)
            report["news"] = {"items": [{"id": "N1"}], "status": "ok"}
            secrets = Secrets(directory)
            secrets.update({"llm_api_key": "test"})
            response = {"summary": "summary", "bull_case": "bull", "bear_case": "bear", "watch_next": "wait",
                        "event_assessment": "event", "event_refs": ["N2"]}
            with self.assertRaises(RuntimeError):
                enrich_report(report, {"llm_base_url": "https://example.com/v1", "llm_model": "test"}, secrets,
                    poster=lambda *a, **k: {"choices": [{"message": {"content": json.dumps(response)}}]})
