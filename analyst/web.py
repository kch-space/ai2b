from __future__ import annotations

import json
import logging
import mimetypes
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

log = logging.getLogger("ai2b")
STATIC = Path(__file__).resolve().parent.parent / "static"


def make_server(service, host="127.0.0.1", port=8765):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, fmt, *args):
            # Do not log POST request bodies, API tokens, or upstream responses.
            log.debug(fmt, *args)

        def reply(self, code, data):
            payload = json.dumps(data, ensure_ascii=False, allow_nan=False).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.end_headers()
            self.wfile.write(payload)

        def valid_host(self):
            allowed = {f"127.0.0.1:{self.server.server_port}", f"localhost:{self.server.server_port}"}
            if self.server.server_port == 80:
                allowed.update({"127.0.0.1", "localhost"})
            return self.headers.get("Host", "").lower() in allowed

        def do_GET(self):
            if not self.valid_host():
                return self.reply(403, {"error": "仅允许本机地址访问"})
            route = urllib.parse.urlsplit(self.path)
            query = urllib.parse.parse_qs(route.query)
            try:
                if route.path == "/api/state":
                    return self.reply(200, service.state())
                if route.path == "/api/health":
                    return self.reply(200, {"ok": True, "version": "0.1.0"})
                if route.path == "/api/candles":
                    symbol = query.get("symbol", ["BTCUSDT"])[0]
                    interval = query.get("interval", ["4h"])[0]
                    return self.reply(200, service.market.candles(symbol, interval))
                if route.path.startswith("/api/reports/"):
                    return self.reply(200, service.store.report(route.path.rsplit("/", 1)[-1]))
                if route.path.startswith("/api/"):
                    return self.reply(404, {"error": "接口不存在"})
                allowed = {"/": "index.html", "/index.html": "index.html", "/app.js": "app.js", "/styles.css": "styles.css", "/favicon.svg": "favicon.svg"}
                if route.path not in allowed:
                    return self.reply(404, {"error": "页面不存在"})
                path = STATIC / allowed[route.path]
                payload = path.read_bytes()
                self.send_response(200)
                self.send_header("Content-Type", (mimetypes.guess_type(str(path))[0] or "text/plain") + "; charset=utf-8")
                self.send_header("Content-Length", str(len(payload)))
                self.send_header("Cache-Control", "no-cache")
                self.send_header("X-Content-Type-Options", "nosniff")
                self.send_header("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'")
                self.end_headers()
                self.wfile.write(payload)
            except (ValueError, KeyError) as exc:
                self.reply(400, {"error": str(exc)[:500]})
            except Exception as exc:
                log.warning("GET %s failed: %s", route.path, type(exc).__name__)
                self.reply(502, {"error": str(exc)[:500] if isinstance(exc, RuntimeError) else "暂时无法获取数据，请稍后重试"})

        def do_POST(self):
            if not self.valid_host():
                return self.reply(403, {"error": "仅允许本机地址访问"})
            origin = self.headers.get("Origin")
            expected = "http://" + self.headers.get("Host", "")
            if origin and origin != expected:
                return self.reply(403, {"error": "来源校验失败"})
            if self.headers.get("Content-Type", "").split(";")[0] != "application/json":
                return self.reply(415, {"error": "请使用 application/json"})
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= 32000:
                    return self.reply(413, {"error": "请求内容过大或为空"})
                data = json.loads(self.rfile.read(length))
                if not isinstance(data, dict):
                    raise ValueError("请求须为 JSON 对象")
                route = urllib.parse.urlsplit(self.path).path
                if route == "/api/settings":
                    return self.reply(200, service.save_settings(data))
                if route == "/api/mode":
                    service.switch_mode(data.get("demo"))
                elif route == "/api/refresh":
                    service.refresh()
                elif route == "/api/watchlist":
                    symbol = service.store.add_symbol(data.get("symbol", ""))
                    service.refresh()
                    return self.reply(200, {"symbol": symbol})
                elif route == "/api/watchlist/delete":
                    service.store.delete_symbol(data.get("symbol", ""))
                    service.refresh()
                elif route == "/api/positions":
                    service.store.save_position(data)
                    service.refresh()
                elif route == "/api/positions/delete":
                    service.store.delete_position(data.get("id"))
                elif route == "/api/alerts":
                    service.store.add_alert(data)
                    service.refresh()
                elif route == "/api/alerts/delete":
                    service.store.delete_alert(data.get("id"))
                elif route == "/api/analyze":
                    return self.reply(202, {"job_id": service.submit("analysis", data.get("symbol", "BTCUSDT"))})
                elif route == "/api/briefing":
                    return self.reply(202, {"job_id": service.submit("briefing")})
                elif route == "/api/telegram/test":
                    service.telegram.send("AI2B 已连接 ✓\n你的个人行情分析师已准备就绪。\n此消息用于验证 Telegram 推送配置。")
                elif route == "/api/telegram/report":
                    service.telegram.send_report(service.store.report(data.get("id")))
                else:
                    return self.reply(404, {"error": "接口不存在"})
                self.reply(200, {"ok": True})
            except (ValueError, TypeError, KeyError, json.JSONDecodeError) as exc:
                self.reply(400, {"error": str(exc)[:500]})
            except RuntimeError as exc:
                self.reply(502, {"error": str(exc)[:500]})
            except Exception as exc:
                log.warning("POST failed: %s", type(exc).__name__)
                self.reply(500, {"error": "操作未完成，请检查输入或查看服务日志"})

    server = ThreadingHTTPServer((host, port), Handler)
    server.daemon_threads = True
    return server
