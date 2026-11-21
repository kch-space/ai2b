"""Terminal setup for the Telegram assistant; no network calls during setup."""
from __future__ import annotations

import getpass
import os
import re
import sys

from .integrations import valid_llm_url
from .storage import number, symbol_name

ENABLED = {"telegram_enabled": True, "telegram_chat_enabled": True, "monitor_enabled": True}


def apply_environment(analyst):
    """Environment values initialize settings without overwriting saved choices."""
    saved = analyst.store.get("settings", {})
    fields = {
        "llm_base_url": ("AI2B_LLM_BASE_URL", str),
        "llm_model": ("AI2B_LLM_MODEL", str),
        "telegram_chat_id": ("AI2B_TELEGRAM_CHAT_ID", str),
        "telegram_allowed_user_ids": ("AI2B_TELEGRAM_ALLOWED_USER_IDS", user_ids),
        "monitor_window_minutes": ("AI2B_MONITOR_WINDOW_MINUTES", float),
        "monitor_change_pct": ("AI2B_MONITOR_CHANGE_PCT", float),
        "monitor_cooldown_minutes": ("AI2B_MONITOR_COOLDOWN_MINUTES", float),
        "monitor_report_minutes": ("AI2B_MONITOR_REPORT_MINUTES", float),
        "telegram_daily_ai_calls": ("AI2B_TELEGRAM_DAILY_AI_CALLS", float),
    }
    patch = {}
    for key, (env, convert) in fields.items():
        value = os.environ.get(env, "").strip()
        if value and key not in saved:
            try:
                patch[key] = convert(value)
            except ValueError:
                raise ValueError(f"{env} 配置格式不正确") from None
    if patch:
        analyst.store.save_settings(patch)


def user_ids(value):
    result = list(dict.fromkeys(int(v.strip()) for v in value.split(",") if v.strip()))
    if any(v <= 0 for v in result):
        raise ValueError("用户 ID 必须为正整数，多个 ID 用逗号分隔")
    return result


def startup_error(analyst):
    token = analyst.secrets.get("telegram_bot_token")
    if not re.fullmatch(r"\d+:[A-Za-z0-9_-]+", token):
        return "Telegram Bot Token 缺失或格式不正确"
    try:
        analyst.store.validate_settings(ENABLED)
    except ValueError as exc:
        return str(exc)
    return ""


def ask(label, default="", *, validate=None, secret=False, input_fn=None, secret_fn=None):
    reader = (secret_fn or getpass.getpass) if secret else (input_fn or input)
    hint = "已保存，回车保留" if secret and default else str(default)
    prompt = f"{label}" + (f" [{hint}]" if hint else "") + "："
    while True:
        value = reader(prompt).strip() or default
        try:
            return validate(value) if validate else value
        except (ValueError, TypeError):
            print(f"{label}格式不正确，请重新输入。", flush=True)


def configure(analyst, *, input_fn=None, secret_fn=None):
    input_fn = input_fn or input
    secret_fn = secret_fn or getpass.getpass
    settings = analyst.store.settings()
    patch = {**ENABLED, "analysis_mode": "rules"}

    def prompt(label, default="", **kwargs):
        return ask(label, default, input_fn=input_fn, secret_fn=secret_fn, **kwargs)

    def token(value):
        if not re.fullmatch(r"\d+:[A-Za-z0-9_-]+", value):
            raise ValueError("Token 无效")
        return value

    def chat_id(value):
        if not re.fullmatch(r"-?[1-9]\d*", value):
            raise ValueError("Chat ID 无效")
        return value

    def required(value):
        if not value:
            raise ValueError("不能为空")
        return value

    def choice(values):
        def validate(value):
            if value not in values:
                raise ValueError("选项无效")
            return value
        return validate

    print("\nAI2B 启动配置 · 回车保留已有值，Token/API Key 隐藏输入。", flush=True)
    print("先在 BotFather 创建机器人，并向自己的机器人发送 /start。", flush=True)
    patch["telegram_bot_token"] = prompt("Telegram Bot Token", analyst.secrets.get("telegram_bot_token"),
                                         validate=token, secret=True)
    patch["telegram_chat_id"] = prompt("数字 Chat ID（群聊为负数）", settings["telegram_chat_id"], validate=chat_id)
    existing_users = ",".join(str(v) for v in settings["telegram_allowed_user_ids"])

    def allowed(value):
        ids = user_ids(value)
        if int(patch["telegram_chat_id"]) < 0 and not ids:
            raise ValueError("群聊必须填写允许操作的用户 ID")
        return ids

    patch["telegram_allowed_user_ids"] = prompt("允许操作的用户 ID，逗号分隔（私聊可留空）", existing_users,
                                                validate=allowed)
    use_ai = prompt("启用 AI 对话？y/n", "y" if settings["llm_base_url"] else "n", validate=choice(("y", "n")))
    if use_ai == "y":
        patch["llm_base_url"] = prompt("OpenAI 兼容 API 基础地址", settings["llm_base_url"] or "https://api.openai.com/v1",
                                        validate=valid_llm_url)
        patch["llm_model"] = prompt("模型名称", settings["llm_model"], validate=required)
        from urllib.parse import urlsplit
        local = urlsplit(patch["llm_base_url"]).hostname in ("localhost", "127.0.0.1", "::1")
        patch["llm_api_key"] = prompt("模型 API Key" + ("（本地服务可留空）" if local else ""),
                                      analyst.secrets.get("llm_api_key"), secret=True,
                                      validate=None if local else required)
    else:
        patch.update(llm_base_url="", llm_model="", clear_llm_api_key=True)

    def watches(value):
        symbols = list(dict.fromkeys(symbol_name(v) for v in re.split(r"[,，\s]+", value) if v))
        if len(symbols) > 20:
            raise ValueError("最多20个币种")
        return symbols

    # '-' explicitly clears an existing watchlist; empty input preserves it.
    current = ",".join(s.removesuffix("USDT") for s in analyst.store.watchlist()) or "-"
    symbols = prompt("关注币种，逗号分隔（- 表示不关注）", current,
                     validate=lambda v: [] if v == "-" else watches(v))
    patch["horizon"] = prompt("分析周期 intraday/swing/position", settings["horizon"],
                              validate=choice(("intraday", "swing", "position")))
    fields = (
        ("poll_seconds", "行情轮询间隔（秒）", 10, 300),
        ("monitor_window_minutes", "异动窗口（分钟）", 1, 1440),
        ("monitor_change_pct", "异动涨跌幅阈值（%）", 0.1, 100),
        ("monitor_cooldown_minutes", "同币种推送冷却（分钟）", 1, 1440),
        ("monitor_report_minutes", "定时分析间隔（分钟，0关闭）", 0, 10080),
        ("telegram_daily_ai_calls", "每天 Telegram AI 调用上限（整数）", 1, 1000),
    )
    for key, label, low, high in fields:
        def validate(value, key=key, low=low, high=high):
            result = number(value, key, low, high)
            if key == "monitor_window_minutes" and result * 60 < patch["poll_seconds"] * 2:
                raise ValueError("窗口至少为轮询间隔的两倍")
            if key == "telegram_daily_ai_calls" and result != int(result):
                raise ValueError("调用上限必须是整数")
            return result
        patch[key] = prompt(label, f"{settings[key]:g}", validate=validate)
    daily = prompt("启用每日简报？y/n", "y" if settings["daily_report_enabled"] else "n", validate=choice(("y", "n")))
    patch["daily_report_enabled"] = daily == "y"
    if daily == "y":
        def report_time(value):
            if not re.fullmatch(r"(?:[01]\d|2[0-3]):[0-5]\d", value):
                raise ValueError("使用HH:MM")
            return value
        patch["daily_report_time"] = prompt("每日简报时间（北京时间 HH:MM）", settings["daily_report_time"],
                                            validate=report_time)
    advanced = prompt("配置资金测算参数？y/n", "n", validate=choice(("y", "n")))
    if advanced == "y":
        for key, label, low, high in (
            ("capital", "参考权益（USDT）", 1, 1e10), ("risk_pct", "单笔风险预算（%）", 0.1, 5),
            ("allocation_pct", "单币保证金额度（%）", 1, 100), ("reserve_pct", "保留现金（%）", 0, 95),
            ("leverage", "计划杠杆（整数）", 1, 20),
        ):
            def validate(value, key=key, low=low, high=high):
                result = number(value, key, low, high)
                if key == "leverage" and result != int(result):
                    raise ValueError("杠杆必须为整数")
                return result
            patch[key] = prompt(label, f"{settings[key]:g}", validate=validate)
    analyst.save_settings(patch)
    analyst.store.replace_watchlist(symbols)
    print(f"配置已保存到 {analyst.store.directory}，后续启动可复用。", flush=True)


def ensure_configured(analyst, *, force=False, non_interactive=False):
    error = startup_error(analyst)
    if non_interactive:
        if error:
            raise ValueError(error + "。先执行 python3 app.py --configure；Docker 使用 docker compose run --rm ai2b --configure")
    elif force or error:
        if not sys.stdin.isatty():
            raise ValueError("需要终端交互配置。使用 python3 app.py --configure；Docker 使用 docker compose run --rm ai2b --configure")
        configure(analyst)
    elif sys.stdin.isatty():
        if ask("已有配置，重新配置？y/n", "n", validate=lambda v: v if v in ("y", "n") else invalid()) == "y":
            configure(analyst)
    analyst.store.save_settings(ENABLED)


def invalid():
    raise ValueError("请选择y或n")
