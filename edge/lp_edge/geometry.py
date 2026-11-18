"""Zone geometry on the normalised picture: x from 0 (left) to 1 (right), y from 0 (top) to 1 (bottom).

A counting line is a zone of two points, A then B. Its sides are named as seen on the picture by someone walking from
A to B: with y pointing down, a point is on the **right** of A → B when the cross product
``(B - A) × (P - A)`` is positive, and on the **left** when it is negative.
"""

from __future__ import annotations

import math
from collections.abc import Sequence

Point = tuple[float, float]
Box = tuple[float, float, float, float]  # x1, y1, x2, y2


def cross(a: Point, b: Point, p: Point) -> float:
    return (b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0])


def side_of_line(a: Point, b: Point, p: Point) -> str | None:
    """``right``, ``left`` or ``None`` (on the line) of ``p`` relative to A → B, as seen on the picture."""
    value = cross(a, b, p)
    if value > 0:
        return "right"
    if value < 0:
        return "left"
    return None


def line_distance(a: Point, b: Point, p: Point) -> float:
    """Signed distance from ``p`` to the infinite line A → B (positive on the right)."""
    length = math.hypot(b[0] - a[0], b[1] - a[1])
    if length == 0:
        return math.hypot(p[0] - a[0], p[1] - a[1])
    return cross(a, b, p) / length


def projection(a: Point, b: Point, p: Point) -> float:
    """Where ``p`` falls along A → B: 0 at A, 1 at B."""
    dx, dy = b[0] - a[0], b[1] - a[1]
    length2 = dx * dx + dy * dy
    if length2 == 0:
        return 0.0
    return ((p[0] - a[0]) * dx + (p[1] - a[1]) * dy) / length2


def segment_distance(p: Point, a: Point, b: Point) -> float:
    t = max(0.0, min(1.0, projection(a, b, p)))
    x, y = a[0] + t * (b[0] - a[0]), a[1] + t * (b[1] - a[1])
    return math.hypot(p[0] - x, p[1] - y)


def point_in_polygon(p: Point, polygon: Sequence[Point]) -> bool:
    """Even-odd rule; a point exactly on an edge may fall either way."""
    x, y = p
    inside = False
    n = len(polygon)
    if n < 3:
        return False
    j = n - 1
    for i in range(n):
        xi, yi = polygon[i]
        xj, yj = polygon[j]
        if (yi > y) != (yj > y):
            x_cross = (xj - xi) * (y - yi) / (yj - yi) + xi
            if x < x_cross:
                inside = not inside
        j = i
    return inside


def boundary_distance(p: Point, polygon: Sequence[Point]) -> float:
    n = len(polygon)
    return min(segment_distance(p, polygon[i], polygon[(i + 1) % n]) for i in range(n))


def anchor(box: Box, mode: str) -> Point:
    """The point of a box that stands for the object: ``bottom`` (feet) or ``center``."""
    x1, y1, x2, y2 = box
    if mode == "bottom":
        return ((x1 + x2) / 2.0, y2)
    return ((x1 + x2) / 2.0, (y1 + y2) / 2.0)


def area(box: Box) -> float:
    return max(0.0, box[2] - box[0]) * max(0.0, box[3] - box[1])


def iou(a: Box, b: Box) -> float:
    ix1, iy1 = max(a[0], b[0]), max(a[1], b[1])
    ix2, iy2 = min(a[2], b[2]), min(a[3], b[3])
    inter = max(0.0, ix2 - ix1) * max(0.0, iy2 - iy1)
    if inter <= 0:
        return 0.0
    union = area(a) + area(b) - inter
    return inter / union if union > 0 else 0.0


def bounds(polygon: Sequence[Point]) -> Box:
    xs = [p[0] for p in polygon]
    ys = [p[1] for p in polygon]
    return (min(xs), min(ys), max(xs), max(ys))
