"""Detector models: downloaded once, checked (signature, then SHA-256), kept in ``<data>/models/<sha256>.onnx``.

A model is used only when its signature verifies against the vendor's key and the downloaded file has the SHA-256
the signature covers. The current and the previous model are kept, so a rollback needs no download.
"""

from __future__ import annotations

import logging
import os
import re
from collections.abc import Callable
from pathlib import Path

from lp_edge.files import ensure_private_dir, sha256_file
from lp_edge.remote_config import ModelSpec
from lp_edge.signing import TrustStore, model_message

log = logging.getLogger(__name__)

_SHA256 = re.compile(r"^[0-9a-f]{64}$")


class ModelError(Exception):
    pass


class ModelManager:
    def __init__(self, root: Path, trust: TrustStore | None, download: Callable[[str, Path], str]) -> None:
        self._root = root
        self._trust = trust
        self._download = download

    def verify(self, spec: ModelSpec) -> None:
        if self._trust is None:
            raise ModelError("LP_EDGE_TRUST_KEY is not set: the model's signature cannot be checked, so it is not used")
        if not _SHA256.match(spec.sha256):
            raise ModelError("the model's sha256 is not 64 lowercase hex digits")
        if not self._trust.verify(model_message(spec.version, spec.sha256, spec.url), spec.signature):
            raise ModelError(f"the signature of model {spec.version} does not verify; it is not used")

    def ensure(self, spec: ModelSpec) -> Path:
        """The verified model file, downloaded when it is not here yet."""
        self.verify(spec)
        ensure_private_dir(self._root)
        path = self._root / f"{spec.sha256}.onnx"
        if path.exists():
            if sha256_file(path) == spec.sha256:
                return path
            log.warning("model file %s is damaged; downloading it again", path.name)
            path.unlink()
        partial = self._root / f".{spec.sha256}.part"
        try:
            digest = self._download(spec.url, partial)
            if digest != spec.sha256:
                raise ModelError(f"the downloaded model {spec.version} does not match its sha256")
            os.replace(partial, path)
        finally:
            partial.unlink(missing_ok=True)
        log.info("model %s downloaded and verified", spec.version)
        self.prune({spec.sha256})
        return path

    def prune(self, keep: set[str], spare: int = 1) -> None:
        """Delete model files other than ``keep`` and the ``spare`` most recent others."""
        files = sorted(self._root.glob("*.onnx"), key=lambda p: p.stat().st_mtime, reverse=True)
        others = [p for p in files if p.stem not in keep]
        for path in others[spare:]:
            path.unlink(missing_ok=True)
