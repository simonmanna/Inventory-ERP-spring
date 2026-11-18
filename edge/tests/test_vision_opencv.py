"""Tests that need NumPy and OpenCV (installed with the package; skipped where they are not)."""

import math
from types import SimpleNamespace

import pytest

np = pytest.importorskip("numpy")
cv2 = pytest.importorskip("cv2")

from fakes import SQUARE, T0, zone  # noqa: E402

from lp_edge.detectors.motion import MotionModel  # noqa: E402
from lp_edge.detectors.yolox import YoloxDetector, decode, postprocess, preprocess  # noqa: E402
from lp_edge.events import GAP, STATE_CHANGED  # noqa: E402
from lp_edge.profiles import ProfileEnv  # noqa: E402
from lp_edge.profiles.base import FrameContext  # noqa: E402
from lp_edge.profiles.cash_drawer import CashDrawerProfile  # noqa: E402
from lp_edge.video import (  # noqa: E402
    CLIP_FORMATS,
    ClipTooLarge,
    NoFrames,
    decode_jpeg,
    encode_clip,
    encode_jpeg,
    jpeg_within,
    polygon_mask,
    resize_to_width,
)


def checkerboard(height=90, width=160, square=8, inverted=False):
    ys, xs = np.mgrid[0:height, 0:width]
    board = ((ys // square + xs // square) % 2).astype(bool)
    if inverted:
        board = ~board
    grey = np.where(board, 230, 20).astype(np.uint8)
    return np.dstack([grey, grey, grey])


def test_yolox_preprocess_letterboxes():
    image = np.zeros((100, 200, 3), dtype=np.uint8)
    blob, ratio = preprocess(image, (64, 64))
    assert blob.shape == (1, 3, 64, 64) and blob.dtype == np.float32
    assert ratio == pytest.approx(0.32)
    assert blob[0, 0, 0, 0] == 0  # the picture
    assert blob[0, 0, 40, 10] == 114  # the padding below it


def test_yolox_decode_uses_the_grid_and_strides():
    outputs = np.zeros((84, 6), dtype=np.float32)  # 64 x 64 input: 8x8 + 4x4 + 2x2 cells
    decoded = decode(outputs, (64, 64))
    assert decoded[0, :4].tolist() == [0, 0, 8, 8]
    assert decoded[9, :4].tolist() == [8, 8, 8, 8]  # stride 8, cell (1, 1)
    assert decoded[64, :4].tolist() == [0, 0, 16, 16]  # first cell of stride 16
    with pytest.raises(ValueError):
        decode(np.zeros((10, 6), dtype=np.float32), (64, 64))


def test_yolox_postprocess_scores_suppresses_and_normalises():
    predictions = np.array(
        [
            [32, 32, 16, 16, 0.9, 0.8, 0.1],  # person, 0.72
            [33, 33, 16, 16, 0.8, 0.8, 0.1],  # the same person, weaker: suppressed
            [10, 10, 8, 8, 0.9, 0.1, 0.9],  # cup, 0.81
            [50, 50, 8, 8, 0.05, 0.9, 0.1],  # below the score threshold
        ],
        dtype=np.float32,
    )
    detections = postprocess(predictions, 0.5, (128, 128), ("person", "cup"), 0.1, 0.45)
    assert [(d.cls, round(d.score, 2)) for d in detections] == [("person", 0.72), ("cup", 0.81)]
    assert detections[0].box == pytest.approx((0.375, 0.375, 0.625, 0.625))
    assert detections[1].box == pytest.approx((0.09375, 0.09375, 0.21875, 0.21875))


def test_yolox_detector_with_a_raw_head():
    head = np.zeros((1, 84, 6), dtype=np.float32)
    head[0, 9] = [0.0, 0.0, math.log(2), math.log(2), 0.9, 0.9]  # cell (1, 1) of stride 8: a 16 px box at (8, 8)

    class Session:
        def get_inputs(self):
            return [SimpleNamespace(name="images")]

        def run(self, names, feeds):
            assert feeds["images"].shape == (1, 3, 64, 64)
            return [head]

    detector = YoloxDetector("unused.onnx", version="t", classes=("person",), input_size=(64, 64), session=Session())
    [detection] = detector.detect(np.zeros((64, 64, 3), dtype=np.uint8))
    assert detection.cls == "person" and detection.score == pytest.approx(0.81)
    assert detection.box == pytest.approx((0.0, 0.0, 0.25, 0.25), abs=1e-6)  # float32 exp


def test_jpeg_helpers():
    frame = checkerboard()
    assert decode_jpeg(encode_jpeg(frame)).shape == frame.shape
    with pytest.raises(ValueError):
        decode_jpeg(b"not a jpeg")
    assert resize_to_width(frame, 80).shape == (45, 80, 3)
    assert resize_to_width(frame, 640) is frame  # never enlarged
    noise = np.random.default_rng(1).integers(0, 255, (480, 640, 3), dtype=np.uint8)
    small = jpeg_within(noise, 40_000)
    assert len(small) <= 40_000
    assert decode_jpeg(small).shape[2] == 3
    mask = polygon_mask(SQUARE, 100, 100)
    assert mask.shape == (100, 100) and 0.2 < (mask > 0).mean() < 0.3


def test_clips_are_encoded_from_buffered_jpegs(tmp_path):
    rng = np.random.default_rng(2)
    frames = [(T0 + i * 0.5, encode_jpeg(rng.integers(0, 255, (120, 160, 3), dtype=np.uint8), 90)) for i in range(10)]
    clip = encode_clip(lambda: iter(frames), tmp_path, fps=2.0)
    assert clip.path.exists() and clip.path.stat().st_size > 0
    assert clip.content_type in {content_type for _, _, content_type in CLIP_FORMATS}
    assert (clip.started_at, clip.ended_at, clip.frames) == (T0, T0 + 4.5, 10)
    with pytest.raises(NoFrames):
        encode_clip(lambda: iter([]), tmp_path, fps=2.0)
    with pytest.raises(ClipTooLarge):  # no container fits in 100 bytes
        encode_clip(lambda: iter(frames), tmp_path, fps=2.0, max_bytes=100)


def test_motion_inside_a_zone():
    model = MotionModel()
    still = np.full((180, 320, 3), 100, dtype=np.uint8)
    for _ in range(40):
        model.update(still)
    assert model.ratio(SQUARE) < 0.05
    moving = still.copy()
    moving[45:135, 80:240] = 255  # something bright fills the zone
    model.update(moving)
    assert model.ratio(SQUARE) > 0.5


def test_a_drawer_opening_is_seen_against_its_resting_picture():
    profile = CashDrawerProfile(
        zone("cash_drawer", SQUARE, "drawer", minOpenSeconds=1, minClosedSeconds=2),
        "cam-1",
        ProfileEnv(new_clip_id=lambda: "clip-1"),
        None,
    )
    closed, opened = checkerboard(), checkerboard(inverted=True)
    profile.start(T0)
    assert profile.hold() == T0  # the resting picture is not known yet
    [learnt] = profile.calibrate(closed, T0 + 0.5)
    assert (learnt.type, learnt.payload["reason"], learnt.payload["detail"]) == (
        GAP, "agent_down", "learning the drawer's resting picture")
    out = []
    timeline = [(1, closed), (2, closed), (3, opened), (3.5, opened), (4, opened), (4.5, opened), (5, closed),
                (6, closed), (7, closed)]
    for seconds, picture in timeline:
        out += profile.process(FrameContext(ts=T0 + seconds, frame=picture))
    changes = [o for o in out if o.type == STATE_CHANGED]
    assert [(o.payload["from"], o.payload["to"], o.occurred_at) for o in changes] == [
        ("closed", "open", T0 + 3),
        ("open", "closed", T0 + 5),
    ]
    assert changes[0].payload["object"] == "cash_drawer" and changes[0].payload["reference"] == "calibrated"
    assert changes[0].clip is not None and changes[0].clip.started_at == T0 + 3 - 20
    assert changes[0].confidence >= 0.6
    assert changes[1].payload["secondsInPreviousState"] == 2.0
