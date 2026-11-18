import pytest
from fakes import box_at

from lp_edge import geometry
from lp_edge.detectors.base import Detection
from lp_edge.tracker import ByteTracker


def det(x: float, y: float, score: float = 0.9, cls: str = "person") -> Detection:
    return Detection(cls, score, box_at(x, y))


def test_line_sides_as_seen_on_the_picture():
    a, b = (0.0, 0.5), (1.0, 0.5)  # walking left to right; y points down, so "below" is on the right
    assert geometry.side_of_line(a, b, (0.5, 0.8)) == "right"
    assert geometry.side_of_line(a, b, (0.5, 0.2)) == "left"
    assert geometry.side_of_line(a, b, (0.5, 0.5)) is None
    assert geometry.line_distance(a, b, (0.5, 0.8)) == pytest.approx(0.3)
    assert geometry.projection(a, b, (0.25, 0.9)) == pytest.approx(0.25)


def test_polygons_and_boxes():
    square = [(0.25, 0.25), (0.75, 0.25), (0.75, 0.75), (0.25, 0.75)]
    assert geometry.point_in_polygon((0.5, 0.5), square)
    assert not geometry.point_in_polygon((0.1, 0.5), square)
    assert geometry.boundary_distance((0.5, 0.5), square) == pytest.approx(0.25)
    assert geometry.anchor((0.2, 0.2, 0.4, 0.6), "bottom") == pytest.approx((0.3, 0.6))
    assert geometry.anchor((0.2, 0.2, 0.4, 0.6), "center") == pytest.approx((0.3, 0.4))
    assert geometry.iou((0, 0, 1, 1), (0, 0, 1, 1)) == 1.0
    assert geometry.iou((0, 0, 1, 1), (2, 2, 3, 3)) == 0.0
    assert geometry.iou((0, 0, 2, 1), (1, 0, 3, 1)) == pytest.approx(1 / 3)


def test_tracks_keep_their_identity():
    tracker = ByteTracker()
    assert tracker.update([det(0.30, 0.5)], 0.0) == []  # new: not confirmed yet
    tracks = tracker.update([det(0.31, 0.5)], 0.2)
    assert len(tracks) == 1 and tracks[0].confirmed
    identity = tracks[0].id
    # a blurred frame: the low-score detection keeps the identity (second pass)
    assert [t.id for t in tracker.update([det(0.32, 0.5, score=0.3)], 0.4)] == [identity]
    # missed for less than max_lost_seconds: kept, but not reported as seen
    assert tracker.update([], 0.6) == []
    assert [t.id for t in tracker.update([det(0.33, 0.5)], 1.0)] == [identity]
    tracker.update([], 3.0)  # gone for too long
    assert tracker.tracks == []


def test_unsure_detections_do_not_start_tracks():
    tracker = ByteTracker()
    tracker.update([det(0.3, 0.5, score=0.55)], 0.0)  # above the high threshold, below the new-track threshold
    assert tracker.tracks == []
    tracker.update([det(0.3, 0.5)], 0.0)
    tracker.update([], 0.2)  # an unconfirmed track that misses a frame is dropped
    assert tracker.tracks == []


def test_two_objects_are_two_tracks():
    tracker = ByteTracker()
    for i in range(3):
        tracks = tracker.update([det(0.2 + 0.01 * i, 0.5), det(0.7 - 0.01 * i, 0.5), det(0.45, 0.5, cls="cup")], i * 0.2)
    assert len({t.id for t in tracks}) == 3
    assert sorted(t.cls for t in tracks) == ["cup", "person", "person"]
