"""The detector interface. A detector turns one picture into boxes of known classes; nothing else.

Boxes are normalised (0 to 1) so zone polygons apply at any resolution. A detector never identifies a person: the
classes are things such as ``person``, ``hand``, ``plate``, ``cup``, ``bottle``, ``bag`` and ``cash_drawer``.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Protocol, runtime_checkable


@dataclass(frozen=True)
class Detection:
    cls: str
    score: float
    box: tuple[float, float, float, float]  # x1, y1, x2, y2, normalised


@runtime_checkable
class Detector(Protocol):
    #: model version (``modelVersion`` in the heartbeat)
    version: str
    #: class names the model knows
    classes: tuple[str, ...]

    def detect(self, frame: Any) -> list[Detection]:
        """Detections in ``frame`` (a BGR image as a NumPy array)."""
        ...
