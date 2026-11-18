"""Test doubles shared by the tests: a settable clock, a requests-like session, the agent's API."""

from __future__ import annotations

import base64
import json
import time
from collections.abc import Callable
from pathlib import Path
from typing import Any

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

from lp_edge.api import ApiError
from lp_edge.remote_config import Zone
from lp_edge.signing import release_message
from lp_edge.tracker import Track
from lp_edge.video import EncodedClip, NoFrames

T0 = 1_800_000_000.0  # 2027-01-15T08:00:00Z, a whole minute (and a multiple of 300 s)


def wait_until(condition: Callable[[], Any], timeout: float = 10.0) -> None:
    deadline = time.monotonic() + timeout
    while not condition():
        if time.monotonic() > deadline:
            raise AssertionError("condition not met in time")
        time.sleep(0.02)


def keypair() -> tuple[Ed25519PrivateKey, str, str]:
    """A vendor signing key: the private key, its public key as PEM, and as base64 of the raw 32 bytes."""
    private = Ed25519PrivateKey.generate()
    public = private.public_key()
    pem = public.public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo).decode()
    raw = public.public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
    return private, pem, base64.b64encode(raw).decode()


def signed_release(private: Ed25519PrivateKey, version: str = "0.2.0",
                   image: str = "registry.example.com/lp-edge", digest: str = "sha256:" + "a" * 64) -> dict[str, str]:
    signature = base64.b64encode(private.sign(release_message(version, image, digest))).decode()
    return {"version": version, "image": image, "digest": digest, "signature": signature}


REPEAT = 25_000  # the fake encoder repeats the frames so a clip spans several 1 MiB chunks


def fake_encoder(frames: Callable[[], Any], out_dir: Path, *, fps: float, max_bytes: int) -> EncodedClip:
    """Stands for OpenCV: the 'video' is the buffered JPEGs joined and repeated."""
    items = list(frames())
    if not items:
        raise NoFrames("no picture in the buffer for that period")
    out_dir.mkdir(parents=True, exist_ok=True)
    path = out_dir / f"fake-{items[0][0]}.mp4"
    path.write_bytes(b"".join(jpeg for _, jpeg in items) * REPEAT)
    return EncodedClip(path, "video/mp4", items[0][0], items[-1][0], len(items))


class FakeClock:
    def __init__(self, start: float = T0) -> None:
        self.now = start
        self.mono = 1000.0

    def time(self) -> float:
        return self.now

    def monotonic(self) -> float:
        return self.mono

    def advance(self, seconds: float) -> None:
        self.now += seconds
        self.mono += seconds


class FakeResponse:
    def __init__(
        self,
        status: int = 200,
        body: Any = None,
        headers: dict[str, str] | None = None,
        chunks: list[bytes] | None = None,
        reason: str = "",
    ) -> None:
        self.status_code = status
        self._body = body
        self.headers = headers or {}
        self._chunks = chunks or []
        self.reason = reason
        self.text = "" if body is None else json.dumps(body)

    def json(self) -> Any:
        if self._body is None:
            raise ValueError("no JSON body")
        return self._body

    def iter_content(self, size: int) -> list[bytes]:
        return list(self._chunks)

    def __enter__(self) -> FakeResponse:
        return self

    def __exit__(self, *exc: Any) -> None:
        return None


class FakeSession:
    """Records every request; ``responder(method, url, kwargs)`` gives the answer."""

    def __init__(self, responder: Callable[[str, str, dict[str, Any]], FakeResponse]) -> None:
        self.responder = responder
        self.calls: list[tuple[str, str, dict[str, Any]]] = []

    def request(self, method: str, url: str, **kwargs: Any) -> FakeResponse:
        self.calls.append((method, url, kwargs))
        return self.responder(method, url, kwargs)


def track(track_id: int, box: tuple[float, float, float, float], cls: str = "person", score: float = 0.9,
          ts: float = T0) -> Track:
    return Track(id=track_id, cls=cls, box=box, score=score, first_seen=ts, last_seen=ts, hits=3, confirmed=True,
                 score_total=score * 3)


def box_at(x: float, y: float, w: float = 0.1, h: float = 0.2) -> tuple[float, float, float, float]:
    """A box centred on (x, y)."""
    return (x - w / 2, y - h / 2, x + w / 2, y + h / 2)


def zone(zone_type: str, polygon: list[list[float]], zone_id: str = "z1", **params: Any) -> Zone:
    data: dict[str, Any] = {"id": zone_id, "name": zone_id, "zoneType": zone_type, "polygon": polygon,
                            "params": params}
    if "inSide" in params:
        data["inSide"] = params.pop("inSide")
    return Zone.parse(data)


SQUARE = [[0.25, 0.25], [0.75, 0.25], [0.75, 0.75], [0.25, 0.75]]


class FakeApi:
    """The agent's API calls, answered from memory."""

    def __init__(self, config: dict[str, Any] | None = None) -> None:
        self.config = config or {"agentId": "a1", "branchId": "b1", "configVersion": 1, "cameras": []}
        self.heartbeats: list[dict[str, Any]] = []
        self.heartbeat_answer: dict[str, Any] = {}
        self.results: list[tuple[str, str, str | None, str | None]] = []
        self.snapshots: list[tuple[str, bytes]] = []
        self.sent: list[list[dict[str, Any]]] = []
        self.registered: list[list[dict[str, Any]]] = []
        self.live_frames: list[str] = []
        self.commands: list[dict[str, Any]] = []
        self.upload: dict[str, Any] = {}
        self.chunks: list[tuple[int, int, str, bytes]] = []
        self.content_types: list[str | None] = []

    def get_config(self) -> dict[str, Any]:
        return self.config

    def heartbeat(self, body: dict[str, Any]) -> dict[str, Any]:
        self.heartbeats.append(body)
        return self.heartbeat_answer

    def poll_commands(self, wait_seconds: int) -> list[dict[str, Any]]:
        commands, self.commands = self.commands, []
        return commands

    def command_result(self, command_id: str, status: str, message: str | None = None, code: str | None = None) -> None:
        self.results.append((command_id, status, message, code))

    def post_snapshot(self, command_id: str, jpeg: bytes) -> None:
        self.snapshots.append((command_id, jpeg))

    def post_live_frame(self, session_id: str, jpeg: bytes) -> bool:
        self.live_frames.append(session_id)
        return True

    def send_events(self, events: list[dict[str, Any]]) -> dict[str, Any]:
        self.sent.append(events)
        return {"results": [{"eventId": e["eventId"], "status": "accepted"} for e in events]}

    def register_clips(self, clips: list[dict[str, Any]]) -> dict[str, Any]:
        self.registered.append(clips)
        return {"results": [{"clipId": c["clipId"], "status": "registered", "evidenceId": "ev-" + c["clipId"]}
                            for c in clips]}

    def upload_status(self, evidence_id: str) -> dict[str, Any]:
        return dict(self.upload.get(evidence_id) or {"receivedBytes": 0, "complete": False})

    def upload_chunk(
        self, evidence_id: str, offset: int, total: int, sha256: str, chunk: bytes, content_type: str | None = None
    ) -> dict[str, Any]:
        self.chunks.append((offset, total, sha256, chunk))
        self.content_types.append(content_type)
        received = offset + len(chunk)
        status = {"receivedBytes": received, "totalBytes": total, "complete": received >= total}
        self.upload[evidence_id] = status
        return status

    def download(self, url: str, dest: Any) -> str:
        raise ApiError(404, "no models here")
