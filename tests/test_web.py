from __future__ import annotations

import http.client
import json
import tempfile
import threading
import time
import unittest

from analyst.service import Analyst
from analyst.web import make_server


class WebTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.service = Analyst(cls.tmp.name, demo=True)
        cls.service.poll()
        cls.server = make_server(cls.service, port=0)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()
        cls.service.close()
        cls.tmp.cleanup()

    def request(self, path, data=None, headers=None):
        conn = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=10)
        conn.request("POST" if data is not None else "GET", path,
            body=json.dumps(data) if data is not None else None,
            headers={"Content-Type": "application/json", **(headers or {})})
        response = conn.getresponse()
        result = response.status, dict(response.getheaders()), response.read()
        conn.close()
        return result

    def test_health_and_frontend(self):
        status, headers, body = self.request("/api/health")
        self.assertEqual(status, 200)
        self.assertTrue(json.loads(body)["ok"])
        status, headers, body = self.request("/")
        self.assertEqual(status, 200)
        self.assertIn("AI2B".encode(), body)
        self.assertIn("Content-Security-Policy", headers)

    def test_secret_files_cannot_be_served(self):
        for path in ("/.data/secrets.json", "/../app.py", "/analyst/storage.py", "/.env"):
            self.assertEqual(self.request(path)[0], 404)

    def test_cross_origin_write_rejected(self):
        self.assertEqual(self.request("/api/settings", {}, {"Origin": "https://evil.example"})[0], 403)

    def test_non_loopback_host_rejected(self):
        self.assertEqual(self.request("/api/state", headers={"Host": "evil.example"})[0], 403)
        self.assertEqual(self.request("/api/settings", {}, {"Host": "evil.example", "Origin": "http://evil.example"})[0], 403)

    def test_loopback_hosts_allow_docker_mapped_ports(self):
        for host in ("127.0.0.1:18765", "localhost:18765", "localhost"):
            with self.subTest(host=host):
                self.assertEqual(self.request("/api/health", headers={"Host": host})[0], 200)
        self.assertEqual(self.request("/api/settings", {},
            {"Host": "localhost:18765", "Origin": "http://localhost:18765"})[0], 200)

    def test_malformed_and_disguised_hosts_rejected(self):
        for host in ("localhost.evil.example", "127.0.0.1.evil.example", "user@localhost",
                     "localhost/path", "localhost:bad", "localhost:70000", "localhost:0"):
            with self.subTest(host=host):
                self.assertEqual(self.request("/api/health", headers={"Host": host})[0], 403)

    def test_mapped_port_still_rejects_cross_origin(self):
        self.assertEqual(self.request("/api/settings", {},
            {"Host": "localhost:18765", "Origin": "http://localhost:18766"})[0], 403)

    def test_plain_content_type_rejected(self):
        self.assertEqual(self.request("/api/settings", {}, {"Content-Type": "text/plain"})[0], 415)

    def test_invalid_payload_rejected(self):
        self.assertEqual(self.request("/api/settings", [1, 2])[0], 400)
        self.assertEqual(self.request("/api/watchlist", {"symbol": "<script>"})[0], 400)
        self.assertEqual(self.request("/api/settings", {"capital": -1})[0], 400)

    def test_demo_candles_and_watchlist_roundtrip(self):
        status, _, data = self.request("/api/candles?symbol=BTC&interval=4h")
        self.assertEqual(status, 200)
        self.assertTrue(json.loads(data)["demo"])
        self.assertEqual(self.request("/api/watchlist", {"symbol": "DOGE"})[0], 200)
        self.assertIn("DOGEUSDT", json.loads(self.request("/api/state")[2])["watchlist"])
        self.assertEqual(self.request("/api/watchlist/delete", {"symbol": "DOGE"})[0], 200)

    def test_background_analysis_end_to_end(self):
        status, _, data = self.request("/api/analyze", {"symbol": "ETH"})
        self.assertEqual(status, 202)
        ident = json.loads(data)["job_id"]
        deadline = time.time() + 5
        while time.time() < deadline:
            result = json.loads(self.request("/api/state")[2])
            job = next(j for j in result["jobs"] if j["id"] == ident)
            if job["status"] != "running":
                break
            time.sleep(.02)
        self.assertEqual(job["status"], "done")
        report = json.loads(self.request("/api/reports/" + str(job["report_ids"][0]))[2])
        self.assertEqual(report["symbol"], "ETHUSDT")
        self.assertIn("allocation", report)


if __name__ == "__main__":
    unittest.main()
