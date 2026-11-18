"""The licence register: what was checked about each candidate's code, pretrained weights, training data and runtime
dependencies, by whom and when.

A candidate is eligible to ship only when every register entry it names is ``cleared``. One ``excluded`` entry
excludes it; anything else (``review``, or an entry missing from the register) leaves it "needs review". The harness
measures a candidate whatever its licence status, and the report states the status beside the scores.

Weights are cleared per file: a weights entry lists its ``checkpoints`` (file, version, download URL, SHA-256,
training data) and its legal ``assessment``, one answer per question in :data:`ASSESSMENT`. A cleared weights entry
needs both, and clears a candidate only when the candidate's weight file is one of those checkpoints.
"""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Any

import yaml

STATUSES = ("cleared", "review", "excluded")
COMPONENTS = ("code", "weights", "data", "runtime")
#: The questions a legal view of pretrained weights answers separately.
ASSESSMENT = ("codeLicence", "weightDistribution", "trainingImages", "annotations", "closedRedistribution")
_CHECKPOINT_FIELDS = ("file", "version", "downloadUrl", "sha256", "trainedOn")


class RegisterError(ValueError):
    pass


@dataclass(frozen=True)
class LicenceEntry:
    key: str
    name: str
    component: str
    licence: str
    source: str
    status: str
    checked_by: str | None
    checked_on: str | None
    notes: str
    checkpoints: tuple[dict[str, Any], ...] = ()
    assessment: dict[str, str] | None = None

    def covers(self, sha256: str) -> bool:
        return any(str(c.get("sha256", "")).lower() == sha256.lower() for c in self.checkpoints)


def load_register(path: str | Path) -> dict[str, LicenceEntry]:
    try:
        data = yaml.safe_load(Path(path).read_text(encoding="utf-8")) or {}
    except (OSError, yaml.YAMLError) as err:
        raise RegisterError(f"cannot read {path}: {err}") from err
    entries: dict[str, LicenceEntry] = {}
    for i, e in enumerate(data.get("entries") or []):
        key = str(e.get("key") or "")
        if not key or key in entries:
            raise RegisterError(f"entries[{i}]: key is required and must be unique")
        if e.get("status") not in STATUSES:
            raise RegisterError(f"entry {key}: status must be one of {', '.join(STATUSES)}")
        if e.get("component") not in COMPONENTS:
            raise RegisterError(f"entry {key}: component must be one of {', '.join(COMPONENTS)}")
        if e["status"] == "cleared" and not (e.get("checkedBy") and e.get("checkedOn")):
            raise RegisterError(f"entry {key}: a cleared entry names who checked it (checkedBy) and when (checkedOn)")
        checkpoints = tuple(dict(c) for c in e.get("checkpoints") or [])
        for j, c in enumerate(checkpoints):
            missing = [f for f in _CHECKPOINT_FIELDS if not c.get(f)]
            if missing:
                raise RegisterError(f"entry {key} checkpoints[{j}]: {', '.join(missing)} required")
            digest = str(c["sha256"]).lower()
            if len(digest) != 64 or any(ch not in "0123456789abcdef" for ch in digest):
                raise RegisterError(f"entry {key} checkpoints[{j}]: sha256 must be 64 hexadecimal characters")
        assessment = {k: str(v).strip() for k, v in (e.get("assessment") or {}).items() if str(v or "").strip()}
        if e["status"] == "cleared" and e["component"] == "weights":
            if not checkpoints:
                raise RegisterError(f"entry {key}: cleared weights list the checkpoints the clearance covers")
            unanswered = [q for q in ASSESSMENT if q not in assessment]
            if unanswered:
                raise RegisterError(f"entry {key}: cleared weights answer every assessment question; missing "
                                    + ", ".join(unanswered))
        entries[key] = LicenceEntry(key, str(e.get("name") or key), e["component"], str(e.get("licence") or ""),
                                    str(e.get("source") or ""), e["status"], e.get("checkedBy"),
                                    None if e.get("checkedOn") is None else str(e["checkedOn"]),
                                    str(e.get("notes") or "").strip(), checkpoints, assessment or None)
    return entries


def eligibility(keys: list[str], register: dict[str, LicenceEntry], sha256: str | None = None) -> dict[str, Any]:
    """``cleared`` / ``excluded`` / ``needs review`` for a candidate naming ``keys`` whose weight (or predictions)
    file has ``sha256``, with the entries behind it."""
    entries = []
    unknown = []
    for key in keys:
        entry = register.get(key)
        if entry is None:
            unknown.append(key)
            continue
        entries.append({"key": key, "name": entry.name, "component": entry.component, "licence": entry.licence,
                        "status": entry.status, "checkedBy": entry.checked_by, "checkedOn": entry.checked_on,
                        "notes": entry.notes, "checkpoints": list(entry.checkpoints),
                        "assessment": entry.assessment})
    statuses = {e["status"] for e in entries}
    components = {e["component"] for e in entries}
    reasons = []
    if not keys:
        reasons.append("the candidate names no licence register entries")
    if unknown:
        reasons.append("not in the register: " + ", ".join(unknown))
    for needed in ("code", "weights"):
        if keys and needed not in components:
            reasons.append(f"no register entry for its {needed}")
    for e in entries:
        entry = register[e["key"]]
        if entry.component == "weights" and entry.status == "cleared":
            if not sha256:
                reasons.append(f"{entry.key}: cleared per file, and the candidate gives no sha256")
            elif not entry.covers(sha256):
                reasons.append(f"{entry.key}: the candidate's weight file ({sha256[:12]}…) is not a listed checkpoint")
    if "excluded" in statuses:
        status = "excluded"
        reasons.extend(f"{e['key']}: excluded ({e['licence']})" for e in entries if e["status"] == "excluded")
    elif statuses == {"cleared"} and not reasons:
        status = "cleared"
    else:
        status = "needs review"
        reasons.extend(f"{e['key']}: {e['status']}" for e in entries if e["status"] != "cleared")
    return {"status": status, "reasons": reasons, "entries": entries}
