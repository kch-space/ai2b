"""Dated RSS headlines, never invented or silently replaced with model knowledge."""
from __future__ import annotations

import copy
import html
import re
import threading
import time
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor
from email.utils import parsedate_to_datetime

FEEDS = (("CoinDesk", "https://www.coindesk.com/arc/outboundfeeds/rss/", "coindesk.com"),
         ("Cointelegraph", "https://cointelegraph.com/rss", "cointelegraph.com"))
ALIASES = {"BTC": ("bitcoin", "btc"), "ETH": ("ethereum", "ether", "eth"),
           "SOL": ("solana", "sol"), "BNB": ("binance", "bnb"), "DOGE": ("dogecoin", "doge"),
           "XRP": ("ripple", "xrp"), "ADA": ("cardano", "ada"), "TRX": ("tron", "trx")}
MACRO = ("federal reserve", "interest rate", "inflation", "cpi", "fomc", "sec", "etf", "hack", "liquidation")


def fetch_feed(url):
    request = urllib.request.Request(url, headers={"User-Agent": "AI2B/0.2", "Accept": "application/rss+xml, application/xml"})
    with urllib.request.urlopen(request, timeout=8) as response:
        data = response.read(1_000_001)
    if len(data) > 1_000_000 or b"<!DOCTYPE" in data.upper() or b"<!ENTITY" in data.upper():
        raise ValueError("不支持的 RSS 内容")
    return data


def plain(value):
    return html.unescape(re.sub(r"<[^>]*>", " ", value or "")).strip()


def parse_feed(data, source, domain, now):
    root = ET.fromstring(data)
    records = []
    for item in root.findall("./channel/item")[:80]:
        try:
            title = plain(item.findtext("title"))[:300]
            url = (item.findtext("link") or "").strip()
            parsed = urllib.parse.urlsplit(url)
            host = (parsed.hostname or "").lower()
            if parsed.scheme != "https" or parsed.username or parsed.password or not (host == domain or host.endswith("." + domain)):
                continue
            date = parsedate_to_datetime(item.findtext("pubDate") or "")
            if date.tzinfo is None:
                continue
            stamp = date.timestamp()
            if not title or not 0 <= now - stamp <= 86400:
                continue
            records.append({"title": title, "url": url, "source": source, "published": stamp,
                            "excerpt": plain(item.findtext("description"))[:600]})
        except (TypeError, ValueError, OverflowError):
            continue
    return records


class News:
    def __init__(self, fetcher=None):
        self.fetch = fetcher or fetch_feed
        self.lock = threading.Lock()
        self.cached = None

    def headlines(self, symbol, demo=False):
        if demo:
            return {"status": "demo", "items": [], "errors": [], "fetched_at": None,
                    "note": "演示模式不获取或虚构真实热点"}
        now = time.time()
        with self.lock:
            cached = self.cached
        if not cached or now - cached[0] >= 300:
            def collect(feed):
                source, url, domain = feed
                try:
                    return parse_feed(self.fetch(url), source, domain, now), None
                except Exception:
                    return [], source + " 新闻源暂不可用"
            with ThreadPoolExecutor(max_workers=2) as pool:
                results = list(pool.map(collect, FEEDS))
            all_items = [item for items, _ in results for item in items]
            errors = [error for _, error in results if error]
            cached = (time.time(), all_items, errors)
            with self.lock:
                self.cached = copy.deepcopy(cached)
        fetched, records, errors = copy.deepcopy(cached)
        base = symbol.removesuffix("USDT").lower()
        aliases = ALIASES.get(base.upper(), (base,))
        def contains(text, terms):
            return any(re.search(r"(?<![a-z0-9])" + re.escape(term) + r"(?![a-z0-9])", text) for term in terms)
        items, seen = [], set()
        for record in sorted(records, key=lambda r: r["published"], reverse=True):
            if not 0 <= now - record["published"] <= 86400 or record["url"] in seen:
                continue
            text = (record["title"] + " " + record["excerpt"]).lower()
            related = contains(text, aliases)
            if not related and not contains(text, MACRO):
                continue
            seen.add(record["url"])
            items.append({**record, "scope": "币种相关" if related else "市场背景", "id": f"N{len(items) + 1}"})
            if len(items) >= 8:
                break
        status = "partial" if errors and items else "ok" if items else "unavailable" if errors else "empty"
        return {"status": status, "items": items, "errors": errors, "fetched_at": fetched,
                "note": "最近 24 小时的媒体标题与摘要；相关性为关键词匹配，未核实全文或因果关系，不覆盖全部新闻和交易所公告"}
