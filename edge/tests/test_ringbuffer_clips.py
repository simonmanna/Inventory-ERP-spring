import hashlib
import os
import stat

import pytest
from fakes import REPEAT, T0, FakeClock, fake_encoder

from lp_edge.clips import CHUNK, ClipStore, ClipUnavailable
from lp_edge.crypto import Cipher
from lp_edge.events import ClipPlan
from lp_edge.ringbuffer import RingBuffer
from lp_edge.store import Store
from lp_edge.timeutil import iso


@pytest.fixture
def ring(tmp_path):
    buffer = RingBuffer(tmp_path / "buffer", Cipher(os.urandom(32)))
    yield buffer
    buffer.close()


@pytest.fixture
def clips(tmp_path):
    cipher = Cipher(os.urandom(32))
    store = Store(tmp_path / "agent.db")
    ring = RingBuffer(tmp_path / "buffer", cipher)
    clock = FakeClock(T0 + 100)
    clip_store = ClipStore(
        store, tmp_path / "clips", cipher, ring, tmp_dir=tmp_path / "tmp", fps=2.0, clock=clock, encode=fake_encoder
    )
    yield clip_store, ring, clock, tmp_path
    ring.close()
    store.close()


def test_frames_come_back_in_order_and_are_encrypted_at_rest(ring):
    for i in range(130):  # 65 s at 2 frames a second: two one-minute segments
        ring.append("cam/1", T0 + i * 0.5, b"JPEG-%d" % i)
    ring.close()
    frames = list(ring.frames("cam/1", T0 + 10, T0 + 61))
    assert [ts for ts, _ in frames] == [T0 + 10 + i * 0.5 for i in range(103)]
    assert frames[0][1] == b"JPEG-20"
    segments = ring.segments()
    assert [s.start for s in segments] == [T0, T0 + 60]
    assert segments[0].camera == "cam_1"
    for segment in segments:
        raw = segment.path.read_bytes()
        assert raw.startswith(b"LPRB1\n")
        assert b"JPEG-" not in raw
        assert stat.S_IMODE(segment.path.stat().st_mode) == 0o600
    assert ring.has_frames("cam/1", T0, T0 + 1)
    assert not ring.has_frames("cam/1", T0 + 100, T0 + 200)
    assert not ring.has_frames("other", T0, T0 + 60)


def test_a_record_cut_by_a_power_failure_is_ignored(ring):
    ring.append("cam", T0, b"one")
    ring.append("cam", T0 + 1, b"two")
    ring.close()
    path = ring.segments()[0].path
    path.write_bytes(path.read_bytes()[:-5])
    assert [jpeg for _, jpeg in ring.frames("cam", T0, T0 + 10)] == [b"one"]


def test_retention_deletes_whole_segments(ring):
    for minute in range(5):
        ring.append("cam", T0 + minute * 60, b"x")
    ring.close()
    assert ring.oldest() == T0
    assert ring.purge_older_than(T0 + 120) == 2
    assert ring.oldest() == T0 + 120
    assert ring.purge_oldest(2) == 2
    assert [s.start for s in ring.segments()] == [T0 + 240]


def test_a_flagged_clip_is_cut_sealed_and_read_back(clips):
    store, ring, clock, root = clips
    for i in range(21):
        ring.append("cam-1", T0 + i, b"frame-%03d" % i)
    store.plan("cam-1", "zone-1", ClipPlan("clip-1", T0 + 5, T0 + 15), "event-1")
    assert store.due(T0 + 17) == []  # it waits a few seconds after its end
    [row] = store.due(T0 + 18)
    stored = store.produce(row)
    expected = b"".join(b"frame-%03d" % i for i in range(5, 16)) * REPEAT
    assert stored.state == "stored" and stored.available
    assert stored.sha256 == hashlib.sha256(expected).hexdigest()  # taken at capture, over the plain clip
    assert stored.byte_size == len(expected)
    assert (stored.started_at, stored.ended_at) == (T0 + 5, T0 + 15)
    sealed = root / "clips" / stored.file_name
    assert b"frame-005" not in sealed.read_bytes()
    assert stat.S_IMODE(sealed.stat().st_mode) == 0o600
    assert store.read(stored, 0, len(expected)) == expected
    offset = CHUNK - 7
    assert store.read(stored, offset, 100) == expected[offset : offset + 100]  # across a chunk boundary
    assert store.read(stored, len(expected) - 3, 100) == expected[-3:]
    assert ClipStore.registration(stored) == {
        "clipId": "clip-1",
        "cameraId": "cam-1",
        "zoneId": "zone-1",
        "startedAt": iso(T0 + 5),
        "endedAt": iso(T0 + 15),
        "sha256": stored.sha256,
        "byteSize": len(expected),
        "contentType": "video/mp4",
        "eventIds": ["event-1"],
    }
    assert store.pending_registration() == [stored]
    store.apply_registration([stored], {"results": [{"clipId": "clip-1", "status": "registered", "evidenceId": "ev-1"}]})
    assert store.find("clip-1").state == "registered"
    assert store.find_by_evidence("ev-1").clip_id == "clip-1"
    assert store.pending_registration() == []
    store.mark_uploaded("clip-1", "ev-1")
    assert store.counts() == {"uploaded": 1}


def test_a_rejected_registration_is_kept_with_its_reason(clips):
    store, ring, clock, _ = clips
    ring.append("cam-1", T0, b"f")
    store.plan("cam-1", "zone-1", ClipPlan("clip-2", T0, T0 + 1), "event-2")
    row = store.produce(store.find("clip-2"))
    store.apply_registration([row], {"results": [{"clipId": "clip-2", "status": "rejected", "reason": "unknown"}]})
    assert (store.find("clip-2").state, store.find("clip-2").reason) == ("rejected", "unknown")


def test_a_range_clip_is_cut_once_on_request(clips):
    store, ring, clock, _ = clips
    for i in range(10):
        ring.append("cam-1", T0 + i, b"f%d" % i)
    row = store.cut_range("ev-9", "cam-1", T0 + 2, T0 + 4)
    assert (row.origin, row.evidence_id, row.available) == ("range", "ev-9", True)
    assert store.cut_range("ev-9", "cam-1", T0 + 2, T0 + 4).clip_id == row.clip_id
    assert store.pending_registration() == []  # a range clip registers its hash with its first upload chunk
    with pytest.raises(ClipUnavailable):
        store.cut_range("ev-10", "cam-1", T0 + 500, T0 + 600)
    assert store.find_by_evidence("ev-10") is None


def test_a_clip_without_video_fails_and_old_clips_are_purged(clips):
    store, ring, clock, root = clips
    store.plan("cam-1", "zone-1", ClipPlan("clip-x", T0 - 100, T0 - 90), "event-x")
    failed = store.produce(store.find("clip-x"))
    assert failed.state == "failed" and not failed.available
    with pytest.raises(ClipUnavailable):
        store.read(failed, 0, 10)
    assert store.purge(clock.now + 1) == 1
    assert store.find("clip-x") is None
