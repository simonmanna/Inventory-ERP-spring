import threading
import time

import pytest
from fakes import T0, FakeClock

from lp_edge.api import ApiError
from lp_edge.events import DWELL, GAP, LINE_CROSSED, OCCUPANCY, ClipPlan, Observation, envelope, gap, validate
from lp_edge.outbox import Outbox, OutboxSender
from lp_edge.store import Store
from lp_edge.timeutil import iso


def occupancy(i: int, camera: str = "cam-1") -> dict:
    observation = Observation(OCCUPANCY, "zone-1", camera, T0 + i, {"count": i, "class": "person"}, 0.9)
    return envelope(observation, agent_id="agent-1", branch_id="branch-1", event_id=f"e-{i}")


@pytest.fixture
def outbox(tmp_path):
    store = Store(tmp_path / "agent.db")
    yield Outbox(store, FakeClock())
    store.close()


def test_envelope_carries_the_ledger_fields():
    observation = Observation(
        OCCUPANCY,
        "zone-1",
        "cam-1",
        T0,
        {"count": 2, "class": "person", "periodSeconds": 60},
        0.81234,
        clip=ClipPlan("clip-1", T0 - 10, T0 + 10),
    )
    event = envelope(observation, agent_id="agent-1", branch_id="branch-1", event_id="e-1")
    assert event == {
        "eventId": "e-1",
        "source": "edge-agent",
        "sourceEventId": "agent-1:e-1",
        "type": "vision.occupancy",
        "schemaVersion": 1,
        "occurredAt": iso(T0),
        "branchId": "branch-1",
        "deviceId": "zone-1",
        "evidenceLevel": "zone",
        "evidenceSource": "camera",
        "observationStatus": "observed",
        "payload": {"count": 2, "class": "person", "periodSeconds": 60, "cameraId": "cam-1", "clipId": "clip-1"},
        "confidence": 0.812,
    }
    assert envelope(observation, agent_id="a", branch_id="b")["eventId"] != envelope(
        observation, agent_id="a", branch_id="b"
    )["eventId"]


def test_a_gap_is_unavailable_and_dated_at_its_start():
    event = envelope(gap("zone-1", "cam-1", "stream_lost", T0, T0 + 30), agent_id="a", branch_id="b")
    assert event["type"] == GAP
    assert event["observationStatus"] == "unavailable"
    assert event["occurredAt"] == iso(T0)
    assert event["payload"] == {"from": iso(T0), "to": iso(T0 + 30), "reason": "stream_lost", "cameraId": "cam-1"}
    assert event["confidence"] == 1.0


def test_invalid_payloads_are_refused():
    cases = [
        (LINE_CROSSED, {"class": "person", "direction": "sideways", "count": 1, "cameraId": "c"}),
        (LINE_CROSSED, {"class": "person", "direction": "in", "count": 0, "cameraId": "c"}),
        (OCCUPANCY, {"count": -1, "cameraId": "c"}),
        (OCCUPANCY, {"count": True, "cameraId": "c"}),
        (OCCUPANCY, {"count": 1}),
        (OCCUPANCY, {"count": 1, "cameraId": "c", "blob": "x" * 70_000}),
        (DWELL, {"class": "person", "startedAt": iso(T0 + 5), "endedAt": iso(T0), "cameraId": "c"}),
        (GAP, {"from": iso(T0), "to": iso(T0 + 1), "reason": "lunch", "cameraId": "c"}),
        ("vision.unknown", {"cameraId": "c"}),
    ]
    for event_type, payload in cases:
        with pytest.raises(ValueError):
            validate(event_type, payload)


def test_the_outbox_settles_each_event(outbox):
    for i in range(4):
        outbox.add(occupancy(i), "cam-1")
    items = outbox.batch(10)
    assert [item.event_id for item in items] == ["e-0", "e-1", "e-2", "e-3"]
    summary = outbox.settle(
        items,
        {
            "results": [
                {"eventId": "e-0", "status": "accepted", "seq": 1},
                {"eventId": "e-1", "status": "duplicate"},
                {"eventId": "e-2", "status": "rejected", "reason": "deviceId is not a zone of this agent"},
            ]
        },
    )
    assert (summary.accepted, summary.duplicates, summary.rejected, summary.kept) == (1, 1, 1, 1)
    assert [item.event_id for item in outbox.batch(10)] == ["e-3"]
    assert outbox.rejected_count() == 1


def test_oldest_unsent_per_camera(outbox):
    outbox.add(occupancy(5, "cam-1"), "cam-1")
    outbox.add(occupancy(2, "cam-2"), "cam-2")
    assert outbox.oldest_unsent("cam-1") == pytest.approx(T0 + 5)
    assert outbox.oldest_unsent() == pytest.approx(T0 + 2)
    assert outbox.oldest_unsent("cam-3") is None
    assert outbox.length() == 2


def test_withdraw_after_moves_later_events_aside(outbox):
    for i in range(3):
        outbox.add(occupancy(i), "cam-1")
    first = outbox.batch(1)[0].seq
    assert outbox.withdraw_after(first, "made while the clock was off") == 2
    assert [item.event_id for item in outbox.batch()] == ["e-0"]
    assert outbox.rejected_count() == 2


def test_the_sender_isolates_a_refused_event(outbox):
    for i in range(5):
        outbox.add(occupancy(i), "cam-1")
    calls = []

    def send(events):
        calls.append([e["eventId"] for e in events])
        if any(e["eventId"] == "e-3" for e in events):
            raise ApiError(400, "payload.count must be 0 or more")
        return {"results": [{"eventId": e["eventId"], "status": "accepted"} for e in events]}

    summary = OutboxSender(outbox, send).send_once()
    assert (summary.accepted, summary.rejected) == (4, 1)
    assert outbox.length() == 0
    assert outbox.rejected_count() == 1
    assert calls[0] == ["e-0", "e-1", "e-2", "e-3", "e-4"]


def test_the_sender_keeps_events_when_the_server_fails(outbox):
    outbox.add(occupancy(0), "cam-1")

    def send(events):
        raise ApiError(503, "down", retry_after=30)

    sender = OutboxSender(outbox, send)
    with pytest.raises(ApiError):
        sender.send_once()
    assert outbox.length() == 1
    assert sender.next_delay(ApiError(503, "down", retry_after=30)) == 30
    assert sender.next_delay(None) == 2
    assert sender.next_delay(None) == 4


def test_the_sender_waits_while_it_may_not_send(outbox):
    outbox.add(occupancy(0), "cam-1")
    sent = []
    allowed = threading.Event()

    def send(events):
        sent.append(events)
        return {"results": [{"eventId": e["eventId"], "status": "accepted"} for e in events]}

    sender = OutboxSender(outbox, send, can_send=allowed.is_set)
    stop, wake = threading.Event(), threading.Event()
    thread = threading.Thread(target=sender.run, args=(stop, wake), daemon=True)
    thread.start()
    try:
        time.sleep(0.3)
        assert sent == []
        allowed.set()
        wake.set()
        deadline = time.monotonic() + 5
        while outbox.length() and time.monotonic() < deadline:
            time.sleep(0.02)
        assert outbox.length() == 0
        assert len(sent) == 1
    finally:
        stop.set()
        wake.set()
        thread.join(5)
