from __future__ import annotations

import math
import time

from .market import INTERVALS, MARKET


def ema(values, period):
    if not values:
        raise ValueError("缺少价格序列")
    current = values[0]
    alpha = 2 / (period + 1)
    for value in values[1:]:
        current += alpha * (value - current)
    return current


def rsi(values, period=14):
    if len(values) <= period:
        raise ValueError("RSI 数据不足")
    changes = [b - a for a, b in zip(values, values[1:])]
    gain = sum(max(d, 0) for d in changes[:period]) / period
    loss = sum(max(-d, 0) for d in changes[:period]) / period
    for delta in changes[period:]:
        gain = (gain * (period - 1) + max(delta, 0)) / period
        loss = (loss * (period - 1) + max(-delta, 0)) / period
    if gain == loss == 0:
        return 50.0
    return 100.0 if loss == 0 else 100 - 100 / (1 + gain / loss)


def atr(bars, period=14):
    ranges = [max(b["high"] - b["low"], abs(b["high"] - a["close"]), abs(b["low"] - a["close"]))
              for a, b in zip(bars, bars[1:])]
    value = sum(ranges[:period]) / period
    for tr in ranges[period:]:
        value = (value * (period - 1) + tr) / period
    return value


def indicators(bars):
    closes = [b["close"] for b in bars]
    fast, slow = ema(closes, 20), ema(closes, 50)
    latest = closes[-1]
    trend = "up" if latest > fast > slow else "down" if latest < fast < slow else "range"
    volume_avg = sum(b["volume"] for b in bars[-21:-1]) / 20
    return {"price": latest, "ema20": fast, "ema50": slow, "rsi": rsi(closes), "atr": atr(bars),
            "volume_ratio": bars[-1]["volume"] / volume_avg if volume_avg > 0 else 0,
            "support": min(b["low"] for b in bars[-25:-1]),
            "resistance": max(b["high"] for b in bars[-25:-1]), "trend": trend}


def position_metrics(position, quote=None):
    futures = position.get("market", "spot") == MARKET
    side = position.get("side", "long") if futures else "long"
    leverage = position.get("leverage", 1) if futures else 1
    mark = quote.get("mark_price" if futures else "price") if quote else None
    fresh = bool(quote and quote.get("market") == MARKET and
                 time.time() - quote["timestamp"] <= 90 and
                 time.time() - quote.get("mark_timestamp", 0) <= 90)
    # A legacy spot holding is not valued using futures quotes.
    mark = mark if fresh and futures else None
    sign = -1 if side == "short" else 1
    margin = position["quantity"] * position["entry"] / leverage
    pnl = (mark - position["entry"]) * position["quantity"] * sign if mark is not None else None
    return {**position, "mark": mark, "margin": margin, "notional": position["quantity"] * (mark or position["entry"]),
            "pnl": pnl, "pnl_pct": pnl / margin * 100 if pnl is not None and margin else None}


def allocation(entry, stop, settings, occupied=0.0, enabled=True, asset_occupied=0.0, side="long", funding_rate=0.0):
    """USDT perpetual: risk-limited notional, capped by available initial margin.

    No liquidation price is inferred from leverage: tiers, account collateral,
    maintenance margin and the exchange account are deliberately unavailable.
    """
    capital, leverage = settings["capital"], settings["leverage"]
    risk_budget = capital * settings["risk_pct"] / 100
    leg_cap = capital * settings["allocation_pct"] / 100
    asset_room = max(0, leg_cap - asset_occupied)
    reserve = capital * settings["reserve_pct"] / 100
    available = max(0, capital - reserve - occupied)
    distance = ((entry - stop) if side == "long" else (stop - entry)) / entry if entry > 0 else 0
    cost_pct = settings["round_trip_cost_bps"] / 10000
    margin_guard = distance + cost_pct >= .8 / leverage
    amount = min(risk_budget / (distance + cost_pct), asset_room * leverage, available * leverage) if distance > 0 and enabled and not margin_guard else 0
    amount = math.floor(max(0, amount) * 100) / 100
    return {"capital": capital, "risk_budget": risk_budget, "leg_cap": leg_cap,
            "asset_occupied": asset_occupied, "asset_room": asset_room, "leverage": leverage,
            "reserve": reserve, "occupied": occupied, "available": available, "amount": amount,
            "notional": amount, "margin_amount": amount / leverage, "side": side,
            "quantity": amount / entry if entry else 0, "stop_risk": amount * max(0, distance),
            "estimated_cost": amount * cost_pct, "planned_loss": amount * (max(0, distance) + cost_pct),
            "next_funding_estimate": amount * funding_rate * (1 if side == "long" else -1),
            "margin_guard": margin_guard, "risk_pct": settings["risk_pct"], "allocation_pct": settings["allocation_pct"],
            "formula": "名义仓位 = min(单笔风险预算 ÷ (止损幅度 + 预估往返成本率), 单币剩余保证金额度 × 杠杆, 可用保证金 × 杠杆)"}


def build_report(snapshot, settings, positions=None, quotes=None):
    symbol, quote, series = snapshot["symbol"], snapshot["quote"], snapshot["series"]
    if snapshot.get("market") != MARKET or quote.get("market") != MARKET or any(
            data.get("market") != MARKET or data["demo"] != quote["demo"] for data in series.values()):
        raise ValueError("行情市场或演示模式不一致，拒绝生成合约建议")
    periods = {tf: indicators(data["bars"]) for tf, data in series.items()}
    primary_tf = {"intraday": "1h", "swing": "4h", "position": "1d"}[settings["horizon"]]
    primary, price, now = periods[primary_tf], quote["price"], time.time()
    warnings = []
    stale = now - quote["timestamp"] > 90 or now - quote["mark_timestamp"] > 90
    legacy = [p for p in (positions or []) if p.get("market", "spot") != MARKET]
    positions = [p for p in (positions or []) if p.get("market") == MARKET]
    quotes = {**(quotes or {}), symbol: quote}
    if legacy:
        warnings.append("旧现货持仓已排除合约保证金与盈亏测算，请重新核对账户权益")
    for position in positions:
        mark = quotes.get(position["symbol"])
        if (not mark or mark.get("market") != MARKET or now - mark["timestamp"] > 90
                or now - mark.get("mark_timestamp", 0) > 90 or mark["demo"] != quote["demo"]):
            stale = True
            warnings.append(f"{position['symbol']} 持仓缺少同模式的新合约报价，暂停新增仓位测算")
    for tf, data in series.items():
        bars = data["bars"]
        if now - (bars[-1]["time"] + INTERVALS[tf]) > INTERVALS[tf] * 1.5:
            stale = True
            warnings.append(f"{tf} K 线过期")
        if any(b["time"] - a["time"] > INTERVALS[tf] * 1.5 for a, b in zip(bars[-60:], bars[-59:])):
            stale = True
            warnings.append(f"{tf} 最近 K 线存在缺口")
    if stale:
        warnings.append("最新成交价、标记价或持仓行情已过期/缺失，暂停新增仓位")
    derivatives = snapshot.get("derivatives", {})
    if derivatives.get("error"):
        warnings.append(derivatives["error"])
    up = sum(p["trend"] == "up" for p in periods.values())
    down = sum(p["trend"] == "down" for p in periods.values())
    signal = "watch_long" if up >= 2 and primary["rsi"] < 72 else "watch_short" if down >= 2 and primary["rsi"] > 28 else "wait"
    if stale:
        signal = "wait"
    side = "short" if signal == "watch_short" else "long"
    labels = {"watch_long": "偏多 · 等待突破确认", "watch_short": "偏空 · 等待跌破确认", "wait": "保持观察 · 暂不开新仓"}
    trend_labels = {"up": "上行", "down": "下行", "range": "震荡"}
    support, resistance, volatility = primary["support"], primary["resistance"], primary["atr"]
    if side == "short":
        entry = min(price, support) - volatility * .1
        stop = max(resistance + volatility * .3, entry + volatility * 1.5)
        target = entry - (stop - entry) * 2
    else:
        entry = max(price, resistance) + volatility * .1
        stop = max(entry * .01, min(support - volatility * .3, entry - volatility * 1.5))
        target = entry + (entry - stop) * 2
    if min(entry, stop, target) <= 0:
        signal = "wait"
        warnings.append("观察价位不适用，暂停新增仓位")
    metrics = [position_metrics(p, quotes.get(p["symbol"])) for p in positions]
    occupied = sum(p["margin"] for p in metrics)
    asset_occupied = sum(p["margin"] for p in metrics if p["symbol"] == symbol)
    opposite = any(p["symbol"] == symbol and p["side"] != side for p in metrics)
    if opposite:
        warnings.append("已有反向持仓；本版按单向净持仓记录，暂停叠加反向新仓")
    sizing = allocation(entry, stop, settings, occupied=occupied,
        enabled=signal != "wait" and not stale and not opposite, asset_occupied=asset_occupied,
        side=side, funding_rate=quote["funding_rate"])
    if sizing["margin_guard"]:
        warnings.append("止损幅度加成本已达到初始保证金比例的 80%，本次暂停新增仓位；这不是强平价测算")
    bullish = [f"{tf} 成交价位于 EMA20、EMA50 上方，趋势偏上行" for tf, p in periods.items() if p["trend"] == "up"]
    bearish = [f"{tf} 成交价低于 EMA20、EMA50，趋势偏下行" for tf, p in periods.items() if p["trend"] == "down"]
    if primary["volume_ratio"] < 1.2:
        warnings.append(f"{primary_tf} 成交量为此前 20 根均值的 {primary['volume_ratio']:.2f} 倍，尚未满足 1.2 倍确认条件")
    if not bullish:
        bullish.append("尚未观察到明确的多周期上行共振")
    if not bearish:
        bearish.append("尚未观察到明确的多周期下行共振")
    if quote["demo"]:
        warnings.append("合成合约演示数据；所有价位、资金费率与仓位金额仅用于功能演示")
    summary = (f"{symbol[:-4]} USDT 永续在 {primary_tf} 呈{trend_labels[primary['trend']]}结构，"
               f"{up}/{len(periods)} 个周期上行、{down}/{len(periods)} 个周期下行。{labels[signal]}。")
    if stale:
        summary = "合约行情已过期或缺失，仅保留观察信息，暂停新增仓位建议。"
    diagnosis = next((p for p in metrics if p["symbol"] == symbol), None)
    if diagnosis:
        diagnosis["note"] = "盈亏按标记价估算；保证金按开仓价/记录杠杆估算，未连接真实账户，不含资金费与手续费"
    direction = "short" if signal == "watch_short" else "long" if signal == "watch_long" else "none"
    trigger = (f"等待 {primary_tf} 已收盘成交价 K 线{'跌破' if side == 'short' else '站上'} {entry:,.6g}，"
               "且成交量高于此前 20 根均值 1.2 倍，再重新生成报告评估；当前未确认。")
    if direction == "none":
        trigger = "当前尚未形成可用的多空条件计划，等待趋势、RSI 与成交量重新确认后再生成报告。"
    invalidation = f"成交价{'升破' if side == 'short' else '跌破'} {stop:,.6g} 或多周期结构转向，当前观察计划失效。"
    if direction == "none":
        invalidation = "当前没有有效开仓计划，不应将观察支撑阻力当作实际订单止损。"
    return {"symbol": symbol, "market": MARKET, "created": now, "data_asof": quote["timestamp"],
            "valid_until": min(now + 300, quote["timestamp"] + 300, quote["mark_timestamp"] + 300),
            "source": quote["source"], "demo": quote["demo"], "stale": stale,
            "engine": "rules", "requested_mode": "rules", "horizon": settings["horizon"], "primary_tf": primary_tf,
            "price": price, "change_pct": quote["change_pct"], "signal": signal, "direction": direction,
            "signal_label": labels[signal], "summary": summary, "score": up * 2 - down * 2,
            "bullish": bullish, "bearish": bearish, "warnings": warnings,
            "unknowns": ["未读取交易所账户、保证金档位与强平价；全仓/逐仓风险需在币安核对", "规则未经收益回测；热点覆盖不完整，模型文字不是经校准的胜率"],
            "timeframes": periods, "levels": {"support": support, "resistance": resistance,
                "entry": entry, "stop": stop, "target": target, "reward_risk": 2.0, "side": side},
            "trigger": trigger, "invalidation": invalidation, "allocation": sizing, "position": diagnosis,
            "derivatives": {**derivatives, "mark_price": quote["mark_price"], "index_price": quote["index_price"],
                "mark_timestamp": quote["mark_timestamp"], "funding_rate": quote["funding_rate"],
                "next_funding_time": quote["next_funding_time"]},
            "disclaimer": "人工资金假设与条件计划，非当前开仓指令。保证金与名义仓位分别计算，不是账户可用余额；止损不保证成交，杠杆、资金费与跳空可导致超额损失。"}
