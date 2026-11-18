import time

import pytest
from fakes import FakeClock, wait_until

from lp_edge.api import ApiError
from lp_edge.commands import REPORTED, CommandDispatcher, CommandError
from lp_edge.live import MAX_SESSIONS, LiveManager, LiveSession
from lp_edge.store import Store
from lp_edge.timeutil import iso


@pytest.fixture
def store(tmp_path):
    db = Store(tmp_path / "agent.db")
    yield db
    db.close()


def state_of(store, command_id):
    return store.scalar("SELECT state FROM commands WHERE id = ?", (command_id,))


def test_a_command_runs_once_and_reports(store):
    results = []
    dispatcher = CommandDispatcher(store, lambda *result: results.append(result), clock=FakeClock())
    ran = []
    dispatcher.register("config_changed", lambda command: ran.append(command["id"]))
    command = {"id": "c1", "kind": "config_changed", "payload": {"configVersion": 2}}
    assert dispatcher.submit([command, command]) == 1
    wait_until(lambda: results)
    assert dispatcher.submit([command]) == 0  # delivered again (heartbeat answer, or no result within 2 minutes)
    assert ran == ["c1"]
    assert results == [("c1", "done", None, None)]
    dispatcher.shutdown()


def test_failures_expiry_and_unknown_kinds_are_reported(store):
    clock = FakeClock()
    results = []
    dispatcher = CommandDispatcher(store, lambda *result: results.append(result), clock=clock)

    def gone(command):
        raise CommandError("Clip x is no longer on the agent", code="not_found")

    dispatcher.register("clip_upload", gone)
    dispatcher.register("snapshot", lambda command: REPORTED)
    dispatcher.register("calibrate", lambda command: 1 / 0)
    dispatcher.submit(
        [
            {"id": "old", "kind": "snapshot", "expiresAt": iso(clock.now - 1)},
            {"id": "what", "kind": "dance"},
            {"id": "gone", "kind": "clip_upload"},
            {"id": "snap", "kind": "snapshot", "expiresAt": iso(clock.now + 60)},
            {"id": "boom", "kind": "calibrate"},
        ]
    )
    wait_until(lambda: len(results) == 4 and state_of(store, "snap") == "done")
    by_id = {result[0]: result[1:] for result in results}
    assert by_id["old"] == ("failed", "the command expired before the agent ran it", None)
    assert by_id["what"][0] == "failed"
    assert by_id["gone"] == ("failed", "Clip x is no longer on the agent", "not_found")
    assert by_id["boom"] == ("failed", "the agent failed: ZeroDivisionError", None)
    assert "snap" not in by_id  # the snapshot upload itself completed the command
    dispatcher.shutdown()


def test_a_result_that_could_not_be_sent_is_sent_later(store):
    attempts = []

    def report(*result):
        attempts.append(result)
        if len(attempts) == 1:
            raise ApiError(503, "down")

    dispatcher = CommandDispatcher(store, report, clock=FakeClock())
    dispatcher.register("config_changed", lambda command: None)
    dispatcher.submit([{"id": "c1", "kind": "config_changed"}])
    wait_until(lambda: attempts and state_of(store, "c1") == "done")
    assert dispatcher.report_pending() == 1
    assert dispatcher.report_pending() == 0
    assert len(attempts) == 2
    dispatcher.shutdown()


def test_a_restart_forgets_commands_that_were_running(store):
    store.execute("INSERT INTO commands (id, kind, state, received_at) VALUES ('r1', 'snapshot', 'running', 0)")
    CommandDispatcher(store, lambda *result: None).shutdown()
    assert state_of(store, "r1") is None


def test_live_view_stops_when_the_server_says_so():
    counter = iter(range(1000))
    pushed = []

    def push(session_id, jpeg):
        pushed.append(jpeg)
        return len(pushed) < 3

    clock = FakeClock()
    session = LiveSession(
        "s1",
        frames=lambda: (next(counter), "frame"),
        encode=lambda frame: b"jpeg",
        push=push,
        fps=100,  # capped at 4 a second
        expires_at=clock.now + 60,
        clock=clock,
    )
    started = time.monotonic()
    session.run()
    assert len(pushed) == 3
    assert session.ended_because == "stopped by the server"
    assert time.monotonic() - started >= 0.45  # two waits of at least 0.25 s


def test_live_view_ends_at_expiry_and_when_refused():
    clock = FakeClock()
    expired = LiveSession(
        "s2", frames=lambda: (1.0, "f"), encode=lambda f: b"j", push=lambda s, j: True, fps=4,
        expires_at=clock.now - 1, clock=clock,
    )
    expired.run()
    assert expired.ended_because == "expired"

    def refuse(session_id, jpeg):
        raise ApiError(404, "Live session not found")

    refused = LiveSession(
        "s3", frames=lambda: (1.0, "f"), encode=lambda f: b"j", push=refuse, fps=4, expires_at=clock.now + 60,
        clock=clock,
    )
    refused.run()
    assert refused.ended_because.startswith("refused")


def test_at_most_four_live_views():
    manager = LiveManager()

    def session(i):
        return LiveSession(
            f"s{i}", frames=lambda: None, encode=lambda f: b"j", push=lambda s, j: True, fps=1,
            expires_at=time.time() + 60,
        )

    for i in range(MAX_SESSIONS):
        manager.start(session(i))
    with pytest.raises(RuntimeError):
        manager.start(session(99))
    assert manager.stop("s0")
    assert not manager.stop("nope")
    manager.stop_all()
    wait_until(lambda: manager.active() == [])
