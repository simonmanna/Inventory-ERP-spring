"""Zone profiles: one small piece of logic per zone type, on top of the camera's shared detector and tracker."""

from __future__ import annotations

import logging

from lp_edge.profiles.base import FrameContext, ProfileEnv, UnavailableProfile, ZoneProfile, unwrap
from lp_edge.profiles.cash_drawer import CashDrawerProfile
from lp_edge.profiles.checkout import CheckoutProfile
from lp_edge.profiles.counting import COUNTING_TYPES, CountingProfile
from lp_edge.profiles.handling import HandlingProfile
from lp_edge.profiles.queue import QueueProfile
from lp_edge.profiles.scan import ScanProfile
from lp_edge.remote_config import ZONE_DEFAULTS, Zone

log = logging.getLogger(__name__)


def build_profile(
    zone: Zone, camera_id: str, env: ProfileEnv, detector_classes: frozenset[str] | None
) -> ZoneProfile:
    """The profile for ``zone``; an :class:`UnavailableProfile` when this agent cannot observe it.

    A zone with ``activityResearch`` true (LP7) also runs its activity recognizers beside the profile; the profile's
    own observations are the same either way.
    """
    profile = _build(zone, camera_id, env, detector_classes)
    if zone.flag("activityResearch", False) and not isinstance(profile, UnavailableProfile):
        from lp_edge.activities.research import with_research  # noqa: PLC0415  (activities import the profiles)

        return with_research(profile)
    return profile


def _build(
    zone: Zone, camera_id: str, env: ProfileEnv, detector_classes: frozenset[str] | None
) -> ZoneProfile:
    """The zone type's own profile."""

    def unavailable(reason: str) -> ZoneProfile:
        log.warning("zone %s (%s) cannot be observed: %s", zone.id, zone.zone_type, reason)
        return UnavailableProfile(zone, camera_id, env, detector_classes, reason)

    kind = zone.zone_type
    if kind == "cash_drawer":
        if zone.is_line:
            return unavailable("a cash_drawer zone must be a polygon")
        method = zone.text("method", "reference_diff")
        if method != "reference_diff":
            log.warning("zone %s: drawer method %r is not available; using reference_diff", zone.id, method)
        return CashDrawerProfile(zone, camera_id, env, detector_classes)
    if kind == "checkout":
        if zone.is_line:
            return unavailable("a checkout zone must be a polygon")
        return CheckoutProfile(zone, camera_id, env, detector_classes)
    if kind == "bar_counter":
        if zone.is_line:
            return unavailable("a bar_counter zone must be a polygon")
        wanted = zone.names("objects", ZONE_DEFAULTS["bar_counter"]["objects"])
        known = _known(wanted, detector_classes)
        if not known:
            return unavailable(_missing(wanted, detector_classes))
        return HandlingProfile(zone, camera_id, env, detector_classes, objects=known)
    if kind in COUNTING_TYPES:
        wanted = zone.names("classes", ZONE_DEFAULTS.get(kind, {}).get("classes", ["person"]))
        known = _known(wanted, detector_classes)
        if not known:
            return unavailable(_missing(wanted, detector_classes))
        return CountingProfile(zone, camera_id, env, detector_classes, classes=known)
    if kind == "scan_area":
        if zone.is_line:
            return unavailable("a scan_area zone must be a polygon over the scanner")
        wanted = zone.names("classes", ZONE_DEFAULTS["scan_area"]["classes"])
        known = _known(wanted, detector_classes)
        if not known:
            return unavailable(_missing(wanted, detector_classes))
        return ScanProfile(zone, camera_id, env, detector_classes, classes=known)
    if kind == "queue":
        if zone.is_line:
            return unavailable("a queue zone must be a polygon")
        wanted = zone.names("classes", ZONE_DEFAULTS["queue"]["classes"])
        known = _known(wanted, detector_classes)
        if not known:
            return unavailable(_missing(wanted, detector_classes))
        return QueueProfile(zone, camera_id, env, detector_classes, classes=known)
    return unavailable(f"zone type {kind!r} is not supported by this agent")


def _known(wanted: list[str], detector_classes: frozenset[str] | None) -> list[str]:
    if detector_classes is None:
        return []
    known = [c for c in wanted if c in detector_classes]
    if known and len(known) < len(wanted):
        log.warning("the detector does not know %s", ", ".join(c for c in wanted if c not in detector_classes))
    return known


def _missing(wanted: list[str], detector_classes: frozenset[str] | None) -> str:
    if detector_classes is None:
        return "no detector model is installed"
    return "the detector model does not know " + ", ".join(wanted)


__all__ = ["FrameContext", "ProfileEnv", "ZoneProfile", "UnavailableProfile", "build_profile", "unwrap"]
