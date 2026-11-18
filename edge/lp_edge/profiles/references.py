"""Resting pictures of drawer zones, kept encrypted under ``<data>/references`` so a restart does not relearn them."""

from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Any

from lp_edge.crypto import Cipher
from lp_edge.files import ensure_private_dir, safe_name, write_private

log = logging.getLogger(__name__)


class EncryptedReferenceStore:
    def __init__(self, root: Path, cipher: Cipher) -> None:
        self._root = root
        self._cipher = cipher

    def _path(self, zone_id: str) -> Path:
        return self._root / f"{safe_name(zone_id)}.ref"

    def load(self, zone_id: str, fingerprint: str) -> tuple[Any, str] | None:
        path = self._path(zone_id)
        if not path.exists():
            return None
        plain = self._cipher.open(path.read_bytes(), aad=zone_id.encode("utf-8"))
        header_line, _, raw = plain.partition(b"\n")
        header = json.loads(header_line)
        if header.get("fingerprint") != fingerprint:
            return None
        import numpy as np  # noqa: PLC0415

        reference = np.frombuffer(raw, dtype=np.dtype(header["dtype"])).reshape(header["shape"]).copy()
        return reference, str(header.get("kind") or "automatic")

    def save(self, zone_id: str, fingerprint: str, reference: Any, kind: str) -> None:
        ensure_private_dir(self._root)
        header = {"fingerprint": fingerprint, "shape": list(reference.shape), "dtype": str(reference.dtype), "kind": kind}
        plain = json.dumps(header).encode("utf-8") + b"\n" + reference.tobytes()
        write_private(self._path(zone_id), self._cipher.seal(plain, aad=zone_id.encode("utf-8")))

    def remove_except(self, zone_ids: set[str]) -> None:
        """Delete the pictures of zones that no longer exist."""
        if not self._root.exists():
            return
        keep = {f"{safe_name(z)}.ref" for z in zone_ids}
        for path in self._root.glob("*.ref"):
            if path.name not in keep:
                path.unlink(missing_ok=True)
