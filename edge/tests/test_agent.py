import copy
import hashlib
import json

import pytest
from fakes import SQUARE, T0, FakeApi, FakeClock, fake_encoder, keypair, signed_release, wait_until

from lp_edge.agent import Agent
from lp_edge.credentials import Credentials
from lp_edge.events import ClipPlan, Observation
from lp_edge.profiles.base import FrameContext
from lp_edge.remote_config import Camera
from lp_edge.settings import Settings
from lp_edge.timeutil import iso

CHECKOUT = {"id": "zone-2", "name": "Checkout", "zoneType": "checkout", "polygon": SQUARE, "linkedType": "till",
            "linkedId": "till-1"}

CONFIG = {
    "agentId": "agent-1",
    "branchId": "branch-1",
    "configVersion": 4,
    "heartbeatSeconds": 60,
    "commandWaitSeconds": 25,
    "clock": {"maxDriftMs": 2000},
    "evidence": {"bufferDays": 7, "maxClipSeconds": 600},
    "model": None,
    "release": None,
    "cameras": [
        {"id": "cam-1", "name": "Till 1", "streamAlias": "till-1", "enabled": True, "analysed": True,
         "zones": [CHECKOUT]},
        {"id": "cam-2", "name": "Store room", "streamAlias": "store", "enabled": True, "analysed": False, "zones": []},
    ],
}


def config(**changes):
    data = copy.deepcopy(CONFIG)
    data.update(changes)
    return data


def make_agent(tmp_path, *, api=None, clock=None, agent_id="agent-1", **env):
    settings = Settings.from_env(
        {
            "LP_EDGE_DATA_DIR": str(tmp_path / "data"),
            "LP_EDGE_TMP_DIR": str(tmp_path / "tmp"),
            "LP_EDGE_RESERVE_BYTES": "0",
            "LP_EDGE_RESERVE_PERCENT": "1",
            **env,
        }
    )
    credentials = Credentials(
        server="https://erp.example.com",
        api_base="/api/v1",
        token_url="/api/v1/oauth/token",
        client_id="lpa_1",
        client_secret="s3cret",
        agent_id=agent_id,
        organization_id="org-1",
        branch_id="branch-1",
    )
    return Agent(settings, credentials, api=api or FakeApi(config()), clock=clock or FakeClock(), start_cameras=False)


@pytest.fixture
def agents():
    made = []
    yield made
    for agent in made:
        agent.commands.shutdown()
        agent.ring.close()
        agent.store.close()


def frames(observer, clock, start, end):
    for i in range(start, end + 1):
        clock.now = T0 + i
        observer.frame(FrameContext(ts=T0 + i))


def outbox_types(agent):
    return [(item.body["type"], item.body["payload"].get("reason")) for item in agent.outbox.batch(500)]


def test_configuration_watermarks_and_heartbeat(tmp_path, agents):
    clock = FakeClock()
    agent = make_agent(tmp_path, clock=clock)
    agents.append(agent)
    api = agent.api
    assert agent.refresh_config()
    assert set(agent.observers) == {"cam-1"}
    assert set(agent.frozen) == {"cam-2"}  # enabled but not analysed: its watermark never moves
    observer = agent.observers["cam-1"]
    frames(observer, clock, 0, 70)  # the occupancy period T0..T0+60 is reported
    agent.heartbeat()
    body = api.heartbeats[-1]
    assert (body["agentVersion"], body["configVersion"], body["queueLength"], body["modelVersion"]) == (
        "0.1.0", 4, 1, None)
    marks = {c["cameraId"]: c["observedThrough"] for c in body["cameras"]}
    assert marks == {"cam-1": iso(T0 + 59.999), "cam-2": iso(T0)}  # before the unsent occupancy at T0+60
    assert body["observedThrough"] == iso(T0 + 59.999)
    assert body["clockDriftMs"] == 0 and body["diskTotalBytes"] > 0
    agent.sender.send_once()
    [[sent]] = api.sent
    assert sent["type"] == "vision.occupancy" and sent["occurredAt"] == iso(T0 + 60)
    assert (sent["deviceId"], sent["branchId"], sent["source"]) == ("zone-2", "branch-1", "edge-agent")
    assert sent["sourceEventId"] == "agent-1:" + sent["eventId"]
    assert sent["payload"] == {"count": 0, "class": "person", "periodSeconds": 60, "method": "motion",
                               "cameraId": "cam-1"}
    agent.heartbeat()
    assert api.heartbeats[-1]["cameras"][0]["observedThrough"] == iso(T0 + 60)  # the period in progress began there


def test_a_lost_stream_holds_the_watermark_until_its_gap_is_sent(tmp_path, agents):
    clock = FakeClock()
    agent = make_agent(tmp_path, clock=clock)
    agents.append(agent)
    agent.refresh_config()
    observer = agent.observers["cam-1"]
    frames(observer, clock, 0, 70)
    agent.sender.send_once()
    observer.interrupt("stream_lost")  # the camera stopped after T0+70
    agent.sender.send_once()  # the period cut at T0+70
    clock.now = T0 + 200
    assert agent.camera_watermark("cam-1") == T0 + 70  # time passes; the watermark does not
    observer.frame(FrameContext(ts=T0 + 200))
    assert outbox_types(agent) == [("vision.gap", "stream_lost")]
    assert agent.camera_watermark("cam-1") == pytest.approx(T0 + 69.999)  # the gap is not sent yet
    agent.sender.send_once()
    assert agent.camera_watermark("cam-1") == T0 + 200
    gap = agent.api.sent[-1][0]
    assert gap["observationStatus"] == "unavailable"
    assert (gap["payload"]["from"], gap["payload"]["to"]) == (iso(T0 + 70), iso(T0 + 200))


def test_clock_drift_withdraws_recent_observations_and_reports_a_gap(tmp_path, agents):
    clock = FakeClock()
    api = FakeApi(config())
    agent = make_agent(tmp_path, api=api, clock=clock)
    agents.append(agent)
    agent.refresh_config()
    observer = agent.observers["cam-1"]
    observer.frame(FrameContext(ts=T0))
    api.heartbeat_answer = {"serverTime": iso(T0), "health": "online", "configVersion": 4}
    agent.heartbeat()  # a good measurement at T0
    assert not agent.drift.drifting
    frames(observer, clock, 1, 61)
    assert agent.outbox.length() == 1
    clock.now = T0 + 65
    api.heartbeat_answer = {"serverTime": iso(T0 + 70), "health": "degraded", "healthReasons": ["clock_drift"]}
    agent.heartbeat()
    assert agent.drift.drifting and agent.drift.drift_ms == 5000
    assert agent.outbox.length() == 0 and agent.outbox.rejected_count() == 1  # made after the last good check
    assert not observer.observing
    assert agent.observed_through() == T0
    agent.emit("cam-1", [Observation("vision.occupancy", "zone-2", "cam-1", T0 + 66, {"count": 1}, 0.9)])
    assert agent.outbox.length() == 0  # nothing is stored while the clock is off
    clock.now = T0 + 120
    api.heartbeat_answer = {"serverTime": iso(T0 + 120)}
    agent.heartbeat()
    assert not agent.drift.drifting
    observer.frame(FrameContext(ts=T0 + 120))
    [gap] = [item.body for item in agent.outbox.batch()]
    assert gap["payload"]["reason"] == "clock_drift"
    assert (gap["payload"]["from"], gap["payload"]["to"]) == (iso(T0), iso(T0 + 120))


def test_commands_from_a_heartbeat_answer_run_once(tmp_path, agents):
    api = FakeApi(config())
    agent = make_agent(tmp_path, api=api)
    agents.append(agent)
    agent.refresh_config()
    command = {"id": "cmd-1", "kind": "clip_upload",
               "payload": {"evidenceId": "ev-1", "cameraId": "cam-1", "clipId": "missing", "from": None, "to": None}}
    api.heartbeat_answer = {"commands": [command]}
    agent.heartbeat()
    agent.heartbeat()
    wait_until(lambda: api.results)
    assert api.results == [("cmd-1", "failed", "Clip missing is no longer on the agent", "not_found")]
    agent.commands.submit([{"id": "cmd-2", "kind": "snapshot", "payload": {"cameraId": "cam-9"}}])
    wait_until(lambda: len(api.results) == 2)
    assert api.results[1][:2] == ("cmd-2", "failed")


def test_clips_are_registered_and_uploaded_on_request(tmp_path, agents):
    clock = FakeClock()
    api = FakeApi(config())
    agent = make_agent(tmp_path, api=api, clock=clock)
    agents.append(agent)
    agent.refresh_config()
    agent.clips._encode = fake_encoder
    for i in range(20):
        agent.ring.append("cam-1", T0 + i, b"frame-%02d" % i)
    opened = Observation("vision.state_changed", "zone-2", "cam-1", T0 + 5, {"object": "cash_drawer", "to": "open"},
                         0.9, clip=ClipPlan("clip-1", T0, T0 + 10))
    agent.emit("cam-1", [opened])
    [event] = [item.body for item in agent.outbox.batch()]
    assert event["payload"]["clipId"] == "clip-1"
    clock.now = T0 + 14
    for row in agent.clips.due(clock.now):
        agent.clips.produce(row)
    agent.register_clips()
    [[registered]] = api.registered
    assert (registered["clipId"], registered["eventIds"], registered["zoneId"]) == ("clip-1", [event["eventId"]],
                                                                                    "zone-2")
    agent.commands.submit([{"id": "up-1", "kind": "clip_upload",
                            "payload": {"evidenceId": "ev-clip-1", "cameraId": "cam-1", "clipId": "clip-1"}}])
    wait_until(lambda: api.results)
    assert api.results == [("up-1", "done", None, None)]
    uploaded = b"".join(chunk for _, _, _, chunk in api.chunks)
    assert hashlib.sha256(uploaded).hexdigest() == registered["sha256"]
    assert max(len(chunk) for _, _, _, chunk in api.chunks) <= 1 << 20
    assert agent.clips.find("clip-1").state == "uploaded"
    agent.commands.submit([{"id": "up-2", "kind": "clip_upload",
                            "payload": {"evidenceId": "ev-range", "cameraId": "cam-1", "clipId": None,
                                        "from": iso(T0 + 2), "to": iso(T0 + 6)}}])
    agent.commands.submit([{"id": "up-3", "kind": "clip_upload",
                            "payload": {"evidenceId": "ev-later", "cameraId": "cam-1", "clipId": None,
                                        "from": iso(T0 + 500), "to": iso(T0 + 510)}}])
    wait_until(lambda: len(api.results) == 3)
    by_id = {r[0]: r[1:] for r in api.results}
    assert by_id["up-2"] == ("done", None, None)
    assert by_id["up-3"] == ("failed", "no video in the buffer for that period", "not_found")


def test_a_still_is_the_buffered_frame_nearest_the_moment(tmp_path, agents):
    clock = FakeClock()
    api = FakeApi(config())
    agent = make_agent(tmp_path, api=api, clock=clock)
    agents.append(agent)
    agent.refresh_config()
    agent.clips._still_encode = lambda jpeg, ts: jpeg + b"@%d" % int(ts - T0)
    for i in range(10):
        agent.ring.append("cam-1", T0 + i, b"frame-%02d" % i)
    clock.now = T0 + 30
    agent.commands.submit([{"id": "st-1", "kind": "clip_upload",
                            "payload": {"evidenceId": "ev-still", "cameraId": "cam-1", "clipId": None,
                                        "kind": "still", "at": iso(T0 + 4.4)}}])
    agent.commands.submit([{"id": "st-2", "kind": "clip_upload",
                            "payload": {"evidenceId": "ev-none", "cameraId": "cam-1", "clipId": None,
                                        "kind": "still", "at": iso(T0 + 20)}}])
    wait_until(lambda: len(api.results) == 2)
    by_id = {r[0]: r[1:] for r in api.results}
    assert by_id["st-1"] == ("done", None, None)
    assert by_id["st-2"] == ("failed", "no video in the buffer for that moment", "not_found")
    [(offset, total, sha, chunk)] = api.chunks
    assert chunk == b"frame-04@4" and (offset, total) == (0, len(chunk))
    assert sha == hashlib.sha256(chunk).hexdigest()
    assert api.content_types == ["image/jpeg"]


def test_a_camera_switched_off_and_on_reports_the_time_off(tmp_path, agents):
    clock = FakeClock()
    api = FakeApi(config())
    agent = make_agent(tmp_path, api=api, clock=clock)
    agents.append(agent)
    agent.refresh_config()
    frames(agent.observers["cam-1"], clock, 0, 1)
    off = config(configVersion=5)
    off["cameras"][0]["enabled"] = False
    api.config = off
    agent.refresh_config()
    assert "cam-1" not in agent.observers
    assert agent.frozen["cam-1"].since == T0 + 1
    clock.now = T0 + 100
    api.config = config(configVersion=6)
    agent.refresh_config()
    agent.observers["cam-1"].frame(FrameContext(ts=T0 + 101))
    gaps = [item.body["payload"] for item in agent.outbox.batch() if item.body["type"] == "vision.gap"]
    assert [(g["reason"], g["from"], g["to"]) for g in gaps] == [("disabled", iso(T0 + 1), iso(T0 + 101))]
    api.config = config(configVersion=7, cameras=[CONFIG["cameras"][0]])
    agent.refresh_config()
    assert "cam-2" not in agent.frozen  # removed from the configuration


def test_after_a_restart_the_downtime_is_reported(tmp_path, agents):
    clock = FakeClock()
    agent = make_agent(tmp_path, clock=clock)
    agents.append(agent)
    agent.refresh_config()
    frames(agent.observers["cam-1"], clock, 0, 5)
    agent.save_markers()
    later = make_agent(tmp_path, clock=FakeClock(T0 + 300))
    agents.append(later)
    later.refresh_config()
    observer = later.observers["cam-1"]
    assert observer.watermark() == T0  # the occupancy period that began at T0 was never reported
    observer.frame(FrameContext(ts=T0 + 300))
    [gap] = [item.body["payload"] for item in later.outbox.batch() if item.body["type"] == "vision.gap"]
    assert (gap["reason"], gap["from"], gap["to"]) == ("agent_down", iso(T0), iso(T0 + 300))


def test_enrolling_again_as_another_agent_sets_the_old_queue_aside(tmp_path, agents):
    agent = make_agent(tmp_path)
    agents.append(agent)
    agent._check_identity()
    agent.refresh_config()
    agent.emit("cam-1", [Observation("vision.occupancy", "zone-2", "cam-1", T0, {"count": 1}, 0.9)])
    other = make_agent(tmp_path, agent_id="agent-2")
    agents.append(other)
    other._check_identity()
    assert other.outbox.length() == 0 and other.outbox.rejected_count() == 1
    assert other.store.get_meta("agent_id") == "agent-2"


def test_only_a_signed_release_is_offered_to_the_updater(tmp_path, agents):
    private, pem, _ = keypair()
    release = signed_release(private)
    agent = make_agent(tmp_path, api=FakeApi(config(release=release)), LP_EDGE_TRUST_KEY=pem)
    agents.append(agent)
    agent.refresh_config()
    assert agent.desired_release() == release
    forged = make_agent(tmp_path / "b", api=FakeApi(config(release=dict(release, version="9.9.9"))),
                        LP_EDGE_TRUST_KEY=pem)
    agents.append(forged)
    forged.refresh_config()
    assert forged.desired_release() is None
    assert "does not verify" in forged.health_status()[1]["release"]["error"]
    untrusted = make_agent(tmp_path / "c", api=FakeApi(config(release=release)))
    agents.append(untrusted)
    untrusted.refresh_config()
    assert untrusted.desired_release() is None


def test_health_status_holds_no_secret(tmp_path, agents):
    agent = make_agent(tmp_path)
    agents.append(agent)
    agent.streams.set("till-1", "rtsp://viewer:pa55word@10.0.0.9:554/stream1")
    agent.refresh_config()
    code, body = agent.health_status()
    text = json.dumps(body)
    assert code == 200 and body["status"] == "ok"
    assert body["cameras"][0]["streamAlias"] == "till-1"
    for secret in ("pa55word", "10.0.0.9", "s3cret", "rtsp"):
        assert secret not in text


def test_retention_deletes_old_video(tmp_path, agents):
    clock = FakeClock()
    agent = make_agent(tmp_path, clock=clock)
    agents.append(agent)
    agent.refresh_config()
    agent.ring.append("cam-1", T0 - 8 * 86_400, b"old")
    agent.ring.append("cam-1", T0, b"new")
    agent.ring.close()
    agent.retention()
    assert [segment.start for segment in agent.ring.segments()] == [T0]


def test_a_changed_stream_address_reconnects_the_camera(tmp_path, agents):
    agent = make_agent(tmp_path)
    agents.append(agent)

    class Worker:
        camera = Camera("cam-1", "Till 1", "till-1")
        reconnects = 0

        def reconnect(self):
            self.reconnects += 1

    worker = Worker()
    agent.workers["cam-1"] = worker
    agent.streams.set("till-1", "rtsp://viewer:one@10.0.0.9/stream1")
    assert agent._capture_source("till-1") is not None
    agent.check_stream_addresses()
    assert worker.reconnects == 0
    agent.streams.set("till-1", "rtsp://viewer:two@10.0.0.9/stream1")
    agent.check_stream_addresses()
    agent.check_stream_addresses()
    assert worker.reconnects == 1  # once, until the stream is opened again
    assert all("viewer" not in value for value in agent._stream_digests.values())


def test_the_configuration_sets_clip_and_chunk_limits(tmp_path, agents):
    clock = FakeClock(T0 + 20)
    evidence = {"bufferDays": 7, "maxClipSeconds": 120, "maxClipBytes": 8 << 20, "chunkBytes": 65536}
    api = FakeApi(config(evidence=evidence))
    agent = make_agent(tmp_path, api=api, clock=clock)
    agents.append(agent)
    agent.refresh_config()
    assert agent.clips.max_bytes == 8 << 20
    assert agent._env.max_clip_seconds == 120
    agent.clips._encode = fake_encoder
    for i in range(5):
        agent.ring.append("cam-1", T0 + i, b"frame-%02d" % i)
    agent.commands.submit([{"id": "up", "kind": "clip_upload",
                            "payload": {"evidenceId": "ev", "cameraId": "cam-1", "clipId": None,
                                        "from": iso(T0), "to": iso(T0 + 4)}}])
    wait_until(lambda: api.results)
    assert api.results == [("up", "done", None, None)]
    assert max(len(chunk) for _, _, _, chunk in api.chunks) == 65536
