from __future__ import annotations

import copy
import logging
import threading
import time
import uuid
from datetime import datetime
from zoneinfo import ZoneInfo

from .analysis import build_report
from .integrations import Secrets, Telegram, enrich_report, valid_llm_url
from .market import Market
from .storage import Store, symbol_name

log = logging.getLogger("ai2b")


class Analyst:
    def __init__(self, directory, demo=False, market=None):
        self.store = Store(directory)
        self.market = market or Market(demo=demo)
        self.secrets = Secrets(directory)
        self.telegram = Telegram(self.store, self.secrets)
        self.lock = threading.RLock()
        self.mode_lock = threading.RLock()
        self.stop_event = threading.Event()
        self.wake_event = threading.Event()
        self.quotes = {}
        self.market_error = ""
        self.last_poll = None
        self.started = time.time()
        self.jobs = {}
        self.worker_lock = threading.Lock()
        self.poll_lock = threading.Lock()
        self.thread = None
        self.last_daily_attempt = 0

    def start(self):
        self.thread = threading.Thread(target=self._loop, name="ai2b-monitor", daemon=True)
        self.thread.start()

    def close(self):
        self.stop_event.set()
        self.wake_event.set()
        if self.thread:
            self.thread.join(timeout=3)

    def state(self):
        with self.lock:
            quotes = copy.deepcopy(self.quotes)
            jobs = copy.deepcopy(list(self.jobs.values()))
            error = self.market_error
            last_poll = self.last_poll
        positions = self.store.positions()
        for p in positions:
            quote = quotes.get(p["symbol"])
            p["mark"] = quote["price"] if quote else None
            p["pnl"] = (quote["price"] - p["entry"]) * p["quantity"] if quote else None
            p["pnl_pct"] = (quote["price"] / p["entry"] - 1) * 100 if quote else None
        return {"watchlist": self.store.watchlist(), "quotes": quotes, "positions": positions,
            "alerts": self.store.alerts(), "reports": self.store.reports(), "jobs": jobs[-20:],
            "notifications": self.store.notifications(), "demo": self.market.demo,
            "market_error": error, "last_poll": last_poll, "started": self.started,
            "monitor_running": bool(self.thread and self.thread.is_alive()), "settings": self.settings()}

    def settings(self):
        result = self.store.settings()
        result["has_llm_key"] = bool(self.secrets.get("llm_api_key"))
        result["has_telegram_token"] = bool(self.secrets.get("telegram_bot_token"))
        result["telegram_configured"] = self.telegram.configured
        result["ai_configured"] = bool(result["llm_base_url"] and result["llm_model"])
        return result

    def save_settings(self, patch):
        if patch.get("llm_base_url"):
            valid_llm_url(patch["llm_base_url"])
        self.store.save_settings(patch)
        self.secrets.update(patch)
        self.wake_event.set()
        return self.settings()

    def switch_mode(self, demo):
        if not isinstance(demo, bool):
            raise ValueError("模式必须是布尔值")
        if not self.worker_lock.acquire(blocking=False):
            raise ValueError("分析正在进行，请完成后再切换数据模式")
        try:
            with self.mode_lock, self.lock:
                self.market.demo = demo
                self.market.clear()
                self.quotes.clear()
                self.market_error = ""
                self.last_poll = None
        finally:
            self.worker_lock.release()
        self.wake_event.set()

    def refresh(self):
        self.wake_event.set()

    def poll(self):
        if not self.poll_lock.acquire(blocking=False):
            return
        try:
            with self.mode_lock:
                watched = self.store.watchlist()
                needed = list(dict.fromkeys(watched + [p["symbol"] for p in self.store.positions()]
                    + [a["symbol"] for a in self.store.alerts() if a["active"]]))
                quotes = self.market.quotes(needed)
                with self.lock:
                    self.quotes.update(quotes)
                    self.last_poll = time.time()
                    missing = [s for s in needed if s not in quotes]
                    self.market_error = f"部分币种没有报价：{', '.join(missing)}" if missing else ""
            self.check_alerts(quotes)
        except Exception as exc:
            with self.lock:
                self.market_error = str(exc)[:400]
        finally:
            self.poll_lock.release()

    def check_alerts(self, quotes):
        for alert in self.store.alerts():
            q = quotes.get(alert["symbol"])
            if not alert["active"] or not q or q["demo"] or time.time() - q["timestamp"] > 90:
                continue
            hit = q["price"] >= alert["price"] if alert["direction"] == "above" else q["price"] <= alert["price"]
            if not hit or not self.store.fire_alert(alert["id"], q["price"]):
                continue
            direction = "向上到达" if alert["direction"] == "above" else "向下到达"
            title = f"{alert['symbol'][:-4]} {direction}预警价"
            text = f"{title}\n设置价位：{alert['price']:,.8g} USDT\n当前价格：{q['price']:,.8g} USDT\n数据源：{q['source']}\n单次预警已触发，请重新分析行情。"
            ident = self.store.notify("alert", title, text)
            if self.store.settings()["telegram_enabled"]:
                try:
                    self.telegram.send("AI2B 价格预警\n" + text)
                    self.store.delivered(ident)
                except Exception as exc:
                    self.store.notify("error", "预警推送未确认送达", str(exc))

    def analyze(self, symbol):
        symbol = symbol_name(symbol)
        settings = self.store.settings()
        snapshot = self.market.snapshot(symbol, settings["horizon"])
        with self.lock:
            quotes = copy.deepcopy(self.quotes)
            self.quotes[symbol] = snapshot["quote"]
        report = build_report(snapshot, settings, self.store.positions(), quotes)
        report = enrich_report(report, settings, self.secrets)
        report["id"] = self.store.add_report(report)
        return report

    def submit(self, kind, symbol=None):
        if kind not in ("analysis", "briefing"):
            raise ValueError("未知任务")
        if symbol:
            symbol = symbol_name(symbol)
        if not self.worker_lock.acquire(blocking=False):
            raise ValueError("已有分析任务正在进行，请稍候")
        ident = uuid.uuid4().hex[:12]
        with self.lock:
            # Keep a bounded in-memory task history; saved reports are persistent.
            self.jobs = dict(list(self.jobs.items())[-19:])
            self.jobs[ident] = {"id": ident, "kind": kind, "symbol": symbol, "status": "running",
                                "created": time.time(), "progress": "正在收集已收盘 K 线"}
        threading.Thread(target=self._job, args=(ident, kind, symbol), daemon=True, name="ai2b-analysis").start()
        return ident

    def _job(self, ident, kind, symbol):
        try:
            symbols = [symbol] if kind == "analysis" else self.store.watchlist()
            if not symbols:
                raise ValueError("请先添加自选币")
            reports, failures = [], []
            for i, target in enumerate(symbols):
                with self.lock:
                    self.jobs[ident]["progress"] = f"正在分析 {target[:-4]}（{i + 1}/{len(symbols)}）"
                try:
                    report = self.analyze(target)
                    reports.append(report)
                except Exception as exc:
                    failures.append(f"{target}: {str(exc)[:200]}")
            if not reports:
                raise ValueError("；".join(failures))
            pushed = False
            push_error = None
            if self.store.settings()["telegram_enabled"]:
                try:
                    for report in reports:
                        self.telegram.send_report(report)
                    pushed = True
                except Exception as exc:
                    push_error = str(exc)
                    self.store.notify("error", "报告已保存，推送未完成", push_error)
            with self.lock:
                self.jobs[ident].update(status="done", report_ids=[r["id"] for r in reports],
                    errors=failures, pushed=pushed, push_error=push_error, progress="报告已保存")
            self.store.notify("report", "分析报告已生成", f"已完成 {len(reports)} 个币种的分析" + ("，Telegram 已推送" if pushed else ""))
        except Exception as exc:
            with self.lock:
                self.jobs[ident].update(status="error", error=str(exc)[:500], progress="分析未完成")
        finally:
            self.worker_lock.release()

    def daily_tick(self, now=None):
        settings = self.store.settings()
        if not (settings["daily_report_enabled"] and settings["telegram_enabled"] and self.telegram.configured):
            return
        # Automatic scheduled delivery never sends synthetic market reports.
        if self.market.demo:
            return
        now = now or datetime.now(ZoneInfo("Asia/Shanghai"))
        day = now.strftime("%Y-%m-%d")
        if now.strftime("%H:%M") < settings["daily_report_time"] or self.store.get("last_daily_day") == day:
            return
        if time.time() - self.last_daily_attempt < 300:
            return
        try:
            ident = self.submit("briefing")
        except ValueError:
            return
        self.last_daily_attempt = time.time()
        # Persist claim BEFORE dispatch completion to prevent duplicate daily messages
        # after restarts. Failed deliveries remain visible and can be retried manually.
        self.store.set("last_daily_day", day)
        self.store.set("last_daily_job", ident)

    def _loop(self):
        while not self.stop_event.is_set():
            self.poll()
            try:
                self.daily_tick()
            except Exception:
                log.exception("Daily report scheduling failed")
            self.wake_event.wait(self.store.settings()["poll_seconds"])
            self.wake_event.clear()
