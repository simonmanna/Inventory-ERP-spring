"""What an evaluation ran on, recorded so that two reports can be compared fairly.

Per candidate: the weight or predictions file's SHA-256 and size, the expected SHA-256 and source URL from the
candidates file, the tracker's settings. Per run: the agent version and a hash of its zone-profile code, every clip's
video and label hashes and zone definitions, the settings and thresholds, and a fingerprint over all of them. Two
reports with the same fingerprint measured the same model on the same footage with the same pipeline and settings.
"""

from __future__ import annotations

import hashlib
import json
import subprocess
from pathlib import Path
from typing import Any

import lp_edge
from lp_edge.tracker import ByteTracker

_CHUNK = 1 << 20
_PACKAGE = Path(lp_edge.__file__).resolve().parent


def sha256_file(path: Path | None) -> str | None:
    """Hex SHA-256 of a file, or None when there is no such file."""
    if path is None or not path.is_file():
        return None
    digest = hashlib.sha256()
    with path.open("rb") as f:
        while chunk := f.read(_CHUNK):
            digest.update(chunk)
    return digest.hexdigest()


def _sha256_json(value: Any) -> str:
    return hashlib.sha256(json.dumps(value, sort_keys=True, default=str).encode("utf-8")).hexdigest()


def tracker_settings() -> dict[str, Any]:
    """The settings of the tracker the harness runs (the agent's defaults)."""
    tracker = ByteTracker()
    return {"tracker": "ByteTracker", **{k: v for k, v in vars(tracker).items() if not k.startswith("_")}}


def agent() -> dict[str, Any]:
    """The agent version, the source commit when known, and a hash of the zone-profile, activity-recognizer and
    tracker code."""
    sources = (sorted((_PACKAGE / "profiles").glob("*.py")) + sorted((_PACKAGE / "activities").glob("*.py"))
               + [_PACKAGE / "tracker.py", _PACKAGE / "geometry.py"])
    digest = hashlib.sha256()
    for path in sources:
        digest.update(path.name.encode("utf-8"))
        digest.update(path.read_bytes())
    commit = None
    try:
        out = subprocess.run(["git", "-C", str(_PACKAGE), "rev-parse", "HEAD"], capture_output=True, text=True,
                             timeout=5, check=False)
        commit = out.stdout.strip() or None
        dirty = subprocess.run(["git", "-C", str(_PACKAGE), "status", "--porcelain", "--", "."], capture_output=True,
                               text=True, timeout=5, check=False).stdout.strip()
        if commit and dirty:
            commit += "+uncommitted"
    except (OSError, subprocess.SubprocessError):
        pass
    return {"version": lp_edge.__version__, "commit": commit, "profilesSha256": digest.hexdigest()}


def candidate(c: Any) -> dict[str, Any]:
    """The file a candidate was measured from, checked against the hash the candidates file expects."""
    path = c.model if c.model is not None else c.predictions
    actual = sha256_file(path)
    return {
        "file": None if path is None else path.name,
        "kind": "model" if c.model is not None else "predictions",
        "bytes": path.stat().st_size if path is not None and path.is_file() else None,
        "sha256": actual,
        "expectedSha256": c.sha256 or None,
        "sha256Matches": None if not c.sha256 or actual is None else actual == c.sha256.lower(),
        "sourceUrl": c.source_url or None,
        "tracker": tracker_settings(),
    }


def footage(dataset: Any, hash_videos: bool = True) -> list[dict[str, Any]]:
    """Every clip: id, video and label hashes, conditions and zones (as sent to the agent)."""
    out = []
    for clip in dataset.clips:
        out.append({
            "id": clip.id,
            "video": clip.video.name,
            "videoSha256": sha256_file(clip.video) if hash_videos else None,
            "labelsSha256": sha256_file(clip.labels),
            "profile": clip.profile,
            "shift": clip.shift,
            "conditions": {"period": clip.period, "lighting": clip.lighting, "angle": clip.angle},
            "zonesSha256": _sha256_json([{"id": z.id, "zoneType": z.zone_type, "polygon": z.polygon,
                                          "inSide": z.in_side, "linked": [z.linked_type, z.linked_id],
                                          "params": dict(z.params)} for z in clip.zones]),
        })
    return out


def fingerprint(candidate_provenance: dict[str, Any], agent_info: dict[str, Any], clips: list[dict[str, Any]],
                settings: dict[str, Any], thresholds: dict[str, Any]) -> str:
    """One hash over everything that decides a candidate's scores."""
    return _sha256_json({"candidate": {k: candidate_provenance[k] for k in ("sha256", "tracker")},
                         "profiles": agent_info["profilesSha256"],
                         "clips": [{k: c[k] for k in ("id", "videoSha256", "labelsSha256", "zonesSha256")}
                                   for c in clips],
                         "settings": settings, "thresholds": thresholds["profiles"]})
