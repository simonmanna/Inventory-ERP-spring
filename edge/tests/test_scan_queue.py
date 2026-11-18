"""Scan areas (LP5) and queues (LP6b): the zone defaults, the two profiles, and the vision.activity contract (LP7)."""

import pytest
from fakes import SQUARE, T0, box_at, track, zone

from lp_edge.events import ACTIVITY, DWELL, HANDLING, LINE_CROSSED, OCCUPANCY, Observation, envelope, validate
from lp_edge.profiles import ProfileEnv, UnavailableProfile, build_profile
from lp_edge.profiles.base import FrameContext
from lp_edge.profiles.queue import QueueProfile
from lp_edge.profiles.scan import ScanProfile, flow_coordinates
from lp_edge.remote_config import ZONE_TYPES
from lp_edge.timeutil import iso

#: the scanner: x 0.45–0.55, y 0.4–0.6; the lane is y 0.25–0.75 with the default laneMargin 0.15
SCANNER = [[0.45, 0.4], [0.55, 0.4], [0.55, 0.6], [0.45, 0.6]]
DETECTOR = frozenset({"item", "person"})


def item(track_id, x, y=0.5, score=0.9, cls="item"):
    return track(track_id, box_at(x, y, 0.06, 0.06), cls=cls, score=score)


def scan_profile(**params):
    profile = build_profile(zone("scan_area", SCANNER, **params), "cam-1", ProfileEnv(new_clip_id=lambda: "clip-1"),
                            DETECTOR)
    assert isinstance(profile, ScanProfile)
    profile.start(T0)
    return profile


def path(profile, track_id, xs, y=0.5, t0=T0, **kw):
    out = []
    for i, x in enumerate(xs):
        out += profile.process(FrameContext(ts=t0 + i, tracks=[item(track_id, x, y, **kw)]))
    return out


def sent(observation):
    return envelope(observation, agent_id="a1", branch_id="b1")


def test_the_new_zone_types_take_the_specified_defaults():
    assert {"scan_area", "queue"} <= set(ZONE_TYPES)
    assert dict(zone("scan_area", SCANNER).params) == {
        "minConfidence": 0.5, "classes": ["item"], "flowDirection": "left_to_right", "laneMargin": 0.15,
        "preRollSeconds": 10, "postRollSeconds": 10}
    assert dict(zone("queue", SQUARE).params) == {
        "minConfidence": 0.5, "classes": ["person"], "minWaitSeconds": 5, "occupancyEverySeconds": 60}


def test_scan_and_queue_zones_need_a_polygon_and_a_detector_that_knows_their_classes():
    env = ProfileEnv()
    for kind in ("scan_area", "queue"):
        line = build_profile(zone(kind, [[0.5, 0.0], [0.5, 1.0]]), "cam-1", env, DETECTOR)
        assert isinstance(line, UnavailableProfile) and "polygon" in line.reason
        assert build_profile(zone(kind, SQUARE), "cam-1", env, None).reason == "no detector model is installed"
    unknown = build_profile(zone("scan_area", SCANNER), "cam-1", env, frozenset({"person"}))
    assert unknown.reason == "the detector model does not know item"
    assert isinstance(build_profile(zone("queue", SQUARE), "cam-1", env, DETECTOR), QueueProfile)


def test_an_item_passed_over_the_scanner_is_counted_out_and_taken_back_in():
    profile = scan_profile()
    out = path(profile, 7, [0.2, 0.3, 0.5, 0.7, 0.8])
    assert [(o.type, o.payload, o.occurred_at, o.clip) for o in out] == [
        (LINE_CROSSED, {"class": "item", "direction": "out", "count": 1, "trackId": 7}, T0 + 3, None)]
    assert sent(out[0])["payload"]["cameraId"] == "cam-1"
    back = path(profile, 7, [0.7, 0.5, 0.3], t0=T0 + 5)
    assert [(o.type, o.payload["direction"], o.occurred_at) for o in back] == [(LINE_CROSSED, "in", T0 + 7)]


def test_an_item_carried_past_the_scanner_is_a_bypass_with_a_clip_of_the_passage():
    profile = scan_profile()
    out = path(profile, 9, [0.2, 0.4, 0.5, 0.6, 0.7], y=0.3)  # beside the scanner, on the lane
    assert [o.type for o in out] == [HANDLING]
    bypass = out[0]
    assert bypass.payload == {"object": "item", "action": "bypassed", "trackId": 9, "durationSeconds": 2.0}
    assert bypass.occurred_at == T0 + 3
    assert (bypass.clip.started_at, bypass.clip.ended_at) == (T0 + 1 - 10, T0 + 3 + 10)
    assert sent(bypass)["payload"]["clipId"] == "clip-1"
    # taken back around the scanner: not reported; on another lane: ignored
    assert path(profile, 10, [0.8, 0.6, 0.5, 0.4, 0.2], y=0.3, t0=T0 + 10) == []
    assert path(profile, 11, [0.2, 0.5, 0.8], y=0.9, t0=T0 + 20) == []


def test_jitter_partial_passages_and_other_objects_are_not_counted():
    profile = scan_profile()
    assert path(profile, 1, [0.43, 0.45, 0.43, 0.46, 0.435]) == []  # at the scanner's input edge
    assert path(profile, 2, [0.5, 0.6, 0.7], t0=T0 + 10) == []  # first seen over the scanner
    assert path(profile, 3, [0.2, 0.5, 0.8], t0=T0 + 20, score=0.3) == []  # below minConfidence
    assert path(profile, 4, [0.2, 0.5, 0.8], t0=T0 + 30, cls="person") == []  # not one of the zone's classes


def test_a_forgotten_item_starts_again():
    profile = scan_profile()
    assert path(profile, 5, [0.2, 0.5]) == []
    # lost for more than 10 s: seen again beyond the scanner, it has not been seen on the input side since
    assert path(profile, 5, [0.8], t0=T0 + 20) == []


def test_the_flow_direction_says_which_side_is_bagging():
    assert flow_coordinates((0.25, 0.75), "left_to_right") == (0.25, 0.75)
    assert flow_coordinates((0.25, 0.75), "right_to_left") == (0.75, 0.75)
    assert flow_coordinates((0.25, 0.75), "top_to_bottom") == (0.75, 0.25)
    assert flow_coordinates((0.25, 0.75), "bottom_to_top") == (0.25, 0.25)
    leftwards = scan_profile(flowDirection="right_to_left")
    assert [o.payload["direction"] for o in path(leftwards, 1, [0.8, 0.7, 0.5, 0.3])] == ["out"]
    downwards = scan_profile(flowDirection="top_to_bottom")
    out = []
    for i, y in enumerate([0.2, 0.3, 0.5, 0.7]):
        out += downwards.process(FrameContext(ts=T0 + i, tracks=[item(2, 0.5, y)]))
    assert [o.payload["direction"] for o in out] == ["out"]
    # an unknown direction falls back to left_to_right
    assert [o.payload["direction"] for o in path(scan_profile(flowDirection="sideways"), 3, [0.2, 0.5, 0.8])] == [
        "out"]


# ------------------------------------------------------------------------------------------------ queues


def person(track_id, x, feet_y=0.5, score=0.9):
    return track(track_id, (x - 0.05, feet_y - 0.2, x + 0.05, feet_y), score=score)


def queue_profile(**params):
    profile = build_profile(zone("queue", SQUARE, **params), "cam-1", ProfileEnv(), frozenset({"person"}))
    profile.start(T0)
    return profile


def test_a_wait_in_the_queue_is_a_dwell_reported_at_its_end():
    profile = queue_profile()
    out = []
    for i in range(10):  # waits from T0+1 to T0+8, then walks off; a second person passes through
        tracks = [person(3, 0.5 if 1 <= i <= 8 else 0.9)]
        if 2 <= i <= 5:
            tracks.append(person(4, [0.3, 0.45, 0.6, 0.9][i - 2]))
        out += profile.process(FrameContext(ts=T0 + i, tracks=tracks))
    dwell = [o for o in out if o.type == DWELL]
    assert [(o.payload, o.occurred_at) for o in dwell] == [
        ({"class": "person", "startedAt": iso(T0 + 1), "endedAt": iso(T0 + 8), "durationSeconds": 7.0,
          "maxCount": 1, "method": "track"}, T0 + 8)]
    assert sent(dwell[0])["occurredAt"] == iso(T0 + 8)
    occupancy = [o for o in profile.stop(T0 + 9) if o.type == OCCUPANCY]
    assert [o.payload for o in occupancy] == [{"count": 2, "class": "person", "periodSeconds": 9.0}]


def test_a_person_lost_from_view_ends_the_wait_where_last_seen_and_the_watermark_waits_for_it():
    profile = queue_profile(occupancyEverySeconds=10)
    out = []
    for i in range(1, 7):
        out += profile.process(FrameContext(ts=T0 + i, tracks=[person(5, 0.5)]))
    for i in range(7, 12):
        out += profile.process(FrameContext(ts=T0 + i))
    assert profile.hold() == T0 + 6  # the wait may still be reported at T0+6, before the period begun at T0+10
    for i in range(12, 18):
        out += profile.process(FrameContext(ts=T0 + i))
    assert [(o.payload["durationSeconds"], o.occurred_at) for o in out if o.type == DWELL] == [(5.0, T0 + 6)]
    assert profile.hold() == T0 + 10
    assert [(o.payload, o.occurred_at) for o in out if o.type == OCCUPANCY] == [
        ({"count": 1, "class": "person", "periodSeconds": 10.0}, T0 + 10)]


def test_someone_on_the_edge_keeps_waiting_and_a_short_wait_is_no_dwell():
    profile = queue_profile()
    out = []
    for i, x in enumerate([0.5, 0.6, 0.75, 0.752, 0.749, 0.6]):
        out += profile.process(FrameContext(ts=T0 + 2 * i, tracks=[person(6, x)]))
    assert [o for o in out if o.type == DWELL] == []
    dwell = [o for o in profile.stop(T0 + 10) if o.type == DWELL]
    assert [(o.payload["startedAt"], o.payload["durationSeconds"]) for o in dwell] == [(iso(T0), 10.0)]
    short = queue_profile(minWaitSeconds=30)
    for i in range(12):
        short.process(FrameContext(ts=T0 + i, tracks=[person(7, 0.5)]))
    assert [o for o in short.stop(T0 + 11) if o.type == DWELL] == []


# ------------------------------------------------------------------------------------------------ vision.activity


def test_activity_payloads_are_checked_before_they_are_sent():
    good = {"activity": "object_carried_out", "startedAt": iso(T0), "endedAt": iso(T0 + 2),
            "recognizer": "object_carried_out@1", "score": 0.7, "trackIds": [3], "cameraId": "cam-1"}
    validate(ACTIVITY, good)
    validate(ACTIVITY, {k: v for k, v in good.items() if k not in ("score", "trackIds")})
    for bad in ({"activity": "Carried"}, {"activity": "1_item"}, {"activity": "a" * 65}, {"activity": "item\n"},
                {"score": 1.5}, {"score": True}, {"recognizer": "x" * 201}, {"endedAt": iso(T0 - 1)},
                {"trackIds": "3"}, {"recognizer": None}):
        with pytest.raises(ValueError):
            validate(ACTIVITY, {**good, **bad})
    event = sent(Observation(ACTIVITY, "z1", "cam-1", T0 + 2, {k: v for k, v in good.items() if k != "cameraId"},
                             0.7))
    assert (event["type"], event["deviceId"], event["evidenceLevel"], event["evidenceSource"]) == (
        "vision.activity", "z1", "zone", "camera")
