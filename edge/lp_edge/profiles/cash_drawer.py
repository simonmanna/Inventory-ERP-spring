"""Cash drawer zones (feeds V-C1): the drawer opens and closes.

Method ``reference_diff``: the zone's picture is compared with its resting picture (a closed drawer). The score is the
mean absolute difference inside the polygon, 0 to 1, after both pictures are blurred and have their mean brightness
removed, so a light switched on does not open a drawer. Hysteresis: the drawer opens when the score stays at or above
``openThreshold`` for ``minOpenSeconds``, and closes when it stays at or below ``openThreshold × closeRatio`` (0.6)
for ``minClosedSeconds``.

The resting picture comes from the ``calibrate`` command (an installer points the camera at a closed drawer), or is
learnt as the median of ``referenceFrames`` pictures one second apart. While it is learnt the zone is not observed and
the period is reported as ``vision.gap``. While the drawer stays closed the reference follows slow light changes.
"""

from __future__ import annotations

import json
import logging
from dataclasses import dataclass
from typing import Any

from lp_edge.events import STATE_CHANGED, Observation, gap
from lp_edge.profiles.base import FrameContext, ProfileEnv, ZoneProfile
from lp_edge.remote_config import Zone
from lp_edge.video import cv2_module, np_module, polygon_mask

log = logging.getLogger(__name__)

OBJECT = "cash_drawer"


@dataclass(frozen=True)
class DrawerTransition:
    from_state: str
    to_state: str
    at: float
    seconds_in_previous: float | None
    score: float
    confidence: float


class DrawerStateMachine:
    """Open/closed with hysteresis on a 0–1 score. Pure logic; the score comes from :class:`ReferenceDiff`."""

    def __init__(
        self, open_threshold: float, min_open_seconds: float, min_closed_seconds: float, close_ratio: float = 0.6
    ) -> None:
        if not 0 < open_threshold <= 1:
            raise ValueError("openThreshold must be between 0 and 1")
        self.open_threshold = open_threshold
        self.close_threshold = open_threshold * max(0.0, min(1.0, close_ratio))
        self.min_open_seconds = max(0.0, min_open_seconds)
        self.min_closed_seconds = max(0.0, min_closed_seconds)
        self.state = "closed"
        self.since: float | None = None
        self._since_known = False
        self._candidate: float | None = None
        self._extreme = 0.0

    def reset(self, ts: float, state: str = "closed") -> None:
        """Start again at ``ts`` in ``state``; how long it had been in that state is unknown."""
        self.state = state
        self.since = ts
        self._since_known = False
        self._candidate = None

    def pending_since(self) -> float | None:
        """When a change waiting for confirmation began (it will be reported at that instant)."""
        return self._candidate

    def update(self, ts: float, score: float) -> DrawerTransition | None:
        if self.since is None:
            self.reset(ts)
        if self.state == "closed":
            if score >= self.open_threshold:
                if self._candidate is None:
                    self._candidate, self._extreme = ts, score
                else:
                    self._extreme = max(self._extreme, score)
                if ts - self._candidate >= self.min_open_seconds:
                    return self._flip("open")
            else:
                self._candidate = None
        else:
            if score <= self.close_threshold:
                if self._candidate is None:
                    self._candidate, self._extreme = ts, score
                else:
                    self._extreme = min(self._extreme, score)
                if ts - self._candidate >= self.min_closed_seconds:
                    return self._flip("closed")
            else:
                self._candidate = None
        return None

    def _flip(self, to_state: str) -> DrawerTransition:
        at = self._candidate if self._candidate is not None else 0.0
        previous = (at - self.since) if (self._since_known and self.since is not None) else None
        transition = DrawerTransition(
            from_state=self.state,
            to_state=to_state,
            at=at,
            seconds_in_previous=None if previous is None else max(0.0, previous),
            score=self._extreme,
            confidence=self._confidence(to_state, self._extreme),
        )
        self.state = to_state
        self.since = at
        self._since_known = True
        self._candidate = None
        return transition

    def _confidence(self, to_state: str, extreme: float) -> float:
        """0.5 at the threshold, rising to 0.99 at twice the margin."""
        if to_state == "open":
            margin = (extreme - self.open_threshold) / self.open_threshold
        elif self.close_threshold > 0:
            margin = (self.close_threshold - extreme) / self.close_threshold
        else:
            margin = 1.0
        return round(min(0.99, 0.5 + 0.5 * max(0.0, min(1.0, margin))), 3)


class ReferenceDiff:
    """Difference between a zone's picture and its resting picture, 0 to 1 (NumPy and OpenCV)."""

    def __init__(
        self,
        polygon: tuple[tuple[float, float], ...],
        *,
        warmup_samples: int = 30,
        sample_every: float = 1.0,
        adapt_seconds: float = 120.0,
    ) -> None:
        self.polygon = polygon
        self.warmup_samples = max(1, warmup_samples)
        self.sample_every = sample_every
        self.adapt_seconds = max(1.0, adapt_seconds)
        self.reference: Any = None
        self.kind: str | None = None
        self._shape: tuple[int, ...] | None = None
        self._rect = (0, 0, 0, 0)
        self._mask: Any = None
        self._samples: list[Any] = []
        self._next_sample: float | None = None
        self._last: Any = None

    @property
    def ready(self) -> bool:
        return self.reference is not None

    def set_reference(self, reference: Any, kind: str) -> None:
        self.reference = reference
        self.kind = kind
        self._samples = []

    def features(self, frame: Any) -> Any:
        """The zone's crop, grey, blurred, 0–1, mean removed; ``None`` when the zone is too small."""
        np = np_module()
        cv2 = cv2_module()
        if self._shape != frame.shape:
            self._prepare(frame.shape)
        if self._mask is None:
            return None
        x0, y0, x1, y1 = self._rect
        crop = frame[y0:y1, x0:x1]
        grey = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY) if crop.ndim == 3 else crop
        blurred = cv2.GaussianBlur(grey, (5, 5), 0).astype(np.float32) / 255.0
        return blurred - float(blurred[self._mask].mean())

    def score(self, frame: Any, ts: float) -> float | None:
        """The score of ``frame``, or ``None`` while the resting picture is still being learnt."""
        np = np_module()
        current = self.features(frame)
        if current is None:
            return None
        if self.reference is None or self.reference.shape != current.shape:
            if self.reference is not None:
                log.info("the picture size changed; learning the drawer's resting picture again")
                self.reference = None
                self.kind = None
            self._collect(current, ts)
            return None
        self._last = current
        return float(min(1.0, np.abs(current - self.reference)[self._mask].mean()))

    def adapt(self, seconds: float) -> None:
        """Move the reference towards the current picture (only called while the drawer is closed)."""
        if self._last is None or self.reference is None or seconds <= 0:
            return
        weight = min(1.0, seconds / self.adapt_seconds)
        self.reference = self.reference + weight * (self._last - self.reference)

    def calibrate(self, frame: Any) -> bool:
        current = self.features(frame)
        if current is None:
            return False
        self.set_reference(current.copy(), "calibrated")
        self._last = current
        return True

    def _collect(self, current: Any, ts: float) -> None:
        np = np_module()
        if self._next_sample is None or ts >= self._next_sample:
            self._samples.append(current)
            self._next_sample = ts + self.sample_every
        if len(self._samples) >= self.warmup_samples:
            self.set_reference(np.median(np.stack(self._samples), axis=0).astype(np.float32), "automatic")

    def _prepare(self, shape: tuple[int, ...]) -> None:
        self._shape = shape
        height, width = shape[0], shape[1]
        xs = [p[0] for p in self.polygon]
        ys = [p[1] for p in self.polygon]
        x0 = max(0, int(min(xs) * (width - 1)))
        y0 = max(0, int(min(ys) * (height - 1)))
        x1 = min(width, int(max(xs) * (width - 1)) + 1)
        y1 = min(height, int(max(ys) * (height - 1)) + 1)
        mask = polygon_mask(self.polygon, height, width)[y0:y1, x0:x1] > 0
        if x1 - x0 < 4 or y1 - y0 < 4 or int(mask.sum()) < 16:
            self._mask = None
            log.warning("a cash_drawer zone covers too few pixels to be watched")
            return
        self._rect = (x0, y0, x1, y1)
        self._mask = mask
        self._samples = []
        self._next_sample = None


class CashDrawerProfile(ZoneProfile):
    def __init__(self, zone: Zone, camera_id: str, env: ProfileEnv, detector_classes: frozenset[str] | None) -> None:
        super().__init__(zone, camera_id, env, detector_classes)
        self.threshold = zone.num("openThreshold", 0.18)
        self.machine = DrawerStateMachine(
            self.threshold,
            zone.num("minOpenSeconds", 1),
            zone.num("minClosedSeconds", 2),
            close_ratio=zone.num("closeRatio", 0.6),
        )
        self.diff = ReferenceDiff(
            zone.polygon,
            warmup_samples=int(zone.num("referenceFrames", 30)),
            adapt_seconds=zone.num("adaptSeconds", 120),
        )
        self.keep_clips = zone.flag("keepClips", True)
        self._fingerprint = json.dumps(zone.polygon)
        self._loaded = False
        self._warm_from: float | None = None
        self._last_ts: float | None = None

    def needs_frame(self) -> bool:
        return True

    def _reset(self, ts: float) -> None:
        self.machine.reset(ts)
        self._last_ts = ts
        if not self._loaded:
            self._loaded = True
            self._load_reference()
        self._warm_from = None if self.diff.ready else ts

    def process(self, frame: FrameContext) -> list[Observation]:
        if frame.frame is None:
            return []
        observations: list[Observation] = []
        score = self.diff.score(frame.frame, frame.ts)
        elapsed = frame.ts - (self._last_ts if self._last_ts is not None else frame.ts)
        self._last_ts = frame.ts
        if score is None:
            if self._warm_from is None:
                self._warm_from = frame.ts
            return observations
        if self._warm_from is not None:
            observations.append(self._learnt(frame.ts))
        transition = self.machine.update(frame.ts, score)
        if transition is not None:
            observations.append(self._state_changed(transition))
        elif (
            self.machine.state == "closed"
            and self.machine.pending_since() is None
            and score <= self.machine.close_threshold
        ):
            self.diff.adapt(elapsed)
        return observations

    def calibrate(self, frame: Any, ts: float) -> list[Observation]:
        """Take ``frame`` as the resting picture (a closed drawer)."""
        if not self.diff.calibrate(frame):
            raise ValueError("the zone covers too few pixels to calibrate")
        self._save_reference()
        observations = [self._learnt(ts)] if (self._warm_from is not None and self.started_at is not None) else []
        self.machine.reset(ts)
        return observations

    def _hold(self) -> float | None:
        if self._warm_from is not None:
            return self._warm_from
        return self.machine.pending_since()

    def _learnt(self, ts: float) -> Observation:
        """The resting picture is known from ``ts``; the time before is reported as not observed."""
        start = self._warm_from if self._warm_from is not None else ts
        self._warm_from = None
        self.machine.reset(ts)
        if self.diff.kind == "automatic":
            self._save_reference()
        return gap(self.zone.id, self.camera_id, "agent_down", start, ts, detail="learning the drawer's resting picture")

    def _state_changed(self, transition: DrawerTransition) -> Observation:
        payload: dict[str, Any] = {
            "object": OBJECT,
            "from": transition.from_state,
            "to": transition.to_state,
            "method": "reference_diff",
            "score": round(transition.score, 4),
            "threshold": self.threshold,
            "reference": self.diff.kind,
        }
        if transition.seconds_in_previous is not None:
            payload["secondsInPreviousState"] = round(transition.seconds_in_previous, 3)
        clip = self.clip_around(transition.at) if (transition.to_state == "open" and self.keep_clips) else None
        return self.observation(STATE_CHANGED, transition.at, payload, transition.confidence, clip)

    def _load_reference(self) -> None:
        if self.env.references is None:
            return
        try:
            loaded = self.env.references.load(self.zone.id, self._fingerprint)
        except Exception:  # noqa: BLE001 - a bad file only means learning again
            log.warning("the saved resting picture of zone %s could not be read", self.zone.id)
            return
        if loaded is not None:
            reference, kind = loaded
            self.diff.set_reference(reference, kind)

    def _save_reference(self) -> None:
        if self.env.references is None or self.diff.reference is None:
            return
        try:
            self.env.references.save(self.zone.id, self._fingerprint, self.diff.reference, self.diff.kind or "automatic")
        except Exception:  # noqa: BLE001
            log.exception("the resting picture of zone %s could not be saved", self.zone.id)
