"""Local health endpoint, bound to 127.0.0.1 only (``LP_EDGE_HEALTH_PORT``, 8765).

``GET /health`` answers the agent's state as JSON (200 while the main loop runs, 503 when it is stalled or the
credentials were refused). ``GET /release`` answers the release the configuration asks for, after its signature was
checked, for the host's updater (404 when there is none). No camera address, token or secret is ever in an answer.
"""

from __future__ import annotations

import json
import logging
import threading
from collections.abc import Callable
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

log = logging.getLogger(__name__)

HOST = "127.0.0.1"


class HealthServer:
    def __init__(
        self,
        port: int,
        status: Callable[[], tuple[int, dict[str, Any]]],
        release: Callable[[], dict[str, Any] | None],
    ) -> None:
        self._port = port
        self._status = status
        self._release = release
        self._server: ThreadingHTTPServer | None = None
        self._thread: threading.Thread | None = None

    @property
    def port(self) -> int:
        return self._server.server_address[1] if self._server else self._port

    def start(self) -> None:
        status, release = self._status, self._release

        class Handler(BaseHTTPRequestHandler):
            server_version = "lp-edge"
            sys_version = ""

            def do_GET(self) -> None:  # noqa: N802 - http.server API
                try:
                    if self.path.split("?")[0] == "/health":
                        code, body = status()
                        self._send(code, body)
                    elif self.path.split("?")[0] == "/release":
                        body = release()
                        self._send(200, body) if body else self._send(404, {"message": "no release"})
                    else:
                        self._send(404, {"message": "not found"})
                except Exception:  # noqa: BLE001
                    log.exception("health endpoint failed")
                    self._send(500, {"message": "internal error"})

            def _send(self, code: int, body: dict[str, Any]) -> None:
                data = json.dumps(body, default=str).encode("utf-8")
                self.send_response(code)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.send_header("Cache-Control", "no-store")
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, format: str, *args: Any) -> None:  # noqa: A002
                pass

        self._server = ThreadingHTTPServer((HOST, self._port), Handler)
        self._server.daemon_threads = True
        self._thread = threading.Thread(target=self._server.serve_forever, name="health", daemon=True)
        self._thread.start()
        log.info("health endpoint on http://%s:%d/health", HOST, self.port)

    def stop(self) -> None:
        if self._server is not None:
            self._server.shutdown()
            self._server.server_close()
            self._server = None
