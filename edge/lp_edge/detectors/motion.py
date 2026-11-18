"""Movement inside a zone from OpenCV's MOG2 background subtraction: presence when no person detector is installed.

Movement is not a person count: a zone with movement counts as one presence, a still zone as none.
"""

from __future__ import annotations

from collections.abc import Sequence
from typing import Any

from lp_edge.video import cv2_module, polygon_mask, resize_to_width


class MotionModel:
    """One background model per camera, on a small copy of each analysed frame."""

    def __init__(self, *, width: int = 320, history: int = 300, var_threshold: float = 25.0) -> None:
        cv2 = cv2_module()
        self._cv2 = cv2
        self._subtractor = cv2.createBackgroundSubtractorMOG2(
            history=history, varThreshold=var_threshold, detectShadows=False
        )
        self._width = width
        self._foreground: Any = None
        self._masks: dict[tuple[Any, ...], Any] = {}

    def update(self, frame: Any) -> None:
        small = resize_to_width(frame, self._width)
        foreground = self._subtractor.apply(small)
        self._foreground = self._cv2.medianBlur(foreground, 3)

    def ratio(self, polygon: Sequence[tuple[float, float]]) -> float:
        """Share of the zone's pixels that moved in the latest frame, 0 to 1."""
        if self._foreground is None or len(polygon) < 3:
            return 0.0
        height, width = self._foreground.shape[:2]
        key = (tuple((float(x), float(y)) for x, y in polygon), height, width)
        mask = self._masks.get(key)
        if mask is None:
            mask = polygon_mask(polygon, height, width)
            self._masks[key] = mask
        zone_pixels = self._cv2.countNonZero(mask)
        if zone_pixels == 0:
            return 0.0
        moving = self._cv2.countNonZero(self._cv2.bitwise_and(self._foreground, mask))
        return moving / float(zone_pixels)
