from __future__ import annotations

import math
import time

from .market import INTERVALS


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


def allocation(entry, stop, settings, occupied=0.0, enabled=True, asset_occupied=0.0):
    """Spot notional: min(risk budget / stop+cost distance, leg cap, cash room)."""
    capital = settings["capital"]
    risk_budget = capital * settings["risk_pct"] / 100
    leg_cap = capital * settings["allocation_pct"] / 100
    asset_room = max(0, leg_cap - asset_occupied)
    reserve = capital * settings["reserve_pct"] / 100
    available = max(0, capital - reserve - occupied)
    distance = (entry - stop) / entry if entry > 0 else 0
    cost_pct = settings["round_trip_cost_bps"] / 10000
    amount = min(risk_budget / (distance + cost_pct), asset_room, available) if distance > 0 and enabled else 0
    amount = math.floor(max(0, amount) * 100) / 100
    return {"capital": capital, "risk_budget": risk_budget, "leg_cap": leg_cap,
            "asset_occupied": asset_occupied, "asset_room": asset_room,
            "reserve": reserve, "occupied": occupied, "available": available, "amount": amount,
            "quantity": amount / entry if entry else 0, "stop_risk": amount * distance,
            "estimated_cost": amount * cost_pct, "planned_loss": amount * (distance + cost_pct),
            "risk_pct": settings["risk_pct"], "allocation_pct": settings["allocation_pct"],
            "formula": "投入金额 = min(单笔风险预算 ÷ (止损幅度 + 预估往返成本率), 单币上限减已有该币持仓, 可用资金)"}


def build_report(snapshot, settings, positions=None, quotes=None):
    symbol, quote, series = snapshot["symbol"], snapshot["quote"], snapshot["series"]
    periods = {tf: indicators(data["bars"]) for tf, data in series.items()}
    primary_tf = {"intraday": "1h", "swing": "4h", "position": "1d"}[settings["horizon"]]
    primary = periods[primary_tf]
    price = quote["price"]
    now = time.time()
    warnings = []
    stale = now - quote["timestamp"] > 90
    positions = positions or []
    quotes = {**(quotes or {}), symbol: quote}
    for position in positions:
        mark = quotes.get(position["symbol"])
        if not mark or now - mark["timestamp"] > 90 or mark["demo"] != quote["demo"]:
            stale = True
            warnings.append(f"{position['symbol']} 持仓缺少同模式的新报价，暂停新增投入测算")
    for tf, data in series.items():
        last_time = data["bars"][-1]["time"]
        if now - (last_time + INTERVALS[tf]) > INTERVALS[tf] * 1.5:
            stale = True
            warnings.append(f"{tf} K 线过期")
        bars = data["bars"]
        if any(b["time"] - a["time"] > INTERVALS[tf] * 1.5 for a, b in zip(bars[-60:], bars[-59:])):
            stale = True
            warnings.append(f"{tf} 最近 K 线存在缺口")
    if now - quote["timestamp"] > 90:
        warnings.append("实时价格超过 90 秒未更新")
    up = sum(p["trend"] == "up" for p in periods.values())
    down = sum(p["trend"] == "down" for p in periods.values())
    score = up * 2 - down * 2
    score += 1 if 45 <= primary["rsi"] <= 65 else -1 if primary["rsi"] > 75 else 0
    signal = "watch_long" if up >= 2 and primary["rsi"] < 72 else "reduce_risk" if down >= 2 else "wait"
    if stale:
        signal = "wait"
    labels = {"watch_long": "等待突破确认", "reduce_risk": "优先检查持仓风险", "wait": "保持观察"}
    trend_labels = {"up": "上行", "down": "下行", "range": "震荡"}
    support, resistance = primary["support"], primary["resistance"]
    volatility = primary["atr"]
    entry = max(price, resistance) + volatility * 0.1
    stop = max(entry * 0.01, min(support - volatility * 0.3, entry - volatility * 1.5))
    risk_distance = entry - stop
    target = entry + risk_distance * 2
    occupied = sum(p["quantity"] * quotes.get(p["symbol"], {}).get("price", p["entry"]) for p in positions)
    asset_occupied = sum(p["quantity"] * price for p in positions if p["symbol"] == symbol)
    sizing = allocation(entry, stop, settings, occupied=occupied, enabled=signal == "watch_long" and not stale,
                        asset_occupied=asset_occupied)
    bullish = [f"{tf} 价格位于 EMA20、EMA50 上方，趋势偏上行" for tf, p in periods.items() if p["trend"] == "up"]
    bearish = [f"{tf} 价格低于 EMA20、EMA50，趋势偏弱" for tf, p in periods.items() if p["trend"] == "down"]
    if primary["volume_ratio"] < 1.2:
        bearish.append(f"{primary_tf} 成交量仅为此前 20 根均值的 {primary['volume_ratio']:.2f} 倍，突破确认不足")
    if primary["rsi"] > 70:
        bearish.append(f"{primary_tf} RSI 达到 {primary['rsi']:.1f}，需留意追高风险")
    if not bullish:
        bullish.append("尚未观察到明确的多周期上行共振")
    if not bearish:
        bearish.append("历史指标无法覆盖突发消息、跳空与流动性变化")
    if quote["demo"]:
        warnings.append("当前使用合成演示数据；所有价位和投入金额仅用于功能演示")
    summary = (f"{symbol[:-4]} 在 {primary_tf} 周期呈{trend_labels[primary['trend']]}结构，"
               f"{up}/{len(periods)} 个周期偏上行。{labels[signal]}，关注关键价位与成交量的共同确认。")
    if stale:
        summary = "部分行情已过期或缺失，本次报告仅保留观察信息，暂停投入金额建议。"
    position = next((p for p in positions if p["symbol"] == symbol), None)
    diagnosis = None
    if position:
        value = position["quantity"] * price
        diagnosis = {**position, "value": value, "pnl": (price - position["entry"]) * position["quantity"],
                     "pnl_pct": (price / position["entry"] - 1) * 100,
                     "note": "价格已低于观察支撑，需重新检查原持仓逻辑" if price < support else "价格仍在观察支撑上方，按预先设定的失效条件跟踪"}
    return {"symbol": symbol, "created": now, "data_asof": quote["timestamp"],
            "source": quote["source"], "demo": quote["demo"], "stale": stale,
            "engine": "rules", "horizon": settings["horizon"], "primary_tf": primary_tf,
            "price": price, "change_pct": quote["change_pct"], "signal": signal,
            "signal_label": labels[signal], "summary": summary, "score": score,
            "bullish": bullish, "bearish": bearish, "warnings": warnings,
            "unknowns": ["本版未纳入实时新闻与衍生品资金费率", "指标判断与模型文字均不是经校准的胜率"],
            "timeframes": periods, "levels": {"support": support, "resistance": resistance,
                "entry": entry, "stop": stop, "target": target, "reward_risk": 2.0},
            "trigger": f"等待 {primary_tf} 已收盘 K 线站上 {entry:,.6g}，且成交量高于此前 20 根均值的 1.2 倍，再重新评估。",
            "invalidation": f"价格跌破 {stop:,.6g} 或多周期结构转弱，当前偏多观察计划失效。",
            "allocation": sizing, "position": diagnosis,
            "disclaimer": "金额依据你的计划资金和风险参数计算；止损滑点可能使实际损失超过预算。"}
