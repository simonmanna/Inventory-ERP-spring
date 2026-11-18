"""Settings of this box, from environment variables (``LP_EDGE_*``).

What the backend decides (cameras, zones, heartbeat interval, buffer days, model, release) comes from
``GET /agents/config`` instead; see :mod:`lp_edge.remote_config`. Stream addresses are in the local streams file,
never in the environment of the backend.
"""

from __future__ import annotations

import os
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path

DEFAULT_DATA_DIR = "/var/lib/lp-edge"
DEFAULT_TMP_DIR = "/run/lp-edge"


def _str(env: Mapping[str, str], name: str) -> str | None:
    value = env.get(name)
    return value.strip() if value and value.strip() else None


def _float(env: Mapping[str, str], name: str, default: float, low: float, high: float) -> float:
    raw = _str(env, name)
    if raw is None:
        return default
    try:
        value = float(raw)
    except ValueError as exc:
        raise ValueError(f"{name} must be a number") from exc
    if not low <= value <= high:
        raise ValueError(f"{name} must be between {low} and {high}")
    return value


def _int(env: Mapping[str, str], name: str, default: int, low: int, high: int) -> int:
    return int(_float(env, name, default, low, high))


def _path(env: Mapping[str, str], name: str) -> Path | None:
    raw = _str(env, name)
    return Path(raw) if raw is not None else None


def _bool(env: Mapping[str, str], name: str) -> bool:
    return (_str(env, name) or "").lower() in ("1", "true", "yes", "on")


@dataclass(frozen=True)
class Settings:
    data_dir: Path
    streams_file: Path
    tmp_dir: Path
    trust_keys: str | None = None
    ca_bundle: str | None = None
    allow_http: bool = False
    health_port: int = 8765
    ntp_server: str | None = None
    buffer_key: str | None = None
    buffer_fps: float = 2.0
    buffer_max_width: int = 640
    buffer_jpeg_quality: int = 60
    analysis_fps: float = 5.0
    analysis_max_width: int = 960
    reserve_percent: float = 12.0
    reserve_bytes: int = 6 * 1024**3
    stall_seconds: float = 5.0
    log_level: str = "INFO"
    log_format: str = "text"
    ort_providers: tuple[str, ...] = ()
    measurement_adapters: Path | None = None

    @property
    def credentials_file(self) -> Path:
        return self.data_dir / "credentials.json"

    @property
    def db_file(self) -> Path:
        return self.data_dir / "agent.db"

    @property
    def buffer_dir(self) -> Path:
        return self.data_dir / "buffer"

    @property
    def clips_dir(self) -> Path:
        return self.data_dir / "clips"

    @property
    def models_dir(self) -> Path:
        return self.data_dir / "models"

    @property
    def references_dir(self) -> Path:
        return self.data_dir / "references"

    @property
    def key_file(self) -> Path:
        return self.data_dir / "keys" / "buffer.key"

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None) -> Settings:
        env = os.environ if env is None else env
        data_dir = Path(_str(env, "LP_EDGE_DATA_DIR") or DEFAULT_DATA_DIR)
        tmp = _str(env, "LP_EDGE_TMP_DIR")
        if tmp is None:
            tmp = DEFAULT_TMP_DIR if os.access(DEFAULT_TMP_DIR, os.W_OK) else str(data_dir / "tmp")
        providers = tuple(p.strip() for p in (_str(env, "LP_EDGE_ORT_PROVIDERS") or "").split(",") if p.strip())
        log_format = (_str(env, "LP_EDGE_LOG_FORMAT") or "text").lower()
        if log_format not in ("text", "json"):
            raise ValueError("LP_EDGE_LOG_FORMAT must be text or json")
        return cls(
            data_dir=data_dir,
            streams_file=Path(_str(env, "LP_EDGE_STREAMS_FILE") or str(data_dir / "streams.yaml")),
            tmp_dir=Path(tmp),
            trust_keys=_str(env, "LP_EDGE_TRUST_KEY"),
            ca_bundle=_str(env, "LP_EDGE_CA_BUNDLE"),
            allow_http=_bool(env, "LP_EDGE_ALLOW_HTTP"),
            health_port=_int(env, "LP_EDGE_HEALTH_PORT", 8765, 1, 65535),
            ntp_server=_str(env, "LP_EDGE_NTP_SERVER"),
            buffer_key=_str(env, "LP_EDGE_BUFFER_KEY"),
            buffer_fps=_float(env, "LP_EDGE_BUFFER_FPS", 2.0, 0.2, 15.0),
            buffer_max_width=_int(env, "LP_EDGE_BUFFER_MAX_WIDTH", 640, 160, 3840),
            buffer_jpeg_quality=_int(env, "LP_EDGE_BUFFER_JPEG_QUALITY", 60, 20, 95),
            analysis_fps=_float(env, "LP_EDGE_ANALYSIS_FPS", 5.0, 1.0, 15.0),
            analysis_max_width=_int(env, "LP_EDGE_ANALYSIS_MAX_WIDTH", 960, 320, 3840),
            reserve_percent=_float(env, "LP_EDGE_RESERVE_PERCENT", 12.0, 1.0, 50.0),
            reserve_bytes=_int(env, "LP_EDGE_RESERVE_BYTES", 6 * 1024**3, 0, 1 << 50),
            stall_seconds=_float(env, "LP_EDGE_STALL_SECONDS", 5.0, 1.0, 60.0),
            log_level=(_str(env, "LP_EDGE_LOG_LEVEL") or "INFO").upper(),
            log_format=log_format,
            ort_providers=providers,
            measurement_adapters=_path(env, "LP_MEASUREMENT_ADAPTERS"),
        )

    @property
    def tls_verify(self) -> bool | str:
        """What ``requests`` takes as ``verify``: a private CA bundle, or the system trust store."""
        return self.ca_bundle or True
