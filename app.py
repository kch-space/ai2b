#!/usr/bin/env python3
"""Start the Telegram market assistant with terminal configuration."""
from __future__ import annotations

import argparse
import logging
import os
import signal
from pathlib import Path

from analyst.configure import apply_environment, ensure_configured
from analyst.service import Analyst


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
    parser = argparse.ArgumentParser(description="AI2B · Telegram 行情分析助手")
    parser.add_argument("--configure", action="store_true", help="交互配置并保存，然后退出")
    parser.add_argument("--non-interactive", action="store_true", help="复用保存配置或环境参数，不询问")
    parser.add_argument("--demo", action="store_true", help="显式使用合成演示行情")
    parser.add_argument("--data-dir", default=str(root / ".data"))
    args = parser.parse_args()
    if args.configure and args.non_interactive:
        parser.error("--configure 与 --non-interactive 不能同时使用")
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    analyst = Analyst(args.data_dir, demo=args.demo)
    try:
        apply_environment(analyst)
        ensure_configured(analyst, force=args.configure, non_interactive=args.non_interactive)
    except (ValueError, EOFError, KeyboardInterrupt) as exc:
        raise SystemExit(str(exc) or "配置已取消，程序未启动") from None
    if args.configure:
        return
    signal.signal(signal.SIGTERM, signal.default_int_handler)
    analyst.start()
    print("AI2B · Telegram 行情助手已启动" + ("（演示数据）" if args.demo else ""), flush=True)
    try:
        analyst.stop_event.wait()
    except KeyboardInterrupt:
        print("AI2B 正在停止…", flush=True)
    finally:
        analyst.close()


if __name__ == "__main__":
    main()
