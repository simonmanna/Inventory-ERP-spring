"""What an activity recognizer is (LP7, research).

A recognizer watches the tracks of one zone and reports **segments**: an activity (a name such as
``object_carried_out``) from a start to an end, with a score from 0 to 1 when it has one, and the tracker's numbers of
the objects involved. The agent sends each segment as ``vision.activity``. No backend rule reads an activity: they
are research, measured against people's reviews and labelled footage, and never make a finding or a case.

An activity is about what happens to **objects** in a zone. A recognizer must not estimate a person's pose, look at
faces, identify anyone or profile anyone's behaviour; the baseline recognizers read nothing but object tracks.

A recognizer reports a segment no earlier than the frame in which it ends; one that reports later (it waits for a
confirmation) says from when in :meth:`BaseRecognizer.pending_since`, so the camera's watermark waits for it.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from typing import TYPE_CHECKING, Protocol, runtime_checkable

from lp_edge.events import ACTIVITY_NAME

if TYPE_CHECKING:
    from lp_edge.profiles.base import FrameContext
    from lp_edge.remote_config import Zone
    from lp_edge.tracker import Track


@dataclass(frozen=True)
class Segment:
    """An activity from ``started_at`` to ``ended_at`` (epoch seconds)."""

    activity: str
    started_at: float
    ended_at: float
    score: float | None = None
    track_ids: tuple[int, ...] = ()


@runtime_checkable
class Recognizer(Protocol):
    """What the agent needs of a recognizer, built in or installed (entry point group
    ``lp_edge.activity_recognizers``: a callable ``factory(zone) -> Recognizer``)."""

    name: str
    version: str
    labels: tuple[str, ...]

    def observe(self, frame: FrameContext, tracks: Sequence[Track], zone: Zone) -> list[Segment]:
        """One analysed frame: the zone's tracks (its classes, above its minimum confidence)."""
        ...

    def flush(self, ts: float) -> list[Segment]:
        """Observation stops after ``ts``: what can still be reported."""
        ...


class BaseRecognizer:
    """A convenient base: no state to reset, nothing pending, nothing to flush."""

    name = ""
    version = "1"
    labels: tuple[str, ...] = ()

    def reset(self) -> None:
        """Observation (re)starts: forget what was seen before."""

    def pending_since(self) -> float | None:
        """The earliest end a segment not yet reported may have, or ``None``."""
        return None

    def observe(self, frame: FrameContext, tracks: Sequence[Track], zone: Zone) -> list[Segment]:
        raise NotImplementedError

    def flush(self, ts: float) -> list[Segment]:
        return []


def valid_name(name: object) -> bool:
    return isinstance(name, str) and bool(ACTIVITY_NAME.fullmatch(name))
