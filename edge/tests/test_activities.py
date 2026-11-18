"""Activity recognition research (LP7): the baseline recognizers, how a zone picks its recognizers, and the shadow-mode
wrapper that runs them beside the zone's own profile without changing what the profile reports."""

from fakes import SQUARE, T0, box_at, track, zone

from lp_edge.activities import BASELINE, BaseRecognizer, Segment, loader
from lp_edge.activities.baseline import ObjectCarriedOut, ObjectLeftBehind
from lp_edge.activities.research import ActivityResearchProfile
from lp_edge.camera import CameraObserver, Unobserved
from lp_edge.events import ACTIVITY, LINE_CROSSED, envelope
from lp_edge.profiles import ProfileEnv, UnavailableProfile, build_profile, unwrap
from lp_edge.profiles.base import FrameContext
from lp_edge.profiles.cash_drawer import CashDrawerProfile
from lp_edge.profiles.counting import CountingProfile
from lp_edge.remote_config import Camera
from lp_edge.timeutil import iso

STORE = frozenset({"person", "bag"})


def bag(track_id, x, y=0.5, score=0.8, cls="bag"):
    return track(track_id, box_at(x, y, 0.06, 0.06), cls=cls, score=score)


def observe(recognizer, z, steps, t0=T0):
    segments = []
    for i, tracks in enumerate(steps):
        segments += recognizer.observe(FrameContext(ts=t0 + i, tracks=tracks), tracks, z)
    return segments


def test_an_object_that_stood_in_the_zone_and_left_it_was_carried_out():
    z = zone("storage", SQUARE)
    recognizer = ObjectCarriedOut.from_zone(z)
    # stands at x 0.5 from T0 to T0+2, is picked up at T0+3, leaves the zone at T0+5
    steps = [[bag(1, x)] for x in (0.5, 0.5, 0.5, 0.6, 0.7, 0.9)]
    assert observe(recognizer, z, steps) == [Segment("object_carried_out", T0 + 2, T0 + 5, 0.8, (1,))]
    # carried through without stopping, or a person: nothing
    assert observe(recognizer, z, [[bag(2, x)] for x in (0.3, 0.5, 0.7, 0.9)], T0 + 10) == []
    assert observe(recognizer, z, [[bag(3, x, cls="person")] for x in (0.5, 0.5, 0.5, 0.9)], T0 + 20) == []
    assert recognizer.flush(T0 + 30) == []


def test_an_object_still_in_the_zone_long_enough_was_left_behind():
    z = zone("storage", SQUARE, leftBehindSeconds=3)
    recognizer = ObjectLeftBehind.from_zone(z)
    assert observe(recognizer, z, [[bag(2, 0.5 + 0.001 * i)] for i in range(5)]) == []
    assert recognizer.pending_since() == T0 + 4  # it may still be reported as ending at T0+4
    moved = recognizer.observe(FrameContext(ts=T0 + 5), [bag(2, 0.6)], z)
    assert moved == [Segment("object_left_behind", T0, T0 + 4, 0.8, (2,))]
    assert recognizer.pending_since() is None
    # a shorter stay is nothing; one still there when observation stops is reported then
    assert observe(recognizer, z, [[bag(4, 0.3)] for _ in range(2)], T0 + 10) == []
    assert observe(recognizer, z, [[bag(5, 0.7)] for _ in range(4)], T0 + 20) == []
    assert recognizer.flush(T0 + 23) == [Segment("object_left_behind", T0 + 20, T0 + 23, 0.8, (5,))]


def test_a_zone_runs_the_baseline_recognizers_unless_it_names_others(monkeypatch):
    assert [r.name for r in loader.recognizers_for(zone("storage", SQUARE))] == list(BASELINE)

    class Installed(BaseRecognizer):
        name, version, labels = "item_swap", "0.1", ("item_swap",)

        def __init__(self, z):
            self.zone = z

        def observe(self, frame, tracks, z):
            return []

    class Nameless:
        def observe(self, frame, tracks, z):
            return []

    class Entry:
        def __init__(self, name, target):
            self.name, self._target = name, target

        def load(self):
            return self._target

    def broken(z):
        raise RuntimeError("no model file")

    entries = [Entry("item_swap", Installed), Entry("broken", broken), Entry("nameless", lambda z: Nameless()),
               Entry("object_left_behind", Installed)]
    monkeypatch.setattr(loader, "entry_points", lambda group: entries if group == loader.ENTRY_POINT_GROUP else [])
    z = zone("storage", SQUARE, activityRecognizers=["item_swap", "nowhere", "broken", "nameless",
                                                     "object_left_behind", "item_swap"])
    picked = loader.recognizers_for(z)
    # unknown, failing and incomplete recognizers are skipped; a built-in name is not replaced by an entry point
    assert [(type(r).__name__, r.version) for r in picked] == [("Installed", "0.1"), ("ObjectLeftBehind", "1")]
    assert picked[0].zone is z


def run(profile, steps):
    profile.start(T0)
    out = []
    for i, tracks in enumerate(steps):
        out += profile.process(FrameContext(ts=T0 + i, tracks=tracks))
    return out + profile.stop(T0 + len(steps) - 1)


def test_research_runs_beside_the_zone_profile_and_changes_nothing_it_reports():
    steps = [[bag(1, x)] for x in (0.5, 0.5, 0.5, 0.7, 0.9)] + [[]]
    plain = build_profile(zone("storage", SQUARE), "cam-1", ProfileEnv(), STORE)
    research = build_profile(zone("storage", SQUARE, activityResearch=True), "cam-1", ProfileEnv(), STORE)
    assert type(plain) is CountingProfile
    assert isinstance(research, ActivityResearchProfile) and type(unwrap(research)) is CountingProfile
    assert research.wanted_classes() == plain.wanted_classes()
    before = run(plain, steps)
    after = run(research, steps)
    assert [o.type for o in before] == [LINE_CROSSED]
    assert [(o.type, o.payload, o.occurred_at) for o in after if o.type != ACTIVITY] == [
        (o.type, o.payload, o.occurred_at) for o in before]
    activities = [o for o in after if o.type == ACTIVITY]
    assert [o.occurred_at for o in activities] == [T0 + 4]
    event = envelope(activities[0], agent_id="a1", branch_id="b1")
    assert event["payload"] == {"activity": "object_carried_out", "startedAt": iso(T0 + 2), "endedAt": iso(T0 + 4),
                                "recognizer": "object_carried_out@1", "score": 0.8, "trackIds": [1],
                                "cameraId": "cam-1"}
    assert (event["deviceId"], event["evidenceLevel"], event["evidenceSource"], event["confidence"]) == (
        "z1", "zone", "camera", 0.8)


def test_a_failing_recognizer_costs_nothing_and_a_pending_segment_holds_the_watermark():
    class Failing(BaseRecognizer):
        name, labels = "always_fails", ("always_fails",)

        def observe(self, frame, tracks, z):
            raise RuntimeError("bad model")

        def flush(self, ts):
            raise RuntimeError("bad model")

    class Odd(BaseRecognizer):
        name, labels = "odd", ("odd",)

        def observe(self, frame, tracks, z):
            return [Segment("Not A Name", frame.ts, frame.ts), Segment("odd", frame.ts - 1, frame.ts, score=2.0)]

        def pending_since(self):
            return T0 - 5

    inner = build_profile(zone("storage", SQUARE), "cam-1", ProfileEnv(), STORE)
    profile = ActivityResearchProfile(inner, [Failing(), Odd()])
    profile.start(T0)
    out = profile.process(FrameContext(ts=T0, tracks=[bag(1, 0.5)]))
    # the invalid name is dropped; a score outside 0–1 is left out
    assert [(o.payload["activity"], "score" in o.payload, o.confidence) for o in out] == [("odd", False, None)]
    assert profile.hold() == T0 - 5
    assert profile.stop(T0) == []
    assert profile.hold() is None


def test_research_wraps_only_observable_zones_and_a_wrapped_drawer_can_still_be_calibrated():
    env = ProfileEnv()
    gap = build_profile(zone("queue", [[0.5, 0.0], [0.5, 1.0]], activityResearch=True), "cam-1", env, STORE)
    assert isinstance(gap, UnavailableProfile)
    nothing = build_profile(zone("storage", SQUARE, activityResearch=True, activityRecognizers=["nowhere"]), "cam-1",
                            env, STORE)
    assert type(nothing) is CountingProfile  # no recognizer can run: the zone's profile alone
    camera = Camera("cam-1", "Till 1", "till-1",
                    zones=(zone("cash_drawer", SQUARE, "d1", activityResearch=True),))
    observer = CameraObserver(camera, env=env, emit=lambda camera_id, observations: None, detector_classes=None,
                              unobserved=Unobserved(None, T0), build=build_profile, start_grace=5.0)
    observer.frame(FrameContext(ts=T0 + 1))
    drawer = observer._profiles["d1"]
    assert isinstance(drawer, ActivityResearchProfile) and isinstance(unwrap(drawer), CashDrawerProfile)
    assert drawer.needs_frame() and drawer.needs_frame() == unwrap(drawer).needs_frame()
    future = observer.request_calibration("d1")
    assert not future.done()  # queued for the next picture, not refused
