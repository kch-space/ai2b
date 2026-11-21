"""Telegram long polling, bounded conversations, and public-market monitoring."""
from __future__ import annotations

import logging
import math
import queue
import re
import threading
import time
from collections import deque
from datetime import datetime
from zoneinfo import ZoneInfo

from .integrations import post_json, report_text
from .market import MARKET
from .storage import symbol_name

log = logging.getLogger("ai2b")
HELP = """AI2B 行情助手
/watch BTC ETH — 添加关注
/unwatch BTC — 取消关注
/list — 查看关注
/price BTC — 最新合约价格
/analyze BTC — 技术规则报告（不调用 AI）
/ask BTC 这次上涨有量能支持吗？ — 结合新闻行情回答
也可以直接说“分析一下 BTC”，然后追问“支撑在哪里？”
/alert BTC above 100000 — 单次突破预警（below 为跌破）
/pause /resume — 暂停或恢复异动、定时分析推送
/monitor 15 2 30 240 — 窗口、阈值、冷却、报告间隔
/status — 监测、推送规则与 AI 调用额度
/reset — 清空自己的对话
"""
ALIASES = {"比特币": "BTC", "以太坊": "ETH", "索拉纳": "SOL", "狗狗币": "DOGE",
           "瑞波币": "XRP", "币安币": "BNB"}


class TelegramBot:
    def __init__(self, analyst, poster=None):
        self.analyst = analyst
        self.store = analyst.store
        self.poster = poster or post_json
        self.tasks = queue.Queue(maxsize=24)
        self.pending = set()
        self.lock = threading.Lock()
        self.threads = []
        self.history = {}
        self.last_error = 0
        self.offset = None
        self.token = None

    def start(self):
        for name, target in (("ai2b-telegram", self.listen), ("ai2b-telegram-analysis", self.work)):
            thread = threading.Thread(target=target, name=name, daemon=True)
            thread.start()
            self.threads.append(thread)

    def close(self):
        # Requests have bounded network timeouts; threads never open trading accounts.
        for thread in self.threads:
            thread.join(timeout=1)

    def authorized(self, message):
        settings = self.store.settings()
        user = message.get("from", {})
        allowed = settings["telegram_allowed_user_ids"]
        chat = message.get("chat", {}).get("id")
        return (settings["telegram_chat_enabled"] and str(chat) == settings["telegram_chat_id"]
                and isinstance(chat, int) and isinstance(user.get("id"), int) and not user.get("is_bot", False)
                and (chat > 0 or bool(allowed)) and (not allowed or user["id"] in allowed))

    def request(self, token, data):
        if not re.fullmatch(r"\d+:[A-Za-z0-9_-]+", token):
            raise ValueError("Telegram Token 格式不正确")
        result = self.poster(f"https://api.telegram.org/bot{token}/getUpdates", data, timeout=15)
        if not result.get("ok") or not isinstance(result.get("result"), list):
            raise RuntimeError("Telegram 未返回消息列表")
        return result["result"]

    def listen(self):
        while not self.analyst.stop_event.is_set():
            settings = self.store.settings()
            token = self.analyst.secrets.get("telegram_bot_token")
            if not settings["telegram_chat_enabled"] or not token:
                self.analyst.stop_event.wait(2)
                continue
            try:
                if token != self.token:
                    # Discard old queued messages at startup/token change; offsets stay in memory.
                    updates = self.request(token, {"offset": -1, "timeout": 0, "allowed_updates": ["message"]})
                    self.offset = max((u["update_id"] + 1 for u in updates), default=0)
                    self.token = token
                updates = self.request(token, {"offset": self.offset, "timeout": 10,
                                              "allowed_updates": ["message"]})
                for update in updates:
                    self.offset = max(self.offset, update["update_id"] + 1)
                    message = update.get("message", {})
                    # Ignore edits/forwards and authorize BEFORE accessing history or dispatching AI.
                    if self.authorized(message) and not message.get("forward_origin") and message.get("text"):
                        self.enqueue(("message", message))
            except Exception:
                if time.time() - self.last_error > 1800:
                    self.store.notify("error", "Telegram 对话连接失败", "请检查 Token、网络、webhook 或重复轮询实例")
                    self.last_error = time.time()
                self.analyst.stop_event.wait(5)

    def enqueue(self, task):
        key = task[1] if task[0] == "automatic" else None
        with self.lock:
            if key and key in self.pending:
                return
            try:
                self.tasks.put_nowait(task)
            except queue.Full:
                return
            if key:
                self.pending.add(key)

    def work(self):
        while not self.analyst.stop_event.is_set():
            try:
                task = self.tasks.get(timeout=0.5)
            except queue.Empty:
                continue
            try:
                if task[0] == "message":
                    if self.authorized(task[1]):
                        self.handle(task[1])
                else:
                    self.automatic(*task[1:])
            except (ValueError, RuntimeError) as exc:
                try:
                    self.analyst.telegram.send(str(exc))
                except Exception:
                    log.warning("Telegram delivery failed")
            except Exception:
                log.warning("Telegram task failed")
                self.store.notify("error", "Telegram 请求未完成", "请检查行情、模型或 Telegram 连接")
            finally:
                with self.lock:
                    if task[0] == "automatic":
                        self.pending.discard(task[1])
                self.tasks.task_done()

    def resolve(self, text):
        symbol = symbol_name(ALIASES.get(text, text))
        if not self.analyst.market.demo and symbol not in self.analyst.market.contracts():
            raise ValueError(f"{symbol} 不是正在交易的币安 USDT 永续合约")
        return symbol

    def mentions(self, text):
        result = [self.resolve(base) for alias, base in ALIASES.items() if alias in text]
        # Resolve mentioned tickers against the supported contracts.
        tokens = re.findall(r"(?<![a-zA-Z0-9])\$?([A-Za-z0-9]{2,20}(?:/[A-Za-z0-9]+)?)(?![a-zA-Z0-9])", text)
        for token in tokens:
            try:
                result.append(self.resolve(token))
            except ValueError:
                continue
        return list(dict.fromkeys(result))

    def handle(self, message):
        if not self.authorized(message):
            return
        text = message["text"].strip()
        if len(text) > 2000:
            raise ValueError("问题请控制在2000字以内")
        chat, user = message["chat"]["id"], message["from"]["id"]
        command, *args = text.split()
        command = command.split("@", 1)[0].lower()
        def send(reply):
            # Connection settings may change while a model request is in flight.
            if self.authorized(message):
                self.analyst.telegram.send(reply)
        if command in ("/start", "/help"):
            send(HELP)
        elif command == "/watch":
            if not args:
                raise ValueError("用法：/watch BTC ETH")
            symbols = list(dict.fromkeys(self.resolve(a) for a in args))
            if len(set(symbols) | set(self.store.watchlist())) > 20:
                raise ValueError("最多关注20个币种")
            for symbol in symbols:
                self.store.add_symbol(symbol)
            self.analyst.wake_event.set()
            send("已关注：" + ", ".join(symbols))
        elif command == "/unwatch":
            if not args:
                raise ValueError("用法：/unwatch BTC")
            for arg in args:
                symbol = symbol_name(ALIASES.get(arg, arg))
                self.store.delete_symbol(symbol)
                with self.lock:
                    self.history.pop(symbol, None)
            send("已取消关注")
        elif command == "/list":
            send("关注币种：" + (", ".join(self.store.watchlist()) or "暂无"))
        elif command == "/price":
            symbols = [self.resolve(a) for a in args] if args else self.store.watchlist()
            quotes = self.analyst.market.quotes(symbols)
            lines = []
            for symbol in symbols:
                q = quotes.get(symbol)
                if not q or time.time() - q["timestamp"] > 90:
                    lines.append(symbol + "：最新行情不可用")
                    continue
                stamp = datetime.fromtimestamp(q["timestamp"], ZoneInfo("Asia/Shanghai")).strftime("%m-%d %H:%M:%S")
                lines.append(f"{symbol}：{q['price']:,.8g} USDT，24h {q['change_pct']:+.2f}%\n"
                             f"标记价 {q['mark_price']:,.8g}｜{stamp} 北京时间｜{q['source']}")
            send("\n\n".join(lines) or "请先关注币种")
        elif command == "/alert":
            if len(args) != 3:
                raise ValueError("用法：/alert BTC above 100000（below 为向下）")
            self.store.add_alert({"symbol": self.resolve(args[0]), "direction": args[1], "price": args[2]})
            send("单次价位预警已设置")
        elif command in ("/pause", "/resume"):
            self.store.set("telegram_monitor_paused", command == "/pause")
            send("异动、定时分析推送已" + ("暂停" if command == "/pause" else "恢复") + "，对话仍可使用")
        elif command == "/reset":
            self.store.save_conversation(chat, user, [], [])
            send("已清空你的对话上下文")
        elif command == "/monitor":
            if len(args) != 4:
                raise ValueError("用法：/monitor 15 2 30 240（分钟、百分比、分钟、分钟；最后0关闭定时报告）")
            self.analyst.save_settings(dict(zip(("monitor_window_minutes", "monitor_change_pct",
                "monitor_cooldown_minutes", "monitor_report_minutes"), args)))
            self.clear_monitor()
            send("监测参数已保存，重新积累完整行情窗口")
        elif command == "/status":
            s = self.store.settings()
            budget = self.store.get("telegram_ai_calls", {})
            today = time.strftime("%Y-%m-%d", time.gmtime())
            count = budget.get("count", 0) if budget.get("day") == today else 0
            send(f"AI2B｜币安 USDT 永续\n关注 {len(self.store.watchlist())} 个｜轮询 {s['poll_seconds']:g}秒\n"
                 f"异动监测：{'开启' if s['monitor_enabled'] else '关闭'}，自动分析：{'暂停' if self.store.get('telegram_monitor_paused') else '运行'}\n"
                 f"{s['monitor_window_minutes']:g}分钟涨跌 {s['monitor_change_pct']:g}%｜冷却 {s['monitor_cooldown_minutes']:g}分钟\n"
                 f"定时分析 {s['monitor_report_minutes']:g}分钟（0为关闭）\n"
                 f"Telegram AI 今日调用 {count}/{int(s['telegram_daily_ai_calls'])}（UTC日）\n"
                 f"行情状态：{self.analyst.market_error or '请按消息快照时间核对'}")
        elif command == "/analyze":
            if not args:
                raise ValueError("用法：/analyze BTC")
            symbol = self.resolve(args[0])
            report = report_text(self.analyst.telegram_reports([symbol])[0])
            self.store.save_conversation(chat, user, [symbol], [
                {"role": "user", "content": text}, {"role": "assistant", "content": report[:3500]}])
            send(report)
        else:
            previous = self.store.conversation(chat, user)
            if command == "/ask":
                if not args:
                    raise ValueError("用法：/ask BTC 关键支撑在哪里？")
                symbols = [self.resolve(args[0])]
                question = " ".join(args[1:]) or "结合新闻行情分析当前走势与需要观察的条件"
            elif command.startswith("/"):
                send(HELP)
                return
            else:
                symbols = self.mentions(text) or previous["symbols"]
                question = text
            if not symbols:
                raise ValueError("请先指定币种，例如“分析一下 BTC”，之后可以直接追问")
            if len(symbols) > 3:
                raise ValueError("一次最多分析三个币种")
            history = previous["messages"] if previous["symbols"] == symbols else []
            send("正在获取行情与新闻并分析…")
            answer = self.analyst.telegram_answer(symbols, question, history)
            self.store.save_conversation(chat, user, symbols, history + [
                {"role": "user", "content": question}, {"role": "assistant", "content": answer[:3500]}])
            send(answer)

    def clear_monitor(self):
        with self.lock:
            self.history.clear()

    def monitor_tick(self, quotes, now=None):
        now = time.time() if now is None else now
        s = self.store.settings()
        if not (s["monitor_enabled"] and s["telegram_enabled"] and self.analyst.telegram.configured):
            with self.lock:
                self.history.clear()
            return
        window, interval = s["monitor_window_minutes"] * 60, s["poll_seconds"]
        watched = self.store.watchlist()
        for symbol in watched:
            q = quotes.get(symbol)
            if (not q or q.get("market") != MARKET or q.get("demo") or q.get("cached")
                    or not math.isfinite(q["price"]) or q["price"] <= 0
                    or not 0 <= now - q["timestamp"] <= 90):
                continue
            with self.lock:
                history = self.history.setdefault(symbol, deque())
                if history and now - history[-1][0] > interval * 3:
                    history.clear()
                if history and q["timestamp"] <= history[-1][0]:
                    continue
                history.append((q["timestamp"], q["price"]))
                while len(history) > 1 and history[1][0] <= now - window:
                    history.popleft()
                base = history[0]
            if self.store.get("telegram_monitor_paused"):
                continue
            claim = self.store.get("monitor:" + symbol, {})
            if not claim:
                self.store.set("monitor:" + symbol, {"report": now, "alert": 0})
                continue
            if base[0] <= now - window and now - window - base[0] <= interval * 2:
                change = (q["price"] / base[1] - 1) * 100
                if abs(change) >= s["monitor_change_pct"] and now - claim.get("alert", 0) >= s["monitor_cooldown_minutes"] * 60:
                    self.enqueue(("automatic", symbol, "alert", f"约{window / 60:g}分钟涨跌 {change:+.2f}%", now))
                    continue
            if s["monitor_report_minutes"] and now - claim.get("report", now) >= s["monitor_report_minutes"] * 60:
                self.enqueue(("automatic", symbol, "report", "定时行情分析", now))

    def automatic(self, symbol, kind, reason, observed):
        s = self.store.settings()
        if (not s["monitor_enabled"] or not s["telegram_enabled"] or self.analyst.market.demo
                or self.store.get("telegram_monitor_paused") or symbol not in self.store.watchlist()):
            return
        if time.time() - observed > max(90, s["poll_seconds"] * 3):
            return  # An expired queued event must not be presented as a new alert.
        self.analyst.telegram.send(f"{symbol}｜{reason}\n采集时间：" + datetime.fromtimestamp(
            observed, ZoneInfo("Asia/Shanghai")).strftime("%m-%d %H:%M:%S 北京时间"))
        claim = self.store.get("monitor:" + symbol, {})
        claim[kind] = time.time()
        self.store.set("monitor:" + symbol, claim)
        try:
            if self.analyst.settings()["ai_configured"]:
                answer = self.analyst.telegram_answer([symbol], reason + "，结合最新新闻行情分析原因、关键位置与失效条件", [])
            else:
                answer = "未配置模型，以下为规则分析：\n" + report_text(self.analyst.telegram_reports([symbol])[0])
        except (ValueError, RuntimeError) as exc:
            answer = "行情事件已推送，本次分析未完成：" + str(exc)
        if (symbol in self.store.watchlist() and not self.store.get("telegram_monitor_paused")
                and self.store.settings()["telegram_enabled"] and not self.analyst.stop_event.is_set()):
            self.analyst.telegram.send(answer)
