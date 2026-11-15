from __future__ import annotations

import hashlib
import json
import math
import random
import threading
import time
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

from .storage import symbol_name

INTERVALS = {"15m": 900, "1h": 3600, "4h": 14400, "1d": 86400}
BASES = {"BTCUSDT": 86420, "ETHUSDT": 2848, "SOLUSDT": 143.8, "BNBUSDT": 612.4,
         "DOGEUSDT": 0.168, "XRPUSDT": 1.98, "ADAUSDT": 0.42, "TRXUSDT": 0.28}


def get_json(url, timeout=8):
    req = urllib.request.Request(url, headers={"User-Agent": "AI2B/0.1", "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as response:
        return json.loads(response.read(4_000_000))


class Market:
    def __init__(self, demo=False, fetcher=None):
        self.demo = demo
        self.fetch = fetcher or get_json
        self.lock = threading.RLock()
        self.cache = {}
        self.quote_cache = {}

    def clear(self):
        with self.lock:
            self.cache.clear()
            self.quote_cache.clear()

    def quotes(self, symbols):
        symbols = list(dict.fromkeys(symbol_name(s) for s in symbols))
        if not symbols:
            return {}
        if self.demo:
            return {s: self._demo_quote(s) for s in symbols}
        query = urllib.parse.urlencode({"symbols": json.dumps(symbols, separators=(",", ":"))})
        try:
            rows = self.fetch("https://data-api.binance.vision/api/v3/ticker/24hr?" + query)
            if isinstance(rows, dict):
                rows = [rows]
            result = {}
            for row in rows:
                symbol = row.get("symbol")
                if symbol not in symbols:
                    continue
                price = float(row["lastPrice"])
                if price <= 0 or not math.isfinite(price):
                    continue
                result[symbol] = {"symbol": symbol, "price": price,
                    "change_pct": float(row["priceChangePercent"]), "high": float(row["highPrice"]),
                    "low": float(row["lowPrice"]), "volume": float(row["quoteVolume"]),
                    "timestamp": float(row["closeTime"]) / 1000, "source": "Binance 公共行情", "demo": False}
            if result:
                for quote in result.values():
                    self._validate_quote(quote)
                with self.lock:
                    self.quote_cache.update(result)
                return result
        except Exception:
            pass
        # Independent public spot-data fallback. No account API keys required.
        try:
            payload = self.fetch("https://www.okx.com/api/v5/market/tickers?instType=SPOT")
            result = {}
            for row in payload.get("data", []):
                symbol = row["instId"].replace("-", "")
                if symbol not in symbols:
                    continue
                price, opening = float(row["last"]), float(row["open24h"])
                if price <= 0 or not math.isfinite(price):
                    continue
                result[symbol] = {"symbol": symbol, "price": price,
                    "change_pct": (price / opening - 1) * 100 if opening else 0,
                    "high": float(row["high24h"]), "low": float(row["low24h"]),
                    "volume": float(row.get("volCcy24h", 0)), "timestamp": float(row["ts"]) / 1000,
                    "source": "OKX 公共行情", "demo": False}
            if result:
                for quote in result.values():
                    self._validate_quote(quote)
                with self.lock:
                    self.quote_cache.update(result)
                return result
        except Exception:
            pass
        raise RuntimeError("公共行情源暂时不可达。请检查网络，或手动切换演示模式；系统不会用模拟价格替代真实行情。")

    @staticmethod
    def _validate_quote(quote):
        if any(not math.isfinite(quote[k]) for k in ("price", "change_pct", "high", "low", "volume", "timestamp")):
            raise ValueError("报价包含无效数值")
        if quote["price"] <= 0 or quote["low"] <= 0 or quote["high"] < quote["low"] or quote["volume"] < 0:
            raise ValueError("报价价格关系异常")
        if not 0 < quote["timestamp"] <= time.time() + 60:
            raise ValueError("报价时间异常")

    def candles(self, symbol, interval="4h", limit=160):
        symbol = symbol_name(symbol)
        if interval not in INTERVALS:
            raise ValueError("不支持的 K 线周期")
        key = (symbol, interval, limit, self.demo)
        with self.lock:
            cached = self.cache.get(key)
            if cached and time.time() - cached[0] < 60:
                return cached[1]
        if self.demo:
            bars = self._demo_bars(symbol, interval, limit)
            result = {"bars": bars, "source": "演示数据", "demo": True, "interval": interval}
        else:
            errors = []
            result = None
            query = urllib.parse.urlencode({"symbol": symbol, "interval": interval, "limit": limit + 1})
            try:
                rows = self.fetch("https://data-api.binance.vision/api/v3/klines?" + query)
                bars = [{"time": int(r[0]) / 1000, "open": float(r[1]), "high": float(r[2]),
                         "low": float(r[3]), "close": float(r[4]), "volume": float(r[5])}
                        for r in rows if float(r[6]) / 1000 <= time.time()]
                result = {"bars": bars[-limit:], "source": "Binance 公共行情", "demo": False, "interval": interval}
            except Exception as exc:
                errors.append(type(exc).__name__)
            if result is None:
                query = urllib.parse.urlencode({"instId": symbol[:-4] + "-USDT",
                    "bar": {"15m": "15m", "1h": "1H", "4h": "4H", "1d": "1Dutc"}[interval], "limit": limit + 1})
                try:
                    rows = self.fetch("https://www.okx.com/api/v5/market/candles?" + query)["data"]
                    bars = [{"time": int(r[0]) / 1000, "open": float(r[1]), "high": float(r[2]),
                             "low": float(r[3]), "close": float(r[4]), "volume": float(r[5])}
                            for r in rows if str(r[8]) == "1"]
                    result = {"bars": sorted(bars, key=lambda b: b["time"])[-limit:],
                              "source": "OKX 公共行情", "demo": False, "interval": interval}
                except Exception as exc:
                    errors.append(type(exc).__name__)
            if result is None:
                raise RuntimeError(f"{symbol} 的 K 线暂不可用（{' / '.join(errors)}），请稍后重试")
        self._validate_bars(result["bars"])
        with self.lock:
            self.cache[key] = (time.time(), result)
        return result

    @staticmethod
    def _validate_bars(bars):
        if len(bars) < 60:
            raise ValueError("已收盘 K 线不足 60 根，暂不生成建议")
        previous = -1
        for b in bars:
            if any(not math.isfinite(b[k]) or b[k] < 0 for k in ("open", "high", "low", "close", "volume")):
                raise ValueError("行情包含无效数值")
            if b["low"] <= 0 or b["high"] < max(b["open"], b["close"]) or b["low"] > min(b["open"], b["close"]):
                raise ValueError("K 线价格关系异常")
            if b["time"] <= previous:
                raise ValueError("K 线时间顺序异常")
            if not math.isfinite(b["time"]) or not 0 < b["time"] <= time.time():
                raise ValueError("K 线时间无效")
            previous = b["time"]

    def snapshot(self, symbol, horizon):
        periods = {"intraday": ("15m", "1h", "4h"), "swing": ("1h", "4h", "1d"),
                   "position": ("4h", "1d")}[horizon]
        with ThreadPoolExecutor(max_workers=3) as pool:
            series = dict(zip(periods, pool.map(lambda tf: self.candles(symbol, tf), periods)))
        quote = self.quotes([symbol]).get(symbol)
        if not quote:
            raise ValueError("该币种没有可用的实时价格")
        return {"symbol": symbol, "quote": quote, "series": series, "created": time.time()}

    def _demo_bars(self, symbol, interval, limit):
        seconds = INTERVALS[interval]
        end = int(time.time() // seconds) * seconds
        seed = int(hashlib.sha256((symbol + interval).encode()).hexdigest()[:12], 16)
        rng = random.Random(seed)
        base = BASES.get(symbol, 12.8)
        phase = (seed % 70) / 10
        bars, prev = [], base * 0.94
        for i in range(limit):
            trend = 0.94 + i / limit * 0.06
            close = base * (trend + 0.012 * math.sin(i / 6 + phase) + rng.uniform(-0.004, 0.004))
            opening = prev
            bars.append({"time": end - (limit - i) * seconds, "open": opening, "close": close,
                         "high": max(opening, close) * (1 + rng.uniform(0.001, 0.006)),
                         "low": min(opening, close) * (1 - rng.uniform(0.001, 0.006)),
                         "volume": (base and 1_500_000 / base) * rng.uniform(0.5, 2)})
            prev = close
        return bars

    def _demo_quote(self, symbol):
        bars = self._demo_bars(symbol, "1h", 160)
        price = bars[-1]["close"] * (1 + math.sin(time.time() / 40) * 0.0004)
        return {"symbol": symbol, "price": price, "change_pct": (price / bars[-25]["close"] - 1) * 100,
                "high": max(b["high"] for b in bars[-24:]), "low": min(b["low"] for b in bars[-24:]),
                "volume": sum(b["volume"] * b["close"] for b in bars[-24:]),
                "timestamp": time.time(), "source": "演示数据 · 非真实行情", "demo": True}
