import time

import pytest
from fakes import SQUARE, T0, wait_until, zone

from lp_edge.camera import CameraObserver, CameraWorker, Unobserved
from lp_edge.events import GAP, OCCUPANCY
from lp_edge.profiles import ProfileEnv
from lp_edge.profiles.base import FrameContext, ZoneProfile
from lp_edge.remote_config import Camera
from lp_edge.timeutil import iso


class Holding(ZoneProfile):
    """A profile that observes nothing, holds the watermark where told, and reports one count when stopped."""

    held = None

    def process(self, frame):
        return []

    def _hold(self):
        return self.held

    def _flush(self, ts):
        return [self.observation(OCCUPANCY, ts, {"count": 0, "class": "person"}, 0.8)]


def make_camera(*zone_ids, **zone_params):
    return Camera("cam-1", "Till 1", "till-1", zones=tuple(zone("checkout", SQUARE, z, **zone_params) for z in zone_ids))


def make_observer(emitted, zone_ids=("z1", "z2"), unobserved=None, grace=5.0):
    built = {}

    def build(z, camera_id, env, classes):
        built[z.id] = Holding(z, camera_id, env, classes)
        return built[z.id]

    observer = CameraObserver(
        make_camera(*zone_ids),
        env=ProfileEnv(),
        emit=lambda camera_id, observations: emitted.extend(observations),
        detector_classes=None,
        unobserved=unobserved or Unobserved(None, T0),
        build=build,
        start_grace=grace,
    )
    return observer, built


def gaps(observations):
    return [(o.zone_id, o.payload["reason"], o.payload["from"], o.payload["to"]) for o in observations if o.type == GAP]


def test_the_watermark_stops_while_the_stream_is_lost_and_a_gap_closes_the_hole():
    emitted = []
    observer, _ = make_observer(emitted)
    assert observer.watermark() == T0
    observer.frame(FrameContext(ts=T0 + 1))
    assert gaps(emitted) == []  # the first picture came within the grace period
    observer.frame(FrameContext(ts=T0 + 2))
    assert observer.watermark() == T0 + 2
    observer.interrupt("stream_lost")
    observer.interrupt("agent_down")  # already interrupted: the first reason and time stay
    assert observer.watermark() == T0 + 2
    assert not observer.observing
    emitted.clear()
    observer.frame(FrameContext(ts=T0 + 40))
    assert gaps(emitted) == [
        ("z1", "stream_lost", iso(T0 + 2), iso(T0 + 40)),
        ("z2", "stream_lost", iso(T0 + 2), iso(T0 + 40)),
    ]
    assert observer.watermark() == T0 + 40


def test_profile_holds_keep_the_watermark_back():
    observer, built = make_observer([])
    observer.frame(FrameContext(ts=T0 + 1))
    built["z1"].held = T0 + 0.5
    observer.frame(FrameContext(ts=T0 + 2))
    assert observer.watermark() == T0 + 0.5


def test_a_camera_slow_to_deliver_its_first_picture_reports_the_wait():
    emitted = []
    observer, _ = make_observer(emitted, zone_ids=("z1",))
    observer.frame(FrameContext(ts=T0 + 30))
    assert gaps(emitted) == [("z1", "stream_lost", iso(T0), iso(T0 + 30))]


def test_after_a_restart_the_downtime_is_a_gap():
    emitted = []
    observer, _ = make_observer(emitted, zone_ids=("z1",), unobserved=Unobserved("agent_down", T0 - 100))
    assert observer.watermark() == T0 - 100
    observer.frame(FrameContext(ts=T0))
    assert gaps(emitted) == [("z1", "agent_down", iso(T0 - 100), iso(T0))]


def test_interrupt_reports_what_profiles_hold_unless_their_times_are_untrusted():
    emitted = []
    observer, _ = make_observer(emitted, zone_ids=("z1",))
    observer.frame(FrameContext(ts=T0 + 1))
    observer.interrupt("stream_lost")
    assert [(o.type, o.occurred_at) for o in emitted] == [(OCCUPANCY, T0 + 1)]
    observer.frame(FrameContext(ts=T0 + 2))
    emitted.clear()
    observer.interrupt("clock_drift", since=T0 + 1.5, report=False)
    assert emitted == []
    assert observer.watermark() == T0 + 1.5


def test_configure_rebuilds_only_changed_zones():
    emitted = []
    observer, built = make_observer(emitted)
    observer.frame(FrameContext(ts=T0 + 1))
    first = dict(built)
    camera = Camera(
        "cam-1",
        "Till 1",
        "till-1",
        zones=(zone("checkout", SQUARE, "z1"), zone("checkout", SQUARE, "z2", minPresenceSeconds=9)),
    )
    observer.configure(camera, None)
    assert built["z1"] is first["z1"]
    assert built["z2"] is not first["z2"]
    assert [o.zone_id for o in emitted] == ["z2"]  # what the replaced zone still held


def test_only_a_drawer_zone_can_be_calibrated():
    observer, _ = make_observer([], zone_ids=("z1",))
    future = observer.request_calibration("z1")
    with pytest.raises(ValueError):
        future.result(timeout=1)


class FakeFrame:
    shape = (90, 160, 3)


class FakeSource:
    def __init__(self, frames):
        self.frames = frames

    def open(self):
        return True

    def read(self):
        time.sleep(0.01)
        if self.frames > 0:
            self.frames -= 1
            return True, FakeFrame()
        return False, None

    def close(self):
        pass


def test_the_worker_buffers_analyses_and_notices_a_lost_stream():
    emitted, buffered = [], []
    observer, _ = make_observer(emitted, zone_ids=("z1",))
    source = FakeSource(frames=40)
    worker = CameraWorker(
        observer.camera,
        open_source=lambda: source,
        observer=observer,
        buffer=lambda camera_id, ts, frame: buffered.append(ts),
        detector=lambda: None,
        analysis_allowed=lambda: True,
        analysis_fps=50,
        buffer_fps=50,
        analysis_max_width=960,
        stall_seconds=1.0,
    )
    worker.start()
    try:
        wait_until(lambda: len(buffered) >= 5)
        assert worker.status()["streamState"] == "ok"
        assert worker.status()["cameraId"] == "cam-1"
        assert worker.latest_frame() is not None
        wait_until(lambda: observer.last_frame is not None)
        wait_until(lambda: not observer.observing)  # the frames ran out: no picture for a second
        assert observer.unobserved.reason == "stream_lost"
        assert observer.watermark() == observer.last_frame
    finally:
        worker.stop(timeout=5)


def test_a_missing_stream_address_does_not_crash_the_worker():
    observer, _ = make_observer([], zone_ids=("z1",))
    worker = CameraWorker(
        observer.camera,
        open_source=lambda: None,
        observer=observer,
        buffer=None,
        detector=lambda: None,
        analysis_allowed=lambda: True,
        analysis_fps=5,
        buffer_fps=2,
        analysis_max_width=960,
        stall_seconds=1.0,
    )
    worker.start()
    try:
        time.sleep(0.2)
        assert worker.status()["streamState"] == "connecting"
        assert worker.latest_frame() is None
    finally:
        worker.stop(timeout=5)


class CountingSource(FakeSource):
    opened = 0

    def open(self):
        CountingSource.opened += 1
        return True

    def read(self):
        time.sleep(0.01)
        return True, FakeFrame()  # a stream that never ends


def test_the_worker_reconnects_when_asked():
    observer, _ = make_observer([], zone_ids=("z1",))
    CountingSource.opened = 0
    worker = CameraWorker(
        observer.camera,
        open_source=lambda: CountingSource(0),
        observer=observer,
        buffer=None,
        detector=lambda: None,
        analysis_allowed=lambda: True,
        analysis_fps=5,
        buffer_fps=2,
        analysis_max_width=960,
        stall_seconds=5.0,
    )
    worker.start()
    try:
        wait_until(lambda: CountingSource.opened == 1 and worker.latest_frame() is not None)
        worker.reconnect()
        wait_until(lambda: CountingSource.opened == 2)
    finally:
        worker.stop(timeout=5)


def test_a_zone_drawn_before_the_agent_loaded_it_reports_the_wait_as_a_gap():
    import dataclasses

    emitted = []
    observer, _ = make_observer(emitted, zone_ids=("z1",))
    observer.frame(FrameContext(ts=T0 + 1))
    observer.frame(FrameContext(ts=T0 + 10))
    new = dataclasses.replace(zone("checkout", SQUARE, "z2"), created_at=T0 + 4)
    old = observer.camera.zones[0]
    observer.configure(Camera("cam-1", "Till 1", "till-1", zones=(old, new)), None)
    # Until the next frame reports the gap, the watermark stays where the new zone started to be missed.
    assert observer.watermark() == T0 + 4
    emitted.clear()
    observer.frame(FrameContext(ts=T0 + 11))
    assert gaps(emitted) == [("z2", "config_pending", iso(T0 + 4), iso(T0 + 11))]
    assert observer.watermark() == T0 + 11
    # Loading the same configuration again reports nothing.
    observer.configure(Camera("cam-1", "Till 1", "till-1", zones=(old, new)), None)
    emitted.clear()
    observer.frame(FrameContext(ts=T0 + 12))
    assert gaps(emitted) == []


def test_a_new_zone_is_missed_from_the_previous_configuration_at_most():
    import dataclasses

    emitted = []
    observer, _ = make_observer(emitted, zone_ids=("z1",))
    observer.frame(FrameContext(ts=T0 + 1))
    observer.configure(observer.camera, None)  # a configuration applied at T0 + 1
    observer.frame(FrameContext(ts=T0 + 20))
    new = dataclasses.replace(zone("checkout", SQUARE, "z2"), created_at=T0 - 500)
    observer.configure(Camera("cam-1", "Till 1", "till-1", zones=(observer.camera.zones[0], new)), None)
    emitted.clear()
    observer.frame(FrameContext(ts=T0 + 21))
    assert gaps(emitted) == [("z2", "config_pending", iso(T0 + 1), iso(T0 + 21))]


def test_a_zone_sent_without_created_at_reports_no_gap():
    emitted = []
    observer, _ = make_observer(emitted, zone_ids=("z1",))
    observer.frame(FrameContext(ts=T0 + 1))
    observer.configure(make_camera("z1", "z2"), None)
    emitted.clear()
    observer.frame(FrameContext(ts=T0 + 2))
    assert gaps(emitted) == []
