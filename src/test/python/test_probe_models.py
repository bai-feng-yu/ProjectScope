import importlib.util
import contextlib
import io
import json
import pathlib
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


SCRIPT = pathlib.Path(__file__).parents[3] / "scripts" / "probe_models.py"
SPEC = importlib.util.spec_from_file_location("probe_models", SCRIPT)
probe_models = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(probe_models)


class ProbeModelsTests(unittest.TestCase):
    def test_build_models_url_from_base(self):
        self.assertEqual(
            probe_models.build_models_url("https://example.test/v1/", "models"),
            "https://example.test/v1/models",
        )

    def test_build_models_url_from_chat_endpoint(self):
        self.assertEqual(
            probe_models.build_models_url(
                "https://example.test/v1/chat/completions", "models"
            ),
            "https://example.test/v1/models",
        )

    def test_find_models_in_common_shapes(self):
        expected = [{"id": "model-a"}]
        self.assertEqual(probe_models.find_models({"data": expected}), expected)
        self.assertEqual(probe_models.find_models({"models": expected}), expected)
        self.assertEqual(probe_models.find_models(expected), expected)

    def test_custom_header_requires_colon(self):
        with self.assertRaises(ValueError):
            probe_models.parse_headers(["broken-header"])

    def test_main_fetches_models_and_sends_bearer_token(self):
        received = {}

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                received["path"] = self.path
                received["authorization"] = self.headers.get("Authorization")
                body = json.dumps({"data": [{"id": "model-a"}, {"id": "model-b"}]})
                self.send_response(200)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(body.encode())))
                self.end_headers()
                self.wfile.write(body.encode())

            def log_message(self, format, *args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever)
        thread.start()
        stdout = io.StringIO()
        stderr = io.StringIO()
        try:
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                result = probe_models.main(
                    [f"http://127.0.0.1:{server.server_port}/v1", "--api-key", "secret"]
                )
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

        self.assertEqual(result, 0)
        self.assertEqual(stdout.getvalue(), "model-a\nmodel-b\n")
        self.assertEqual(received["path"], "/v1/models")
        self.assertEqual(received["authorization"], "Bearer secret")


if __name__ == "__main__":
    unittest.main()
