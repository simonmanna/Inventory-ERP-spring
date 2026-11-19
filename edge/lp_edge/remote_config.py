"""The configuration the backend serves at ``GET /agents/config``: cameras, zones, meters, model, release, limits.

Zone ``params`` are merged over the defaults of the zone type (spec "Zones"). A zone the agent cannot read is skipped
with a warning rather than stopping the camera.
"""

from __future__ import annotations

import json
import logging
import math
from collections.abc import Mapping
from dataclasses import dataclass, field
from typing import Any

from lp_edge.timeutil import parse_iso

log = logging.getLogger(__name__)

ZONE_TYPES = (
    "cash_drawer",
    "checkout",
    "bar_counter",
    "kitchen_pass",
    "dining",
    "cafe_counter",
    "storage",
    "entrance",
    "scan_area",
    "queue",
)

COMMON_DEFAULTS: dict[str, Any] = {"minConfidence": 0.5}

ZONE_DEFAULTS: dict[str, dict[str, Any]] = {
    "cash_drawer": {
        "method": "reference_diff",
        "openThreshold": 0.18,
        "minOpenSeconds": 1,
        "minClosedSeconds": 2,
        "preRollSeconds": 20,
        "postRollSeconds": 30,
    },
    "checkout": {
        "presenceMethod": "detector",
        "minPresenceSeconds": 3,
        "clipMinDwellSeconds": 90,
        "occupancyEverySeconds": 60,
        "preRollSeconds": 10,
        "postRollSeconds": 10,
    },
    "entrance": {"classes": ["person"]},
    "kitchen_pass": {"classes": ["plate"], "preRollSeconds": 20, "postRollSeconds": 30},
    "cafe_counter": {"classes": ["cup"], "preRollSeconds": 20, "postRollSeconds": 30},
    "bar_counter": {"objects": ["bottle"], "preRollSeconds": 20, "postRollSeconds": 30},
    "dining": {"classes": ["plate", "person"], "occupancyEverySeconds": 300},
    "storage": {"classes": ["person", "bag"], "preRollSeconds": 20, "postRollSeconds": 30},
    # LP5: the scanner of a retail checkout. flowDirection: the way items travel from the unscanned side to the bagging
    # side on the picture; laneMargin: how far beside the scanner (fraction of the picture) an item may pass and still
    # be on this checkout's lane.
    "scan_area": {
        "classes": ["item"],
        "flowDirection": "left_to_right",
        "laneMargin": 0.15,
        "preRollSeconds": 10,
        "postRollSeconds": 10,
    },
    # LP6b: a waiting line. One dwell per person who waited at least minWaitSeconds; a count every period.
    "queue": {"classes": ["person"], "minWaitSeconds": 5, "occupancyEverySeconds": 60},
}

#: The ways items can travel across a scan_area on the picture (``flowDirection``).
FLOW_DIRECTIONS = ("left_to_right", "right_to_left", "top_to_bottom", "bottom_to_top")

MAX_CLIP_SECONDS = 600
MAX_CLIP_BYTES = 64 * 1024 * 1024
MAX_CHUNK_BYTES = 1024 * 1024


def _polygon(value: Any) -> tuple[tuple[float, float], ...]:
    if not isinstance(value, list) or len(value) < 2:
        raise ValueError("polygon needs at least 2 points")
    points = []
    for point in value:
        if not isinstance(point, (list, tuple)) or len(point) != 2:
            raise ValueError("a point is [x, y]")
        x, y = float(point[0]), float(point[1])
        if not (math.isfinite(x) and math.isfinite(y) and 0.0 <= x <= 1.0 and 0.0 <= y <= 1.0):
            raise ValueError("point coordinates are between 0 and 1")
        points.append((x, y))
    return tuple(points)


def _bounded_int(value: Any, default: int, low: int, high: int) -> int:
    try:
        number = int(value)
    except (TypeError, ValueError):
        return default
    return max(low, min(high, number))


def _instant_or_none(value: Any) -> float | None:
    """An ISO-8601 instant as epoch seconds; ``None`` when absent or unreadable (an older server sends none)."""
    if not isinstance(value, str) or not value:
        return None
    try:
        return parse_iso(value)
    except ValueError:
        return None


@dataclass(frozen=True)
class Zone:
    id: str
    name: str
    zone_type: str
    polygon: tuple[tuple[float, float], ...]
    in_side: str | None = None
    linked_type: str | None = None
    linked_id: str | None = None
    params: Mapping[str, Any] = field(default_factory=dict)
    #: When the zone was drawn on the server (epoch seconds), if sent. Not part of the fingerprint: a zone loaded after
    #: it was drawn reports the time in between as a ``config_pending`` gap.
    created_at: float | None = None

    @property
    def is_line(self) -> bool:
        return len(self.polygon) == 2

    @property
    def fingerprint(self) -> str:
        return json.dumps(
            [self.zone_type, self.polygon, self.in_side, self.linked_type, self.linked_id, dict(self.params)],
            sort_keys=True,
            default=str,
        )

    def num(self, name: str, default: float) -> float:
        value = self.params.get(name, default)
        try:
            number = float(value)
        except (TypeError, ValueError):
            return float(default)
        return number if math.isfinite(number) else float(default)

    def text(self, name: str, default: str) -> str:
        value = self.params.get(name, default)
        return value if isinstance(value, str) and value else default

    def names(self, name: str, default: list[str]) -> list[str]:
        value = self.params.get(name, default)
        if isinstance(value, list) and all(isinstance(v, str) for v in value):
            return list(value)
        return list(default)

    def flag(self, name: str, default: bool) -> bool:
        value = self.params.get(name, default)
        return value if isinstance(value, bool) else default

    @classmethod
    def parse(cls, data: Mapping[str, Any]) -> Zone:
        zone_type = str(data.get("zoneType") or "")
        if zone_type not in ZONE_TYPES:
            raise ValueError(f"unknown zoneType {zone_type!r}")
        raw_params = data.get("params") or {}
        if not isinstance(raw_params, Mapping):
            raise ValueError("params must be an object")
        params = {**COMMON_DEFAULTS, **ZONE_DEFAULTS.get(zone_type, {}), **raw_params}
        in_side = data.get("inSide")
        if in_side not in (None, "left", "right"):
            raise ValueError("inSide must be left or right")
        return cls(
            id=str(data["id"]),
            name=str(data.get("name") or ""),
            zone_type=zone_type,
            polygon=_polygon(data.get("polygon")),
            in_side=in_side,
            linked_type=data.get("linkedType"),
            linked_id=None if data.get("linkedId") is None else str(data.get("linkedId")),
            params=params,
            created_at=_instant_or_none(data.get("createdAt")),
        )


@dataclass(frozen=True)
class Camera:
    id: str
    name: str
    stream_alias: str
    enabled: bool = True
    analysed: bool = True
    zones: tuple[Zone, ...] = ()

    @property
    def observed(self) -> bool:
        """Analysed by this agent. (Every enabled camera is read and buffered; ``analysed`` adds vision.)"""
        return self.enabled and self.analysed

    @property
    def stream_key(self) -> tuple[str, bool, bool]:
        return (self.stream_alias, self.enabled, self.analysed)


@dataclass(frozen=True)
class ModelSpec:
    version: str
    detector: str
    url: str
    sha256: str
    signature: str
    input_size: tuple[int, int] = (640, 640)
    classes: tuple[str, ...] = ()

    @classmethod
    def parse(cls, data: Mapping[str, Any]) -> ModelSpec:
        size = data.get("inputSize") or 640
        if isinstance(size, (list, tuple)) and len(size) == 2:
            input_size = (int(size[0]), int(size[1]))
        else:
            input_size = (int(size), int(size))
        if min(input_size) < 32 or max(input_size) > 4096:
            raise ValueError("inputSize is out of range")
        classes = data.get("classes") or []
        if not isinstance(classes, list) or not all(isinstance(c, str) for c in classes):
            raise ValueError("classes must be a list of names")
        for name in ("version", "detector", "url", "sha256", "signature"):
            if not data.get(name):
                raise ValueError(f"model {name} is missing")
        return cls(
            version=str(data["version"]),
            detector=str(data["detector"]),
            url=str(data["url"]),
            sha256=str(data["sha256"]).lower(),
            signature=str(data["signature"]),
            input_size=input_size,
            classes=tuple(classes),
        )


@dataclass(frozen=True)
class ReleaseSpec:
    version: str
    image: str
    digest: str
    signature: str

    @classmethod
    def parse(cls, data: Mapping[str, Any]) -> ReleaseSpec:
        for name in ("version", "image", "digest", "signature"):
            if not data.get(name):
                raise ValueError(f"release {name} is missing")
        return cls(
            version=str(data["version"]),
            image=str(data["image"]),
            digest=str(data["digest"]),
            signature=str(data["signature"]),
        )

    def as_dict(self) -> dict[str, str]:
        return {"version": self.version, "image": self.image, "digest": self.digest, "signature": self.signature}


MEASUREMENT_KINDS = ("pour_spout", "flow_meter", "dispenser")


@dataclass(frozen=True)
class MeasurementDevice:
    """A pour meter this agent reads (LP3): ``external_id`` is the vendor's name of it, ``id`` the ledger's."""

    id: str
    external_id: str
    kind: str = "pour_spout"
    vendor: str | None = None
    product_id: str | None = None
    station: str | None = None
    standard_pour_ml: float | None = None
    enabled: bool = True

    @classmethod
    def parse(cls, data: Mapping[str, Any]) -> MeasurementDevice:
        if not data.get("id"):
            raise ValueError("id is missing")
        external_id = data.get("externalId")
        if not isinstance(external_id, str) or not external_id.strip():
            raise ValueError("externalId is missing")
        kind = str(data.get("kind") or "pour_spout")
        if kind not in MEASUREMENT_KINDS:
            log.warning("measurement device %s has an unknown kind %r", data.get("id"), kind)
        standard = data.get("standardPourMl")
        try:
            standard_ml = None if standard is None else float(standard)
        except (TypeError, ValueError):
            standard_ml = None
        return cls(
            id=str(data["id"]),
            external_id=external_id.strip(),
            kind=kind,
            vendor=None if data.get("vendor") is None else str(data.get("vendor")),
            product_id=None if data.get("productId") is None else str(data.get("productId")),
            station=None if data.get("station") is None else str(data.get("station")),
            standard_pour_ml=standard_ml,
            enabled=bool(data.get("enabled", True)),
        )


@dataclass(frozen=True)
class AgentConfig:
    agent_id: str
    branch_id: str
    config_version: int
    heartbeat_seconds: int = 60
    command_wait_seconds: int = 25
    max_drift_ms: int = 2000
    buffer_days: int = 7
    max_clip_seconds: int = MAX_CLIP_SECONDS
    max_clip_bytes: int = MAX_CLIP_BYTES
    chunk_bytes: int = MAX_CHUNK_BYTES
    model: ModelSpec | None = None
    release: ReleaseSpec | None = None
    cameras: tuple[Camera, ...] = ()
    measurement_devices: tuple[MeasurementDevice, ...] = ()
    raw: Mapping[str, Any] = field(default_factory=dict, compare=False, repr=False)

    def camera(self, camera_id: str) -> Camera | None:
        return next((c for c in self.cameras if c.id == camera_id), None)

    def zone(self, zone_id: str) -> tuple[Camera, Zone] | None:
        for camera in self.cameras:
            for zone in camera.zones:
                if zone.id == zone_id:
                    return camera, zone
        return None

    @classmethod
    def parse(cls, data: Mapping[str, Any]) -> AgentConfig:
        if not isinstance(data, Mapping):
            raise ValueError("the configuration is not a JSON object")
        cameras = []
        for raw_camera in data.get("cameras") or []:
            if not isinstance(raw_camera, Mapping) or not raw_camera.get("id"):
                log.warning("a camera without an id was ignored")
                continue
            zones = []
            for raw_zone in raw_camera.get("zones") or []:
                if isinstance(raw_zone, Mapping) and raw_zone.get("enabled") is False:
                    continue
                try:
                    zones.append(Zone.parse(raw_zone))
                except (ValueError, TypeError, KeyError) as exc:
                    zone_id = raw_zone.get("id") if isinstance(raw_zone, Mapping) else None
                    log.warning("zone %s of camera %s ignored: %s", zone_id, raw_camera.get("id"), exc)
            cameras.append(
                Camera(
                    id=str(raw_camera["id"]),
                    name=str(raw_camera.get("name") or ""),
                    stream_alias=str(raw_camera.get("streamAlias") or ""),
                    enabled=bool(raw_camera.get("enabled", True)),
                    analysed=bool(raw_camera.get("analysed", True)),
                    zones=tuple(zones),
                )
            )
        devices = []
        raw_devices = data.get("measurementDevices")
        for raw_device in raw_devices if isinstance(raw_devices, list) else []:
            try:
                if not isinstance(raw_device, Mapping):
                    raise ValueError("not an object")
                devices.append(MeasurementDevice.parse(raw_device))
            except (ValueError, TypeError) as exc:
                device_id = raw_device.get("id") if isinstance(raw_device, Mapping) else None
                log.warning("measurement device %s ignored: %s", device_id, exc)
        clock = data.get("clock") if isinstance(data.get("clock"), Mapping) else {}
        evidence = data.get("evidence") if isinstance(data.get("evidence"), Mapping) else {}
        model = None
        if isinstance(data.get("model"), Mapping):
            try:
                model = ModelSpec.parse(data["model"])
            except (ValueError, TypeError) as exc:
                log.error("the detector model in the configuration is ignored: %s", exc)
        release = None
        if isinstance(data.get("release"), Mapping):
            try:
                release = ReleaseSpec.parse(data["release"])
            except (ValueError, TypeError) as exc:
                log.error("the release in the configuration is ignored: %s", exc)
        return cls(
            agent_id=str(data.get("agentId") or ""),
            branch_id=str(data.get("branchId") or ""),
            config_version=_bounded_int(data.get("configVersion"), 0, 0, 2**62),
            heartbeat_seconds=_bounded_int(data.get("heartbeatSeconds"), 60, 10, 3600),
            command_wait_seconds=_bounded_int(data.get("commandWaitSeconds"), 25, 0, 60),
            max_drift_ms=_bounded_int(clock.get("maxDriftMs"), 2000, 50, 3_600_000),
            buffer_days=_bounded_int(evidence.get("bufferDays"), 7, 1, 365),
            max_clip_seconds=_bounded_int(evidence.get("maxClipSeconds"), MAX_CLIP_SECONDS, 10, MAX_CLIP_SECONDS),
            max_clip_bytes=_bounded_int(evidence.get("maxClipBytes"), MAX_CLIP_BYTES, 1024 * 1024, MAX_CLIP_BYTES),
            chunk_bytes=_bounded_int(evidence.get("chunkBytes"), MAX_CHUNK_BYTES, 64 * 1024, MAX_CHUNK_BYTES),
            model=model,
            release=release,
            cameras=tuple(cameras),
            measurement_devices=tuple(devices),
            raw=dict(data),
        )
