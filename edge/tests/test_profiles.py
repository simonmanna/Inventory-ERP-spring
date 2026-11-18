import pytest
from fakes import SQUARE, T0, box_at, track, zone

from lp_edge.events import DWELL, GAP, LINE_CROSSED, OCCUPANCY, ClipPlan
from lp_edge.profiles import ProfileEnv, UnavailableProfile, build_profile
from lp_edge.profiles.base import FrameContext, ZoneProfile
from lp_edge.profiles.cash_drawer import CashDrawerProfile, DrawerStateMachine
from lp_edge.profiles.checkout import CheckoutProfile
from lp_edge.profiles.counting import CountingProfile
from lp_edge.profiles.handling import HandlingProfile
from lp_edge.profiles.occupancy import OccupancyWindow
from lp_edge.timeutil import iso


class FakeMotion:
    def __init__(self) -> None:
        self.value = 0.0

    def ratio(self, polygon):
        return self.value


def people(ts, *points):
    return FrameContext(ts=ts, tracks=[track(i + 1, box_at(x, y)) for i, (x, y) in enumerate(points)])


def checkout(**params):
    env = ProfileEnv(new_clip_id=lambda: "clip-1")
    return CheckoutProfile(zone("checkout", SQUARE, **params), "cam-1", env, frozenset({"person"}))


def test_occupancy_periods_end_on_the_clock():
    window = OccupancyWindow(60)
    window.start(T0 + 15)
    assert window.add(T0 + 20, 2, 0.9) == []
    assert window.add(T0 + 50, 1, 0.8) == []
    [period] = window.add(T0 + 61, 0)
    assert (period.started_at, period.ended_at, period.max_count) == (T0 + 15, T0 + 60, 2)
    assert period.mean_score == pytest.approx(0.9)
    assert window.start_ts == T0 + 60
    closed = window.add(T0 + 125, 0)
    assert [(p.started_at, p.ended_at, p.max_count) for p in closed] == [(T0 + 60, T0 + 120, 0)]
    last = window.flush(T0 + 130)
    assert (last.started_at, last.ended_at, last.seconds) == (T0 + 120, T0 + 130, 10)
    assert window.start_ts is None


def test_checkout_reports_occupancy_and_dwell():
    profile = checkout()
    profile.start(T0)
    out = []
    for i in range(131):
        points = []
        if 10 <= i <= 110:
            points.append((0.5, 0.5))
        if i == 30:
            points.append((0.4, 0.4))
        out += profile.process(people(T0 + i, *points))
    occupancy = [o for o in out if o.type == OCCUPANCY]
    assert [(o.occurred_at, o.payload["count"], o.payload["periodSeconds"]) for o in occupancy] == [
        (T0 + 60, 2, 60),
        (T0 + 120, 1, 60),
    ]
    assert occupancy[0].payload["class"] == "person"
    assert occupancy[0].confidence == pytest.approx(0.9)
    [dwell] = [o for o in out if o.type == DWELL]
    assert dwell.occurred_at == T0 + 110  # occurredAt is endedAt
    assert dwell.payload["startedAt"] == iso(T0 + 10)
    assert dwell.payload["endedAt"] == iso(T0 + 110)
    assert dwell.payload["durationSeconds"] == 100
    assert dwell.payload["maxCount"] == 2
    assert dwell.clip == ClipPlan("clip-1", T0, T0 + 120)  # 90 s or more keeps a clip, with the rolls
    assert dwell.confidence == pytest.approx(0.9)
    assert profile.hold() == T0 + 120  # the occupancy period in progress
    [flushed] = profile.stop(T0 + 130)
    assert (flushed.type, flushed.occurred_at, flushed.payload["count"], flushed.payload["periodSeconds"]) == (
        OCCUPANCY,
        T0 + 130,
        0,
        10,
    )
    assert profile.hold() is None


def test_a_short_presence_is_no_dwell_and_a_missed_frame_is_bridged():
    profile = checkout()
    profile.start(T0)
    out = []
    for i in range(40):
        seen = (5 <= i <= 6) or (10 <= i <= 20 and i != 15)
        out += profile.process(people(T0 + i, (0.5, 0.5)) if seen else people(T0 + i))
        if i == 22:
            assert profile.presence.pending_end() == T0 + 20  # the end of the presence awaits confirmation
            assert profile.hold() == T0  # and the occupancy period that began at T0 is not reported yet
    [dwell] = [o for o in out if o.type == DWELL]
    assert (dwell.payload["startedAt"], dwell.payload["endedAt"]) == (iso(T0 + 10), iso(T0 + 20))
    assert dwell.clip is None


def test_checkout_without_a_person_detector_uses_motion():
    profile = CheckoutProfile(zone("checkout", SQUARE), "cam-1", ProfileEnv(), None)
    assert profile.method == "motion"
    assert profile.needs_motion()
    assert profile.wanted_classes() == frozenset()
    motion = FakeMotion()
    profile.start(T0)
    out = []
    for i in range(61):
        motion.value = 0.05 if 10 <= i < 20 else 0.0
        out += profile.process(FrameContext(ts=T0 + i, motion=motion))
    [occupancy] = [o for o in out if o.type == OCCUPANCY]
    assert occupancy.payload == {"count": 1, "class": "person", "periodSeconds": 60, "method": "motion"}
    assert occupancy.confidence == 0.6
    [dwell] = [o for o in out if o.type == DWELL]
    assert dwell.payload["method"] == "motion" and dwell.confidence == 0.6


def test_line_crossings_follow_the_in_side():
    # a door line drawn left to right; "in" is below it on the picture (the right of A -> B)
    profile = CountingProfile(
        zone("entrance", [[0.1, 0.5], [0.9, 0.5]], inSide="right"),
        "cam-1",
        ProfileEnv(),
        frozenset({"person"}),
        classes=["person"],
    )
    profile.start(T0)
    out = []
    for i, feet in enumerate([0.3, 0.4, 0.45, 0.55, 0.6, 0.7]):
        out += profile.process(FrameContext(ts=T0 + i, tracks=[track(7, (0.45, feet - 0.2, 0.55, feet))]))
    assert [(o.type, o.payload["direction"], o.payload["count"], o.occurred_at) for o in out] == [
        (LINE_CROSSED, "in", 1, T0 + 3)
    ]
    assert out[0].payload["class"] == "person"
    for i, feet in enumerate([0.6, 0.45, 0.3]):
        out += profile.process(FrameContext(ts=T0 + 10 + i, tracks=[track(7, (0.45, feet - 0.2, 0.55, feet))]))
    assert [o.payload["direction"] for o in out] == ["in", "out"]
    # passing beyond the end of the line is not a crossing
    for i, feet in enumerate([0.3, 0.7]):
        out += profile.process(FrameContext(ts=T0 + 20 + i, tracks=[track(8, (0.93, feet - 0.2, 0.99, feet))]))
    assert len(out) == 2


def test_dining_counts_plates_in_and_reports_occupancy_per_class():
    profile = build_profile(zone("dining", SQUARE), "cam-1", ProfileEnv(), frozenset({"person", "plate"}))
    assert isinstance(profile, CountingProfile)
    assert profile.crossing_classes == {"plate"}
    profile.start(T0)
    out = []
    for i, x in enumerate([0.1, 0.15, 0.3, 0.4]):
        tracks = [track(3, box_at(x, 0.5), cls="plate"), track(4, box_at(0.5, 0.5))]
        out += profile.process(FrameContext(ts=T0 + i, tracks=tracks))
    crossings = [o for o in out if o.type == LINE_CROSSED]
    assert [(o.payload["class"], o.payload["direction"]) for o in crossings] == [("plate", "in")]
    assert profile.hold() == T0
    out += profile.process(FrameContext(ts=T0 + 300))
    counts = {o.payload["class"]: (o.payload["count"], o.occurred_at) for o in out if o.type == OCCUPANCY}
    assert counts == {"person": (1, T0 + 300), "plate": (1, T0 + 300)}


def test_bottles_taken_and_returned():
    profile = build_profile(zone("bar_counter", SQUARE), "cam-1", ProfileEnv(), frozenset({"bottle"}))
    assert isinstance(profile, HandlingProfile)
    profile.start(T0)
    out = []
    xs = [0.5, 0.5, 0.9, 0.9, 0.5, 0.5, 0.5, 0.5]
    for i, x in enumerate(xs):
        bottle = track(9, box_at(x, 0.5, w=0.05, h=0.15), cls="bottle")
        out += profile.process(FrameContext(ts=T0 + i, tracks=[bottle], width=16, height=9))
        if i == 5:
            assert profile.hold() == T0 + 4  # a return waits for minReturnSeconds and is dated at its entry
    assert [(o.payload["object"], o.payload["action"], o.occurred_at) for o in out] == [
        ("bottle", "taken", T0 + 2),
        ("bottle", "returned", T0 + 4),
    ]


def test_drawer_state_machine_has_hysteresis():
    machine = DrawerStateMachine(0.2, 1.0, 2.0)
    machine.reset(T0)
    assert machine.update(T0 + 1, 0.3) is None
    assert machine.pending_since() == T0 + 1
    assert machine.update(T0 + 1.5, 0.1) is None  # back below: the change is dropped
    assert machine.pending_since() is None
    machine.update(T0 + 2, 0.3)
    opened = machine.update(T0 + 3, 0.4)
    assert (opened.from_state, opened.to_state, opened.at, opened.seconds_in_previous) == ("closed", "open", T0 + 2, None)
    assert opened.confidence == 0.99
    assert machine.update(T0 + 4, 0.15) is None  # between the two thresholds: still open
    machine.update(T0 + 5, 0.1)
    closed = machine.update(T0 + 7, 0.05)
    assert (closed.to_state, closed.at, closed.seconds_in_previous) == ("closed", T0 + 5, 3.0)
    with pytest.raises(ValueError):
        DrawerStateMachine(0, 1, 2)


def test_zones_this_agent_cannot_watch_report_gaps():
    line_drawer = build_profile(zone("cash_drawer", [[0.1, 0.1], [0.9, 0.9]]), "cam-1", ProfileEnv(), None)
    assert isinstance(line_drawer, UnavailableProfile)
    assert isinstance(build_profile(zone("cash_drawer", SQUARE), "cam-1", ProfileEnv(), None), CashDrawerProfile)
    no_detector = build_profile(zone("entrance", [[0.1, 0.5], [0.9, 0.5]]), "cam-1", ProfileEnv(), None)
    assert isinstance(no_detector, UnavailableProfile)
    no_detector.start(T0)
    assert no_detector.process(FrameContext(ts=T0 + 100)) == []
    assert no_detector.hold() == T0
    [chunk] = no_detector.process(FrameContext(ts=T0 + 300))
    assert (chunk.type, chunk.status, chunk.occurred_at) == (GAP, "unavailable", T0)
    assert chunk.payload["reason"] == "disabled"
    assert (chunk.payload["from"], chunk.payload["to"]) == (iso(T0), iso(T0 + 300))
    assert "no detector" in chunk.payload["detail"]
    [last] = no_detector.stop(T0 + 310)
    assert last.payload["from"] == iso(T0 + 300)
    assert no_detector.hold() is None
    unknown_classes = build_profile(zone("storage", SQUARE), "cam-1", ProfileEnv(), frozenset({"car"}))
    assert isinstance(unknown_classes, UnavailableProfile)


def test_clips_are_cut_to_the_longest_allowed():
    env = ProfileEnv(max_clip_seconds=600, new_clip_id=lambda: "c")
    profile = ZoneProfile(zone("cash_drawer", SQUARE), "cam-1", env, None)
    assert profile.clip_span(T0, T0 + 1000, keep="end") == ClipPlan("c", T0 + 400, T0 + 1000)
    assert profile.clip_span(T0, T0 + 1000, keep="start") == ClipPlan("c", T0, T0 + 600)
    assert profile.clip_around(T0) == ClipPlan("c", T0 - 20, T0 + 30)  # the drawer's pre- and post-roll
