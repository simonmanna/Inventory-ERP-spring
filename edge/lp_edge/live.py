"""Live view: JPEG frames pushed to ``POST /agents/live/{sessionId}/frames`` until told to stop.

At most 4 frames a second, at most ``maxWidth`` wide, until the backend answers ``{"continue": false}``, the session
expires, ``live_stop`` arrives, or pushing keeps failing. Nothing is stored; the backend keeps only the latest frame.
"""

from __future__ import annotations

import logging
import threading
from collections.abc import Callable
from typing import Any

from lp_edge.api import ApiError
from lp_edge.timeutil import Clock, SystemClock

log = logging.getLogger(__name__)

MAX_FPS = 4.0
MAX_SESSIONS = 4
MAX_FRAME_BYTES = 1_500_000

FrameSource = Callable[[], "tuple[float, Any] | None"]


class LiveSession:
    def __init__(
        self,
        session_id: str,
        *,
        frames: FrameSource,
        encode: Callable[[Any], bytes],
        push: Callable[[str, bytes], bool],
        fps: float,
        expires_at: float,
        clock: Clock | None = None,
        max_errors: int = 5,
    ) -> None:
        self.session_id = session_id
        self._frames = frames
        self._encode = encode
        self._push = push
        self._interval = 1.0 / max(0.2, min(MAX_FPS, fps or 2.0))
        self._expires_at = expires_at
        self._clock = clock or SystemClock()
        self._max_errors = max_errors
        self.stop_event = threading.Event()
        self.frames_sent = 0
        self.ended_because: str | None = None

    def run(self) -> None:
        errors = 0
        last_ts: float | None = None
        while not self.stop_event.is_set():
            if self._clock.time() >= self._expires_at:
                self.ended_because = "expired"
                return
            latest = self._frames()
            if latest is None or latest[0] == last_ts:
                self.stop_event.wait(self._interval / 2)
                continue
            last_ts = latest[0]
            try:
                go_on = self._push(self.session_id, self._encode(latest[1]))
                errors = 0
                self.frames_sent += 1
            except ApiError as exc:
                if exc.status in (400, 403, 404, 409, 410):
                    self.ended_because = f"refused: {exc.message}"
                    return
                errors += 1
                if errors >= self._max_errors:
                    self.ended_because = f"failing: {exc.message}"
                    return
                go_on = True
            except Exception as exc:  # noqa: BLE001
                errors += 1
                log.warning("live view %s: a frame could not be sent (%s)", self.session_id, type(exc).__name__)
                if errors >= self._max_errors:
                    self.ended_because = "failing"
                    return
                go_on = True
            if not go_on:
                self.ended_because = "stopped by the server"
                return
            self.stop_event.wait(self._interval)
        self.ended_because = self.ended_because or "stopped"


class LiveManager:
    def __init__(self) -> None:
        self._sessions: dict[str, tuple[LiveSession, threading.Thread]] = {}
        self._lock = threading.Lock()

    def start(self, session: LiveSession) -> None:
        with self._lock:
            self._sessions = {k: v for k, v in self._sessions.items() if v[1].is_alive()}
            if session.session_id in self._sessions:
                return
            if len(self._sessions) >= MAX_SESSIONS:
                raise RuntimeError(f"at most {MAX_SESSIONS} live views at a time")
            thread = threading.Thread(target=self._run, args=(session,), name=f"live-{session.session_id}", daemon=True)
            self._sessions[session.session_id] = (session, thread)
            thread.start()

    def _run(self, session: LiveSession) -> None:
        log.info("live view %s started", session.session_id)
        try:
            session.run()
        finally:
            log.info("live view %s ended (%s, %d frames)", session.session_id, session.ended_because, session.frames_sent)
            with self._lock:
                self._sessions.pop(session.session_id, None)

    def stop(self, session_id: str) -> bool:
        with self._lock:
            entry = self._sessions.get(session_id)
        if entry is None:
            return False
        entry[0].stop_event.set()
        return True

    def stop_all(self) -> None:
        with self._lock:
            entries = list(self._sessions.values())
        for session, thread in entries:
            session.stop_event.set()
        for _, thread in entries:
            thread.join(2.0)

    def active(self) -> list[str]:
        with self._lock:
            return [k for k, v in self._sessions.items() if v[1].is_alive()]
