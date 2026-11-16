from __future__ import annotations

import hashlib
import json
import math
import random
import threading
import time
import urllib.parse
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

from .storage import symbol_name

INTERVALS = {"15m": 900, "1h": 3600, "4h": 14400, "1d": 86400}
MARKET = "binance_usdm"
FAPI = "https://fapi.binance.com"
BASES = {"BTCUSDT": 86420, "ETHUSDT": 2848, "SOLUSDT": 143.8, "BNBUSDT": 612.4,
         "DOGEUSDT": 0.168, "XRPUSDT": 1.98, "ADAUSDT": 0.42, "TRXUSDT": 0.28}


def get_json(url, timeout=8):
    req = urllib.request.Request(url, headers={"User-Agent": "AI2B/0.2", "Accept": "application/json"})
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

    def contracts(self):
        with self.lock:
            cached = self.cache.get("contracts")
            if cached and time.time() - cached[0] < 3600:
                return cached[1]
        rows = self.fetch(FAPI + "/fapi/v1/exchangeInfo")["symbols"]
        symbols = {r["symbol"] for r in rows if r.get("contractType") == "PERPETUAL"
                   and r.get("status") == "TRADING" and r.get("quoteAsset") == "USDT"
                   and r.get("marginAsset") == "USDT"}
        if not symbols:
            raise ValueError("没有可用的 USDT 永续合约")
        with self.lock:
            self.cache["contracts"] = (time.time(), symbols)
        return symbols

    def quotes(self, symbols):
        symbols = list(dict.fromkeys(symbol_name(s) for s in symbols))
        if not symbols:
            return {}
        if self.demo:
            return {s: self._demo_quote(s) for s in symbols}
        try:
            supported = self.contracts()
            unknown = [s for s in symbols if s not in supported]
            if len(unknown) == len(symbols):
                raise ValueError("不是正在交易的币安 USDT 永续合约：" + ", ".join(unknown))
            # Same exchange and contract market for every price. Never fall back
            # to spot, another exchange, or synthetic data.
            with ThreadPoolExecutor(max_workers=2) as pool:
                tickers, premiums = list(pool.map(self.fetch, (
                    FAPI + "/fapi/v1/ticker/24hr", FAPI + "/fapi/v1/premiumIndex")))
            marks = {r["symbol"]: r for r in premiums}
            result = {}
            for row in tickers:
                symbol = row.get("symbol")
                if symbol not in symbols or symbol not in supported or symbol not in marks:
                    continue
                mark = marks[symbol]
                quote = {"symbol": symbol, "market": MARKET, "price": float(row["lastPrice"]),
                    "mark_price": float(mark["markPrice"]), "index_price": float(mark["indexPrice"]),
                    "funding_rate": float(mark["lastFundingRate"]),
                    "next_funding_time": float(mark["nextFundingTime"]) / 1000,
                    "mark_timestamp": float(mark["time"]) / 1000,
                    "change_pct": float(row["priceChangePercent"]), "high": float(row["highPrice"]),
                    "low": float(row["lowPrice"]), "volume": float(row["quoteVolume"]),
                    "timestamp": float(row["closeTime"]) / 1000,
                    "source": "Binance USDT 永续合约", "demo": False}
                self._validate_quote(quote)
                result[symbol] = quote
            if not result:
                raise ValueError("没有可用的合约报价")
            with self.lock:
                self.quote_cache.update(result)
            return result
        except Exception as exc:
            reason = f"HTTP {exc.code}" if isinstance(exc, urllib.error.HTTPError) else type(exc).__name__
            raise RuntimeError("币安合约行情不可用（" + reason +
                "）。请检查服务器到 fapi.binance.com 的连通性和地区限制；不会用模拟价格、现货或其他交易所替代。") from exc

    @staticmethod
    def _validate_quote(quote):
        fields = ("price", "change_pct", "high", "low", "volume", "timestamp",
                  "mark_price", "index_price", "funding_rate", "next_funding_time", "mark_timestamp")
        if any(not math.isfinite(quote[k]) for k in fields):
            raise ValueError("报价包含无效数值")
        if (min(quote["price"], quote["low"], quote["mark_price"], quote["index_price"]) <= 0
                or quote["high"] < quote["low"] or quote["volume"] < 0):
            raise ValueError("报价价格关系异常")
        now = time.time()
        if any(not 0 < quote[k] <= now + 60 for k in ("timestamp", "mark_timestamp")):
            raise ValueError("报价时间异常")
        if quote["next_funding_time"] <= 0:
            raise ValueError("资金费结算时间无效")

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
            result = {"bars": self._demo_bars(symbol, interval, limit), "source": "合约演示数据",
                      "demo": True, "interval": interval, "market": MARKET}
        else:
            try:
                if symbol not in self.contracts():
                    raise ValueError("该交易对不是正在交易的 USDT 永续合约")
                query = urllib.parse.urlencode({"symbol": symbol, "interval": interval, "limit": limit + 1})
                rows = self.fetch(FAPI + "/fapi/v1/klines?" + query)
                bars = [{"time": int(r[0]) / 1000, "open": float(r[1]), "high": float(r[2]),
                         "low": float(r[3]), "close": float(r[4]), "volume": float(r[5])}
                        for r in rows if float(r[6]) / 1000 <= time.time()]
                result = {"bars": bars[-limit:], "source": "Binance USDT 永续合约 · 成交价 K 线",
                          "demo": False, "interval": interval, "market": MARKET}
            except Exception as exc:
                reason = f"HTTP {exc.code}" if isinstance(exc, urllib.error.HTTPError) else type(exc).__name__
                raise RuntimeError(f"{symbol} 币安合约 K 线不可用（{reason}），请检查网络与地区限制") from exc
        self._validate_bars(result["bars"])
        with self.lock:
            self.cache[key] = (time.time(), result)
        return result

    def derivatives(self, symbol):
        if self.demo:
            return {"open_interest": 12345.0, "timestamp": time.time(), "demo": True,
                    "source": "合约演示数据", "error": None}
        key = ("open_interest", symbol)
        with self.lock:
            cached = self.cache.get(key)
            if cached and time.time() - cached[0] < 30:
                return cached[1]
        try:
            row = self.fetch(FAPI + "/fapi/v1/openInterest?" + urllib.parse.urlencode({"symbol": symbol}))
            value, stamp = float(row["openInterest"]), float(row["time"]) / 1000
            if not math.isfinite(value) or value < 0 or not math.isfinite(stamp) or not 0 <= time.time() - stamp <= 90:
                raise ValueError("持仓量无效或过期")
            result = {"open_interest": value, "timestamp": stamp, "source": "Binance USDT 永续合约",
                      "demo": False, "error": None}
            with self.lock:
                self.cache[key] = (time.time(), result)
            return result
        except Exception:
            return {"open_interest": None, "timestamp": None, "error": "持仓量暂不可用，不据此判断资金流向"}

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
        return {"symbol": symbol, "quote": quote, "series": series, "created": time.time(),
                "market": MARKET, "derivatives": self.derivatives(symbol)}

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
                "timestamp": time.time(), "source": "合约演示数据 · 非真实行情", "demo": True,
                "market": MARKET, "mark_price": price * 1.0001, "index_price": price * .9999,
                "mark_timestamp": time.time(), "funding_rate": .0001,
                "next_funding_time": (int(time.time() // 28800) + 1) * 28800}
