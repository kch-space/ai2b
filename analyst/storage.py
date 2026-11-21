from __future__ import annotations

import copy
import json
import math
import re
import sqlite3
import threading
import time
from pathlib import Path

DEFAULT_SETTINGS = {
    "capital": 10000.0,
    "risk_pct": 1.0,
    "allocation_pct": 20.0,
    "reserve_pct": 30.0,
    "round_trip_cost_bps": 30.0,
    "horizon": "swing",
    "poll_seconds": 15,
    "leverage": 3.0,
    "analysis_mode": "rules",
    "telegram_enabled": False,
    "daily_report_enabled": False,
    "daily_report_time": "09:00",
    "telegram_chat_id": "",
    "llm_base_url": "",
    "llm_model": "",
    "telegram_chat_enabled": False,
    "telegram_allowed_user_ids": [],
    "monitor_enabled": False,
    "monitor_window_minutes": 15.0,
    "monitor_change_pct": 2.0,
    "monitor_cooldown_minutes": 30.0,
    "monitor_report_minutes": 240.0,
    "telegram_daily_ai_calls": 50.0,
}
SYMBOL_RE = re.compile(r"^[A-Z0-9]{2,15}USDT$")


def symbol_name(value: str) -> str:
    name = str(value).strip().upper().replace("/", "").replace("-", "")
    if not name.endswith("USDT"):
        name += "USDT"
    if not SYMBOL_RE.fullmatch(name):
        raise ValueError("请输入有效的 USDT 交易对，例如 BTC 或 BTCUSDT")
    return name


def number(value, name: str, low: float, high: float) -> float:
    if isinstance(value, bool):
        raise ValueError(f"{name}必须是数字")
    try:
        result = float(value)
    except (ValueError, TypeError):
        raise ValueError(f"{name}必须是数字") from None
    if not math.isfinite(result) or not low <= result <= high:
        raise ValueError(f"{name}须在 {low:g} 至 {high:g} 之间")
    return result


class Store:
    def __init__(self, directory: str | Path):
        self.directory = Path(directory)
        self.directory.mkdir(parents=True, exist_ok=True)
        self.path = self.directory / "ai2b.sqlite3"
        self.lock = threading.RLock()
        with self.connect() as db:
            db.executescript("""
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS config (key TEXT PRIMARY KEY, value TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS watchlist (symbol TEXT PRIMARY KEY, created REAL NOT NULL);
                CREATE TABLE IF NOT EXISTS positions (
                    id INTEGER PRIMARY KEY, symbol TEXT NOT NULL UNIQUE, quantity REAL NOT NULL,
                    entry REAL NOT NULL, created REAL NOT NULL);
                CREATE TABLE IF NOT EXISTS reports (
                    id INTEGER PRIMARY KEY, symbol TEXT NOT NULL, created REAL NOT NULL, payload TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS alerts (
                    id INTEGER PRIMARY KEY, symbol TEXT NOT NULL, direction TEXT NOT NULL,
                    price REAL NOT NULL, active INTEGER NOT NULL DEFAULT 1, created REAL NOT NULL,
                    fired REAL, triggered_price REAL);
                CREATE TABLE IF NOT EXISTS notifications (
                    id INTEGER PRIMARY KEY, kind TEXT NOT NULL, title TEXT NOT NULL,
                    message TEXT NOT NULL, created REAL NOT NULL, delivered INTEGER NOT NULL DEFAULT 0);
            """)
            db.execute("BEGIN IMMEDIATE")
            # Existing rows belong to the previous spot version. Preserve them
            # with an explicit market tag rather than reinterpreting exposure.
            columns = {r[1] for r in db.execute("PRAGMA table_info(positions)")}
            for name, definition in (("side", "TEXT NOT NULL DEFAULT 'long'"),
                                     ("leverage", "REAL NOT NULL DEFAULT 1"),
                                     ("market", "TEXT NOT NULL DEFAULT 'spot'")):
                if name not in columns:
                    db.execute(f"ALTER TABLE positions ADD COLUMN {name} {definition}")
            if not db.execute("SELECT 1 FROM config WHERE key='positions_market_v2'").fetchone():
                db.execute("""CREATE TABLE positions_v2 (
                    id INTEGER PRIMARY KEY, symbol TEXT NOT NULL, quantity REAL NOT NULL,
                    entry REAL NOT NULL, created REAL NOT NULL, side TEXT NOT NULL,
                    leverage REAL NOT NULL, market TEXT NOT NULL, UNIQUE(symbol,market))""")
                db.execute("INSERT INTO positions_v2 SELECT id,symbol,quantity,entry,created,side,leverage,market FROM positions")
                db.execute("DROP TABLE positions")
                db.execute("ALTER TABLE positions_v2 RENAME TO positions")
                db.execute("INSERT INTO config VALUES ('positions_market_v2','true')")
            alert_columns = {r[1] for r in db.execute("PRAGMA table_info(alerts)")}
            if "market" not in alert_columns:
                db.execute("ALTER TABLE alerts ADD COLUMN market TEXT NOT NULL DEFAULT 'spot'")
                db.execute("UPDATE alerts SET active=0 WHERE market='spot'")
                if db.execute("SELECT 1 FROM alerts UNION ALL SELECT 1 FROM positions LIMIT 1").fetchone():
                    db.execute("INSERT INTO notifications(kind,title,message,created) VALUES (?,?,?,?)",
                               ("info", "已切换币安 USDT 永续合约", "旧现货持仓保留并排除合约测算；旧现货预警已停用，请按合约价位重新创建。", time.time()))
            if not db.execute("SELECT 1 FROM config WHERE key='initialized'").fetchone():
                for symbol in ("BTCUSDT", "ETHUSDT", "SOLUSDT", "BNBUSDT"):
                    db.execute("INSERT OR IGNORE INTO watchlist VALUES (?,?)", (symbol, time.time()))
                db.execute("INSERT INTO config VALUES ('initialized','true')")

    def connect(self):
        db = sqlite3.connect(self.path, timeout=10)
        db.row_factory = sqlite3.Row
        return db

    def get(self, key, default=None):
        with self.connect() as db:
            row = db.execute("SELECT value FROM config WHERE key=?", (key,)).fetchone()
        return json.loads(row[0]) if row else default

    def set(self, key, value):
        with self.lock, self.connect() as db:
            db.execute("INSERT OR REPLACE INTO config VALUES (?,?)", (key, json.dumps(value)))

    def settings(self):
        result = copy.deepcopy(DEFAULT_SETTINGS)
        result.update(self.get("settings", {}))
        return result

    def validate_settings(self, patch):
        settings = self.settings()
        bounds = {"capital": (1, 1e10), "risk_pct": (0.1, 5), "allocation_pct": (1, 100),
                  "reserve_pct": (0, 95), "leverage": (1, 20), "round_trip_cost_bps": (0, 500), "poll_seconds": (10, 300),
                  "monitor_window_minutes": (1, 1440), "monitor_change_pct": (0.1, 100),
                  "monitor_cooldown_minutes": (1, 1440), "monitor_report_minutes": (0, 10080),
                  "telegram_daily_ai_calls": (1, 1000)}
        for key, limits in bounds.items():
            if key in patch:
                settings[key] = number(patch[key], key, *limits)
        if settings["leverage"] != int(settings["leverage"]):
            raise ValueError("杠杆须为 1–20 的整数")
        if "horizon" in patch:
            if patch["horizon"] not in ("intraday", "swing", "position"):
                raise ValueError("未知的观察周期")
            settings["horizon"] = patch["horizon"]
        if "analysis_mode" in patch:
            if patch["analysis_mode"] not in ("rules", "ai"):
                raise ValueError("分析模式须为 rules 或 ai")
            settings["analysis_mode"] = patch["analysis_mode"]
        for key in ("telegram_enabled", "daily_report_enabled", "telegram_chat_enabled", "monitor_enabled"):
            if key in patch:
                if not isinstance(patch[key], bool):
                    raise ValueError(f"{key}须为布尔值")
                settings[key] = patch[key]
        for key in ("telegram_chat_id", "llm_base_url", "llm_model", "daily_report_time"):
            if key in patch:
                settings[key] = str(patch[key]).strip()[:500]
        if not re.fullmatch(r"(?:[01]\d|2[0-3]):[0-5]\d", settings["daily_report_time"]):
            raise ValueError("推送时间须为 HH:MM，例如 09:00")
        if "telegram_allowed_user_ids" in patch:
            values = patch["telegram_allowed_user_ids"]
            if (not isinstance(values, list) or any(isinstance(v, bool) or not isinstance(v, int)
                                                   or v <= 0 for v in values)):
                raise ValueError("Telegram 用户白名单须为正整数 ID 数组")
            settings["telegram_allowed_user_ids"] = list(dict.fromkeys(values))
        if settings["telegram_daily_ai_calls"] != int(settings["telegram_daily_ai_calls"]):
            raise ValueError("Telegram 日调用上限必须为整数")
        if settings["monitor_window_minutes"] * 60 < settings["poll_seconds"] * 2:
            raise ValueError("异动窗口至少为行情轮询间隔的两倍")
        if settings["telegram_chat_enabled"]:
            chat = settings["telegram_chat_id"]
            if not re.fullmatch(r"-?[1-9]\d*", chat):
                raise ValueError("Telegram 对话需要数字 Chat ID")
            if int(chat) < 0 and not settings["telegram_allowed_user_ids"]:
                raise ValueError("群聊对话必须配置允许操作的 Telegram 用户 ID")
        return settings

    def save_settings(self, patch):
        settings = self.validate_settings(patch)
        self.set("settings", settings)
        return settings

    def replace_watchlist(self, symbols):
        symbols = list(dict.fromkeys(symbol_name(s) for s in symbols))
        if len(symbols) > 20:
            raise ValueError("最多关注20个币种")
        with self.lock, self.connect() as db:
            db.execute("DELETE FROM watchlist")
            db.executemany("INSERT INTO watchlist VALUES (?,?)", [(s, time.time()) for s in symbols])

    def watchlist(self):
        with self.connect() as db:
            return [r[0] for r in db.execute("SELECT symbol FROM watchlist ORDER BY created,symbol")]

    def add_symbol(self, symbol):
        symbol = symbol_name(symbol)
        with self.lock, self.connect() as db:
            if (db.execute("SELECT COUNT(*) FROM watchlist").fetchone()[0] >= 20
                    and not db.execute("SELECT 1 FROM watchlist WHERE symbol=?", (symbol,)).fetchone()):
                raise ValueError("第一版最多关注 20 个币种")
            db.execute("INSERT OR IGNORE INTO watchlist VALUES (?,?)", (symbol, time.time()))
        return symbol

    def delete_symbol(self, symbol):
        with self.lock, self.connect() as db:
            db.execute("DELETE FROM watchlist WHERE symbol=?", (symbol_name(symbol),))

    def positions(self):
        with self.connect() as db:
            return [dict(r) for r in db.execute("SELECT * FROM positions ORDER BY created DESC")]

    def save_position(self, data):
        symbol = symbol_name(data.get("symbol", ""))
        side = data.get("side", "long")
        if side not in ("long", "short"):
            raise ValueError("持仓方向须为 long 或 short")
        leverage = number(data.get("leverage", self.settings()["leverage"]), "杠杆", 1, 20)
        if leverage != int(leverage):
            raise ValueError("杠杆须为 1–20 的整数")
        quantity = number(data.get("quantity"), "持仓数量", 1e-12, 1e15)
        entry = number(data.get("entry"), "成本价", 1e-12, 1e12)
        if quantity * entry > 1e12:
            raise ValueError("持仓金额过大，请检查数量和成本价")
        with self.lock, self.connect() as db:
            db.execute("""INSERT INTO positions(symbol,quantity,entry,created,side,leverage,market) VALUES (?,?,?,?,?,?,?)
                ON CONFLICT(symbol,market) DO UPDATE SET quantity=excluded.quantity,entry=excluded.entry,
                side=excluded.side,leverage=excluded.leverage,market=excluded.market""",
                       (symbol, quantity, entry, time.time(), side, leverage, "binance_usdm"))
        return self.positions()

    def delete_position(self, ident):
        with self.lock, self.connect() as db:
            db.execute("DELETE FROM positions WHERE id=?", (int(ident),))

    def add_report(self, report):
        with self.lock, self.connect() as db:
            cursor = db.execute("INSERT INTO reports(symbol,created,payload) VALUES (?,?,?)",
                                (report["symbol"], report["created"], json.dumps(report, ensure_ascii=False, allow_nan=False)))
            ident = cursor.lastrowid
            db.execute("DELETE FROM reports WHERE id NOT IN (SELECT id FROM reports ORDER BY id DESC LIMIT 500)")
        return ident

    def reports(self, limit=30):
        with self.connect() as db:
            rows = db.execute("SELECT id,payload FROM reports ORDER BY id DESC LIMIT ?", (limit,))
            return [{**json.loads(r["payload"]), "id": r["id"]} for r in rows]

    def report(self, ident):
        with self.connect() as db:
            row = db.execute("SELECT payload FROM reports WHERE id=?", (int(ident),)).fetchone()
        if not row:
            raise ValueError("报告不存在")
        return {**json.loads(row[0]), "id": int(ident)}

    def alerts(self):
        with self.connect() as db:
            return [dict(r) for r in db.execute("SELECT * FROM alerts ORDER BY created DESC LIMIT 100")]

    def add_alert(self, data):
        symbol = symbol_name(data.get("symbol", ""))
        direction = data.get("direction")
        if direction not in ("above", "below"):
            raise ValueError("预警方向须为向上突破或向下跌破")
        price = number(data.get("price"), "预警价格", 1e-12, 1e12)
        with self.lock, self.connect() as db:
            db.execute("INSERT INTO alerts(symbol,direction,price,created,market) VALUES (?,?,?,?,?)",
                       (symbol, direction, price, time.time(), "binance_usdm"))

    def delete_alert(self, ident):
        with self.lock, self.connect() as db:
            db.execute("DELETE FROM alerts WHERE id=?", (int(ident),))

    def fire_alert(self, ident, price):
        with self.lock, self.connect() as db:
            result = db.execute("UPDATE alerts SET active=0,fired=?,triggered_price=? WHERE id=? AND active=1",
                                (time.time(), price, ident))
        return result.rowcount == 1

    def notify(self, kind, title, message):
        with self.lock, self.connect() as db:
            result = db.execute("INSERT INTO notifications(kind,title,message,created) VALUES (?,?,?,?)",
                                (kind, title, message, time.time()))
            db.execute("DELETE FROM notifications WHERE id NOT IN (SELECT id FROM notifications ORDER BY id DESC LIMIT 200)")
        return result.lastrowid

    def delivered(self, ident):
        with self.lock, self.connect() as db:
            db.execute("UPDATE notifications SET delivered=1 WHERE id=?", (ident,))

    def notifications(self):
        with self.connect() as db:
            return [dict(r) for r in db.execute("SELECT * FROM notifications ORDER BY id DESC LIMIT 30")]

    def conversation(self, chat_id, user_id):
        return self.get(f"conversation:{chat_id}:{user_id}", {"symbols": [], "messages": []})

    def save_conversation(self, chat_id, user_id, symbols, messages):
        self.set(f"conversation:{chat_id}:{user_id}", {"symbols": symbols, "messages": messages[-8:]})

    def claim_telegram_ai_call(self, limit):
        # Persist before dispatch; failed requests also count toward the daily cap.
        day = time.strftime("%Y-%m-%d", time.gmtime())
        with self.lock:
            budget = self.get("telegram_ai_calls", {})
            count = budget.get("count", 0) if budget.get("day") == day else 0
            if count >= int(limit):
                raise ValueError("今天的 Telegram AI 调用次数已达上限，/price 与 /analyze 规则分析仍可使用")
            self.set("telegram_ai_calls", {"day": day, "count": count + 1})
