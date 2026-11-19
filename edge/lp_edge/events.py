"""Vision observations, pour-meter readings and the ledger envelope (v1) they travel in.

Every vision observation is about one zone: ``deviceId`` is the zone id, ``evidenceLevel`` ``zone``,
``evidenceSource`` ``camera``, ``payload.cameraId`` the camera. A meter reading (LP3) is about one measurement device:
``deviceId`` is the device id, ``evidenceLevel`` ``item``, ``evidenceSource`` ``sensor``. Only the envelope fields the
ingest accepts are sent (it refuses unknown fields). Times are UTC instants with milliseconds.
"""

from __future__ import annotations

import json
import math
import re
import uuid
from dataclasses import dataclass, field
from typing import Any

from lp_edge import EVENT_SOURCE
from lp_edge.timeutil import iso, parse_iso

MAX_PAYLOAD_BYTES = 65_536

LINE_CROSSED = "vision.line_crossed"
DWELL = "vision.dwell"
STATE_CHANGED = "vision.state_changed"
HANDLING = "vision.handling"
OCCUPANCY = "vision.occupancy"
GAP = "vision.gap"
#: LP7, research only: a recognizer saw an activity in a zone. No backend rule reads it.
ACTIVITY = "vision.activity"

REQUIRED: dict[str, tuple[str, ...]] = {
    LINE_CROSSED: ("class", "direction", "count"),
    DWELL: ("class", "startedAt", "endedAt"),
    STATE_CHANGED: ("object", "to"),
    HANDLING: ("object", "action"),
    OCCUPANCY: ("count",),
    GAP: ("from", "to", "reason"),
    ACTIVITY: ("activity", "startedAt", "endedAt", "recognizer"),
}

#: An activity name: 1–64 characters, lowercase letters, digits or ``_``, starting with a letter.
ACTIVITY_NAME = re.compile(r"^[a-z][a-z0-9_]{0,63}$")
MAX_TRACK_IDS = 50

GAP_REASONS = ("stream_lost", "agent_down", "clock_drift", "disabled", "config_pending")

POUR_MEASURED = "pour.measured"
MEASUREMENT_GAP = "measurement.gap"
MEASUREMENT_GAP_REASONS = ("device_offline", "adapter_down", "clock_drift", "disabled")
MAX_POUR_ML = 5000
MAX_ID_LENGTH = 200

#: The envelope fields this agent sends; all are accepted by ``POST /loss-prevention/events``.
ENVELOPE_FIELDS = frozenset(
    {
        "eventId",
        "source",
        "sourceEventId",
        "type",
        "schemaVersion",
        "occurredAt",
        "branchId",
        "deviceId",
        "evidenceLevel",
        "evidenceSource",
        "observationStatus",
        "confidence",
        "payload",
    }
)


@dataclass(frozen=True)
class ClipPlan:
    """A clip to keep for an observation: ``started_at``–``ended_at`` of the camera's buffer."""

    clip_id: str
    started_at: float
    ended_at: float


@dataclass
class Observation:
    """What a zone profile saw. Becomes one ledger event."""

    type: str
    zone_id: str
    camera_id: str
    occurred_at: float
    payload: dict[str, Any] = field(default_factory=dict)
    confidence: float | None = None
    status: str = "observed"
    clip: ClipPlan | None = None


def gap(zone_id: str, camera_id: str, reason: str, start: float, end: float, **extra: Any) -> Observation:
    """A period without a usable picture of a zone (``observationStatus`` ``unavailable``)."""
    payload = {"from": iso(start), "to": iso(end), "reason": reason, **extra}
    return Observation(GAP, zone_id, camera_id, start, payload, confidence=1.0, status="unavailable")


def validate(event_type: str, payload: dict[str, Any]) -> None:
    """Raise :class:`ValueError` when ``payload`` lacks what the ledger requires for ``event_type``."""
    required = REQUIRED.get(event_type)
    if required is None:
        raise ValueError(f"unknown vision event type {event_type!r}")
    for name in required:
        if payload.get(name) is None or payload.get(name) == "":
            raise ValueError(f"{event_type} needs payload.{name}")
    if not payload.get("cameraId"):
        raise ValueError(f"{event_type} needs payload.cameraId")
    if event_type == LINE_CROSSED:
        if payload["direction"] not in ("in", "out"):
            raise ValueError("direction must be in or out")
        if not isinstance(payload["count"], int) or isinstance(payload["count"], bool) or payload["count"] < 1:
            raise ValueError("count must be 1 or more")
    elif event_type == OCCUPANCY:
        if not isinstance(payload["count"], int) or isinstance(payload["count"], bool) or payload["count"] < 0:
            raise ValueError("count must be 0 or more")
    elif event_type == DWELL:
        if parse_iso(payload["endedAt"]) < parse_iso(payload["startedAt"]):
            raise ValueError("endedAt is before startedAt")
    elif event_type == GAP:
        if payload["reason"] not in GAP_REASONS:
            raise ValueError("reason must be one of " + ", ".join(GAP_REASONS))
        if parse_iso(payload["to"]) < parse_iso(payload["from"]):
            raise ValueError("to is before from")
    elif event_type == ACTIVITY:
        _validate_activity(payload)
    size = len(json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8"))
    if size > MAX_PAYLOAD_BYTES:
        raise ValueError(f"payload is larger than {MAX_PAYLOAD_BYTES} bytes")


def _validate_activity(payload: dict[str, Any]) -> None:
    activity = payload["activity"]
    if not isinstance(activity, str) or not ACTIVITY_NAME.fullmatch(activity):
        raise ValueError("activity must be 1 to 64 characters: lowercase letters, digits or '_', starting with a"
                         " letter")
    if not _text(payload, "recognizer"):
        raise ValueError(f"recognizer must hold 1 to {MAX_ID_LENGTH} characters")
    if parse_iso(payload["endedAt"]) < parse_iso(payload["startedAt"]):
        raise ValueError("endedAt is before startedAt")
    score = payload.get("score")
    if score is not None and (isinstance(score, bool) or not isinstance(score, (int, float))
                              or not math.isfinite(score) or not 0 <= score <= 1):
        raise ValueError("score must be between 0 and 1")
    track_ids = payload.get("trackIds")
    if track_ids is not None and (not isinstance(track_ids, list) or len(track_ids) > MAX_TRACK_IDS
                                  or any(isinstance(t, bool) or not isinstance(t, (int, str)) for t in track_ids)):
        raise ValueError(f"trackIds must be a list of at most {MAX_TRACK_IDS} track numbers")


def envelope(observation: Observation, *, agent_id: str, branch_id: str, event_id: str | None = None) -> dict:
    """The ledger event for ``observation``. ``sourceEventId`` is the agent id and the event id: stable on resend."""
    payload = dict(observation.payload)
    payload["cameraId"] = observation.camera_id
    if observation.clip is not None:
        payload["clipId"] = observation.clip.clip_id
    validate(observation.type, payload)
    if observation.status not in ("observed", "unavailable"):
        raise ValueError("observationStatus must be observed or unavailable")
    event_id = event_id or str(uuid.uuid4())
    event: dict[str, Any] = {
        "eventId": event_id,
        "source": EVENT_SOURCE,
        "sourceEventId": f"{agent_id}:{event_id}",
        "type": observation.type,
        "schemaVersion": 1,
        "occurredAt": iso(observation.occurred_at),
        "branchId": branch_id,
        "deviceId": observation.zone_id,
        "evidenceLevel": "zone",
        "evidenceSource": "camera",
        "observationStatus": observation.status,
        "payload": payload,
    }
    if observation.confidence is not None:
        event["confidence"] = round(min(1.0, max(0.0, float(observation.confidence))), 3)
    return event


def _text(payload: dict[str, Any], name: str) -> bool:
    value = payload.get(name)
    return isinstance(value, str) and 1 <= len(value) <= MAX_ID_LENGTH


def validate_measurement(event_type: str, payload: dict[str, Any]) -> None:
    """Raise :class:`ValueError` when ``payload`` is not what the ledger takes for a meter event (LP3)."""
    if event_type == MEASUREMENT_GAP:
        for name in ("from", "to", "reason"):
            if not payload.get(name):
                raise ValueError(f"{event_type} needs payload.{name}")
        if payload["reason"] not in MEASUREMENT_GAP_REASONS:
            raise ValueError("reason must be one of " + ", ".join(MEASUREMENT_GAP_REASONS))
        if parse_iso(payload["to"]) < parse_iso(payload["from"]):
            raise ValueError("to is before from")
        return
    if event_type != POUR_MEASURED:
        raise ValueError(f"unknown measurement event type {event_type!r}")
    volume = payload.get("volumeMl")
    if isinstance(volume, bool) or not isinstance(volume, (int, float)) or not 0 < volume <= MAX_POUR_ML:
        raise ValueError(f"volumeMl must be a number above 0 and at most {MAX_POUR_ML}")
    for name in ("productId", "vendorEventId"):
        if payload.get(name) is not None and not _text(payload, name):
            raise ValueError(f"{name} must hold 1 to {MAX_ID_LENGTH} characters")
    started = parse_iso(payload["startedAt"]) if payload.get("startedAt") is not None else None
    ended = parse_iso(payload["endedAt"]) if payload.get("endedAt") is not None else None
    if started is not None and ended is not None and ended < started:
        raise ValueError("endedAt is before startedAt")


def measurement_envelope(
    event_type: str,
    payload: dict[str, Any],
    *,
    device_id: str,
    occurred_at: float,
    source_event_id: str,
    branch_id: str,
) -> dict:
    """The ledger event of a meter reading or gap. ``eventId`` derives from ``sourceEventId``: the same reading read
    twice is the same event."""
    validate_measurement(event_type, payload)
    if not device_id:
        raise ValueError("deviceId is required")
    if not source_event_id or len(source_event_id) > MAX_ID_LENGTH:
        raise ValueError(f"sourceEventId must hold 1 to {MAX_ID_LENGTH} characters")
    return {
        "eventId": str(uuid.uuid5(uuid.NAMESPACE_URL, f"lp-edge:{source_event_id}")),
        "source": EVENT_SOURCE,
        "sourceEventId": source_event_id,
        "type": event_type,
        "schemaVersion": 1,
        "occurredAt": iso(occurred_at),
        "branchId": branch_id,
        "deviceId": device_id,
        "evidenceLevel": "item",
        "evidenceSource": "sensor",
        "observationStatus": "unavailable" if event_type == MEASUREMENT_GAP else "observed",
        "payload": payload,
    }
