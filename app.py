#!/usr/bin/env python3
"""Start AI2B with Python 3.10+; no third-party runtime packages required."""
from __future__ import annotations

import argparse
import logging
import os
import signal
from pathlib import Path

from analyst.service import Analyst
from analyst.web import make_server


def load_env(path):
    if not path.exists():
        return
    for line in path.read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            if key.strip().startswith("AI2B_"):
                os.environ.setdefault(key.strip(), value.strip().strip("\"'"))


def main():
    root = Path(__file__).resolve().parent
    load_env(root / ".env")
    parser = argparse.ArgumentParser(description="AI2B · 个人币圈分析师")
    parser.add_argument("--host", default="127.0.0.1", help="监听地址；容器内使用 0.0.0.0")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--demo", action="store_true", help="显式使用合成演示行情")
    parser.add_argument("--data-dir", default=str(root / ".data"))
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    analyst = Analyst(args.data_dir, demo=args.demo)
    env_settings = {"llm_base_url": os.environ.get("AI2B_LLM_BASE_URL", ""),
                    "llm_model": os.environ.get("AI2B_LLM_MODEL", ""),
                    "telegram_chat_id": os.environ.get("AI2B_TELEGRAM_CHAT_ID", "")}
    # Saved UI configuration takes priority over initial environment defaults.
    saved = analyst.store.get("settings", {})
    defaults = {k: v for k, v in env_settings.items() if v and k not in saved}
    if defaults:
        analyst.store.save_settings(defaults)
    try:
        server = make_server(analyst, host=args.host, port=args.port)
    except OSError as exc:
        raise SystemExit(f"无法启动：{exc}。可使用 --port 8766 更换端口。") from None
    analyst.start()
    print(f"\n  AI2B · 个人币圈分析师\n  http://127.0.0.1:{server.server_port}\n  {'演示模式 · 非真实行情' if args.demo else '真实行情 · 公共只读接口'}\n  Ctrl+C 停止\n", flush=True)
    signal.signal(signal.SIGTERM, signal.default_int_handler)
    try:
        server.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt:
        print("\nAI2B 正在停止…")
    finally:
        analyst.close()
        server.server_close()


if __name__ == "__main__":
    main()
