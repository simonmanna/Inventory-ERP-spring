"""Which activity recognizers a zone runs (LP7, research).

Built in: the baseline recognizers (:mod:`lp_edge.activities.baseline`). Others are added without changing the agent
by installing a package that declares an entry point in the group ``lp_edge.activity_recognizers``: the entry point
is a callable ``factory(zone) -> Recognizer``, named by the entry point's name. No model ships with the agent.

A zone names the recognizers it runs in ``params.activityRecognizers`` (default: every baseline recognizer). A name
that is not installed, or a factory that fails, is logged and skipped; the zone's own profile runs regardless.
"""

from __future__ import annotations

import logging
from collections.abc import Callable, Mapping
from importlib.metadata import entry_points

from lp_edge.activities.base import Recognizer, valid_name
from lp_edge.activities.baseline import ObjectCarriedOut, ObjectLeftBehind
from lp_edge.remote_config import Zone

log = logging.getLogger(__name__)

ENTRY_POINT_GROUP = "lp_edge.activity_recognizers"

Factory = Callable[[Zone], Recognizer]

BASELINE: dict[str, Factory] = {
    ObjectCarriedOut.name: ObjectCarriedOut.from_zone,
    ObjectLeftBehind.name: ObjectLeftBehind.from_zone,
}


def installed() -> dict[str, Factory]:
    """Every recognizer this agent can run, by name: the baseline ones and the installed entry points."""
    found: dict[str, Factory] = dict(BASELINE)
    try:
        entries = list(entry_points(group=ENTRY_POINT_GROUP))
    except Exception:  # noqa: BLE001  (a broken package must not stop the camera)
        log.exception("the installed activity recognizers could not be listed")
        entries = []
    for entry in entries:
        if entry.name in found:
            log.warning("activity recognizer %r is built in; the installed entry point is ignored", entry.name)
            continue
        found[entry.name] = _deferred(entry)
    return found


def _deferred(entry: object) -> Factory:
    def factory(zone: Zone) -> Recognizer:
        return entry.load()(zone)  # type: ignore[attr-defined]

    return factory


def usable(recognizer: object) -> bool:
    """Whether ``recognizer`` has what the agent calls: a name, a version, labels, ``observe`` and ``flush``."""
    name = getattr(recognizer, "name", None)
    version = getattr(recognizer, "version", None)
    labels = getattr(recognizer, "labels", None)
    return (valid_name(name) and isinstance(version, str) and bool(version)
            and isinstance(labels, (tuple, list)) and all(valid_name(label) for label in labels)
            and callable(getattr(recognizer, "observe", None)) and callable(getattr(recognizer, "flush", None)))


def recognizers_for(zone: Zone, available: Mapping[str, Factory] | None = None) -> list[Recognizer]:
    """The recognizers ``zone`` runs, in the order its ``activityRecognizers`` names them."""
    factories = installed() if available is None else available
    names = zone.names("activityRecognizers", list(BASELINE))
    out: list[Recognizer] = []
    for name in dict.fromkeys(names):
        factory = factories.get(name)
        if factory is None:
            log.warning("zone %s: activity recognizer %r is not installed", zone.id, name)
            continue
        try:
            recognizer = factory(zone)
        except Exception:  # noqa: BLE001
            log.exception("zone %s: activity recognizer %r could not be started", zone.id, name)
            continue
        if not usable(recognizer):
            log.warning("zone %s: activity recognizer %r lacks a name, version, labels, observe or flush", zone.id,
                        name)
            continue
        out.append(recognizer)
    return out
