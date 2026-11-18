"""Clock drift: how far this box's clock is from true time, and what happens when it is too far.

The drift is measured with SNTP when ``LP_EDGE_NTP_SERVER`` is set (UDP 123), else against the backend's
``serverTime`` in each heartbeat answer (half the round trip is the uncertainty), so an outbound-HTTPS-only site needs
nothing more. The host should keep its clock with NTP; the agent only measures. Beyond ``maxDriftMs`` the agent stops
making and sending observations, withdraws those made since the last good measurement, and reports the period as
``vision.gap`` (``clock_drift``) once the clock is right again.
"""

from __future__ import annotations

import socket
import struct
import threading
import time
from collections.abc import Callable
from dataclasses import dataclass

from lp_edge.timeutil import parse_iso

NTP_EPOCH_OFFSET = 2_208_988_800


def to_ntp(ts: float) -> bytes:
    seconds = int(ts) + NTP_EPOCH_OFFSET
    fraction = int((ts - int(ts)) * 2**32) & 0xFFFFFFFF
    return struct.pack(">II", seconds & 0xFFFFFFFF, fraction)


def from_ntp(data: bytes) -> float:
    seconds, fraction = struct.unpack(">II", data)
    return seconds - NTP_EPOCH_OFFSET + fraction / 2**32


def sntp_offset(
    server: str,
    *,
    port: int = 123,
    timeout: float = 2.0,
    now: Callable[[], float] = time.time,
    sock_factory: Callable[..., socket.socket] = socket.socket,
) -> tuple[float, float]:
    """(offset, round trip) in seconds from one SNTP exchange; a positive offset means this clock is behind."""
    request = bytearray(48)
    request[0] = 0x1B  # leap 0, version 3, mode 3 (client)
    sent = now()
    request[40:48] = to_ntp(sent)
    with sock_factory(socket.AF_INET, socket.SOCK_DGRAM) as sock:
        sock.settimeout(timeout)
        sock.sendto(bytes(request), (server, port))
        answer, _ = sock.recvfrom(512)
    received = now()
    if len(answer) < 48:
        raise ValueError("the SNTP answer is too short")
    mode, stratum = answer[0] & 0x07, answer[1]
    if mode not in (4, 5) or stratum == 0:
        raise ValueError("the SNTP server did not give the time")
    if answer[24:32] != bytes(request[40:48]):
        raise ValueError("the SNTP answer does not match the request")
    server_received = from_ntp(answer[32:40])
    server_sent = from_ntp(answer[40:48])
    offset = ((server_received - sent) + (server_sent - received)) / 2.0
    delay = (received - sent) - (server_sent - server_received)
    return offset, delay


def server_offset(server_time: str, sent_at: float, received_at: float) -> float:
    """Offset from the backend's ``serverTime``, taken as read halfway through the call."""
    return parse_iso(server_time) - (sent_at + received_at) / 2.0


@dataclass(frozen=True)
class DriftChange:
    drifting: bool
    at: float
    last_good_at: float | None
    last_good_seq: int


class DriftMonitor:
    def __init__(self, max_drift_ms: int = 2000, last_good_seq: int = 0) -> None:
        self._lock = threading.Lock()
        self.max_drift_ms = max_drift_ms
        self.offset: float | None = None
        self.source: str | None = None
        self.drifting = False
        self.drift_since: float | None = None
        self.last_good_at: float | None = None
        self.last_good_seq = last_good_seq

    @property
    def drift_ms(self) -> int:
        return 0 if self.offset is None else int(round(abs(self.offset) * 1000))

    def set_limit(self, max_drift_ms: int) -> None:
        with self._lock:
            self.max_drift_ms = max_drift_ms

    def record(
        self, offset: float, at: float, *, outbox_seq: int, source: str, uncertainty: float = 0.0
    ) -> DriftChange | None:
        """One measurement; returns the change when the clock goes off or comes right."""
        with self._lock:
            self.offset = offset
            self.source = source
            off = (abs(offset) - max(0.0, uncertainty)) * 1000 > self.max_drift_ms
            if not off:
                was = self.drifting
                self.drifting = False
                self.drift_since = None
                self.last_good_at = at
                self.last_good_seq = outbox_seq
                return DriftChange(False, at, at, outbox_seq) if was else None
            if self.drifting:
                return None
            self.drifting = True
            self.drift_since = self.last_good_at if self.last_good_at is not None else at
            return DriftChange(True, at, self.last_good_at, self.last_good_seq)
