"""Offline checks for smoke mode and result validation; no Docker or LLM calls."""

import json
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


ROOT = Path(__file__).resolve().parents[2]


class DemoSmokeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        output = ROOT / "courseinsight-server" / "target"
        output.mkdir(exist_ok=True)
        cls.classes = tempfile.TemporaryDirectory(dir=output, prefix="smoke-test-")
        subprocess.run(
            ["javac", "-d", cls.classes.name, str(ROOT / "scripts/DemoSmoke.java")],
            check=True, capture_output=True, timeout=30,
        )

    @classmethod
    def tearDownClass(cls):
        cls.classes.cleanup()

    def run_smoke(self, *, stub=False, service="courseinsight-ai-service",
                  sources=None, advice=None, failed=0):
        sources = sources or ["bert", "bert+rule", "bert"]
        advice = advice or ["llm_api", "local_fallback", "llm_api"]
        state = {"writes": 0, "code": ""}

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *_args):
                pass

            def reply(self, data, status=200):
                payload = json.dumps(data).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def do_GET(self):
                if self.path == "/actuator/health":
                    self.reply({"status": "UP"})
                elif self.path == "/health":
                    self.reply({"status": "UP", "service": service,
                                "activeBackend": "bert"})
                elif self.path == "/api/courses/1":
                    self.reply({"data": {"code": state["code"]}})
                elif self.path.startswith("/api/analysis-batches/1/results"):
                    self.reply({"data": {"total": 3, "items": [
                        {"taskStatus": "SUCCESS", "sentimentSource": source,
                         "adviceSource": advice[index]}
                        for index, source in enumerate(sources)
                    ]}})
                elif self.path == "/api/analysis-batches/1":
                    self.reply({"data": {"status": "COMPLETED", "waitingCount": 0,
                                        "processingCount": 0, "successCount": 3 - failed,
                                        "failedCount": failed}})
                else:
                    self.reply({}, 404)

            def do_POST(self):
                payload = self.rfile.read(int(self.headers["Content-Length"]))
                state["writes"] += 1
                if self.path == "/api/auth/login":
                    self.reply({"data": {"accessToken": "offline-test-token", "role": "ADMIN"}})
                elif self.path == "/api/courses":
                    state["code"] = json.loads(payload)["code"]
                    self.reply({"data": 1}, 201)
                elif self.path == "/api/courses/1/analysis-batches":
                    self.reply({"data": {"batchId": 1, "totalCount": 3}}, 201)
                else:
                    self.reply({}, 404)

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        address = f"http://127.0.0.1:{server.server_port}"
        environment = dict(os.environ, DEMO_BASE_URL=address, AI_SERVICE_BASE_URL=address,
                           BOOTSTRAP_ADMIN_USERNAME="offline-test", BOOTSTRAP_ADMIN_PASSWORD="offline-test")
        try:
            result = subprocess.run(
                ["java", "-cp", self.classes.name, "DemoSmoke", *(["--stub"] if stub else [])],
                cwd=ROOT, env=environment, capture_output=True, text=True, timeout=15,
            )
            return result, state
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_default_accepts_real_ai_and_reports_advice_sources(self):
        result, _ = self.run_smoke()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("real AI analysis results", result.stdout)
        self.assertIn("llm_api=2, local_fallback=1", result.stdout)

    def test_default_rejects_stub_before_writes(self):
        result, state = self.run_smoke(service="courseinsight-performance-ai-stub")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("AI service mode", result.stderr)
        self.assertEqual(state["writes"], 0)

    def test_explicit_stub_checks_deterministic_sources(self):
        result, _ = self.run_smoke(
            stub=True, service="courseinsight-performance-ai-stub",
            sources=["performance_stub"] * 3, advice=["performance_stub"] * 3,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("deterministic Stub results", result.stdout)

    def test_real_ai_rejects_unrecognized_source(self):
        result, _ = self.run_smoke(sources=["bert", "performance_stub", "bert"])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("recognizedSources=2", result.stderr)

    def test_real_ai_rejects_missing_advice(self):
        result, _ = self.run_smoke(advice=["llm_api", "unknown", "llm_api"])
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("validAdvice=2", result.stderr)

    def test_completed_batch_still_requires_all_successful(self):
        result, _ = self.run_smoke(failed=1)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("unexpected counts", result.stderr)


if __name__ == "__main__":
    unittest.main()
