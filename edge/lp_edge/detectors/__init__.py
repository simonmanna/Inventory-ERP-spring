"""Pluggable detectors.

The configuration names a detector kind (``model.detector``). Built in: ``yolox`` and ``yolox-decoded``. Another kind
can be added without changing the agent by installing a package that declares an entry point in the group
``lp_edge.detectors``; the entry point is a callable ``factory(model_path, spec, providers) -> Detector``.
"""

from __future__ import annotations

import logging
from collections.abc import Callable, Sequence
from importlib.metadata import entry_points
from pathlib import Path
from typing import Any

from lp_edge.detectors.base import Detection, Detector

log = logging.getLogger(__name__)

Factory = Callable[[Path, Any, Sequence[str]], Detector]


def _yolox(decoded: bool) -> Factory:
    def factory(model_path: Path, spec: Any, providers: Sequence[str]) -> Detector:
        from lp_edge.detectors.yolox import YoloxDetector  # noqa: PLC0415

        return YoloxDetector(
            model_path,
            version=spec.version,
            classes=spec.classes,
            input_size=spec.input_size,
            decoded=decoded,
            providers=providers or None,
        )

    return factory


BUILT_IN: dict[str, Factory] = {"yolox": _yolox(False), "yolox-decoded": _yolox(True)}


def factory_for(kind: str) -> Factory:
    if kind in BUILT_IN:
        return BUILT_IN[kind]
    for entry in entry_points(group="lp_edge.detectors"):
        if entry.name == kind:
            return entry.load()
    raise ValueError(f"no detector of kind {kind!r} is installed")


def load_detector(model_path: Path, spec: Any, providers: Sequence[str] = ()) -> Detector:
    detector = factory_for(spec.detector)(model_path, spec, providers)
    log.info("detector %s %s loaded (%d classes)", spec.detector, spec.version, len(spec.classes))
    return detector


__all__ = ["Detection", "Detector", "BUILT_IN", "factory_for", "load_detector"]
