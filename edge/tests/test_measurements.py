import json
import os

import pytest
from fakes import T0, FakeApi, FakeClock
from test_agent import config, make_agent

from lp_edge.events import (
    ENVELOPE_FIELDS,
    MEASUREMENT_GAP,
    OCCUPANCY,
    POUR_MEASURED,
    Observation,
    validate_measurement,
)
from lp_edge.measurements import AdapterError, ConfigError, MeasurementRunner, load_adapters, load_adapters_file
from lp_edge.measurements.base import Adapter, BadRecord, parse_time, to_ml
from lp_edge.measurements.config import build_adapter, parse_spec
from lp_edge.measurements.http_poll import with_param
from lp_edge.outbox import Outbox
from lp_edge.remote_config import AgentConfig, MeasurementDevice
from lp_edge.store import Store
from lp_edge.timeutil import iso

DEVICES = [
    MeasurementDevice(id="dev-1", external_id="SP-1"),
    MeasurementDevice(id="dev-2", external_id="SP-2"),
    MeasurementDevice(id="dev-3", external_id="SP-3", enabled=False),
]
FIELDS = {"externalId": "spout", "volume": "ml", "id": "rid", "startedAt": "start", "endedAt": "end",
          "productId": "sku"}


@pytest.fixture
def store(tmp_path):
    store = Store(tmp_path / "agent.db")
    yield store
    store.close()


def spec(**changes):
    entry = {"name": "bar", "type": "jsonl_dir", "path": "/nonexistent", "fields": dict(FIELDS)}
    entry.update(changes)
    return parse_spec(entry)


def runner_for(store, adapters, devices=DEVICES):
    outbox = Outbox(store, FakeClock())
    runner = MeasurementRunner(adapters, store, outbox, agent_id="agent-1", branch_id=lambda: "branch-1")
    if devices is not None:
        runner.set_devices(devices)
    return runner, outbox


def queued(outbox):
    return [item.body for item in outbox.batch(500)]


def write_file(path, lines, mtime=T0 - 60):
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    os.utime(path, (mtime, mtime))


def pour(rid, spout="SP-1", ml=30, start=T0 - 30, end=T0 - 28, **extra):
    record = {"rid": rid, "spout": spout, "ml": ml, "start": iso(start), "end": iso(end)}
    record.update(extra)
    return json.dumps(record)


class FlakyAdapter(Adapter):
    """Fails while ``failing`` is set; otherwise returns ``readings`` once."""

    def __init__(self, store, **changes):
        super().__init__(spec(**changes), store)
        self.failing = False
        self.readings = []

    def poll(self, now):
        if self.failing:
            raise AdapterError("unreachable")
        readings, self.readings = self.readings, []
        return readings


# -- units, fields, times ------------------------------------------------------------------------------------------------


def test_units_convert_to_millilitres():
    assert to_ml(25, "ml") == 25
    assert to_ml(2.5, "cl") == 25
    assert to_ml(0.7, "l") == pytest.approx(700)
    assert to_ml(1, "oz_us") == 29.5735295625
    assert to_ml(1, "oz_uk") == 28.4130625
    with pytest.raises(ConfigError):
        to_ml(1, "pint")


def test_vendor_times_are_iso_or_epoch_and_naive_times_use_the_zone():
    from zoneinfo import ZoneInfo

    assert parse_time("2027-01-15T08:00:00Z") == T0
    assert parse_time("2027-01-15T09:00:00+01:00") == T0
    assert parse_time(T0) == T0
    assert parse_time(int(T0 * 1000)) == T0
    assert parse_time(str(int(T0 * 1000))) == T0
    assert parse_time("2027-01-15 08:00:00") == T0
    assert parse_time("2027-01-15 09:00:00", ZoneInfo("Europe/Berlin")) == T0
    for bad in ("", "yesterday", True, -5, None):
        with pytest.raises(BadRecord):
            parse_time(bad)


def test_fields_are_mapped_with_dotted_paths_and_units(store):
    adapter = build_adapter(
        parse_spec({"name": "bar", "type": "jsonl_dir", "path": "/x", "unit": "cl", "timezone": "Europe/Berlin",
                    "fields": {"externalId": "device.serial", "volume": "pour.amount", "at": "pour.time"}}),
        store,
    )
    reading = adapter.to_reading({"device": {"serial": " SP-1 "}, "pour": {"amount": "2.5", "time": "2027-01-15 09:00"}})
    assert reading.external_id == "SP-1"
    assert reading.volume_ml == 25.0
    assert reading.ended_at == T0 and reading.started_at is None
    assert reading.vendor_id is None and len(reading.raw_hash) == 16
    bad = [
        {"device": {"serial": "SP-1"}, "pour": {"amount": 0}},
        {"device": {"serial": "SP-1"}, "pour": {"amount": 501}},  # 5010 ml
        {"device": {"serial": "SP-1"}, "pour": {"amount": "lots"}},
        {"device": {}, "pour": {"amount": 1}},
        {"device": {"serial": "SP-1"}, "pour": {"amount": 1, "time": "soon"}},
        ["not", "an", "object"],
    ]
    for record in bad:
        with pytest.raises(BadRecord):
            adapter.to_reading(record)
    flat = build_adapter(spec(), store)
    with pytest.raises(BadRecord):
        flat.to_reading({"spout": "SP-1", "ml": 30, "start": iso(T0), "end": iso(T0 - 1)})


# -- directory adapters ----------------------------------------------------------------------------------------------------


def test_jsonl_files_are_read_once_and_moved_to_done(tmp_path, store):
    inbox = tmp_path / "pours"
    inbox.mkdir()
    write_file(inbox / "a.jsonl", [pour("r1"), "{not json", pour("r2", spout="SP-2", ml=44.44444, sku="p-9"), ""])
    (inbox / "ignored.txt").write_text("x")
    adapter = build_adapter(spec(path=str(inbox)), store)
    runner, outbox = runner_for(store, [adapter])
    assert runner.poll_once(T0) == 2
    events = queued(outbox)
    assert [e["deviceId"] for e in events] == ["dev-1", "dev-2"]
    assert events[1]["payload"] == {"volumeMl": 44.444, "productId": "p-9", "startedAt": iso(T0 - 30),
                                    "endedAt": iso(T0 - 28), "vendorEventId": "r2"}
    assert adapter.bad_records == 1
    assert not (inbox / "a.jsonl").exists()
    assert (inbox / "done" / "a.jsonl").exists()
    assert (inbox / "ignored.txt").exists()
    assert store.meta_with_prefix("meter:bar:file:") == {}
    # A second poll (once due) finds nothing new.
    assert runner.poll_once(T0 + 10) == 0
    # A new file with the same name does not overwrite the one in done/.
    write_file(inbox / "a.jsonl", [pour("r3")])
    assert runner.poll_once(T0 + 20) == 1
    assert sorted(p.name for p in (inbox / "done").iterdir()) == ["a.1.jsonl", "a.jsonl"]
    assert runner.counters()["readingsQueued"] == 3 and runner.counters()["badRecords"] == 1


def test_a_file_still_being_written_waits_for_the_next_poll(tmp_path, store):
    inbox = tmp_path / "pours"
    inbox.mkdir()
    write_file(inbox / "old.jsonl", [pour("r1")], mtime=T0 - 60)
    write_file(inbox / "new.jsonl", [pour("r2")], mtime=T0 - 0.5)
    adapter = build_adapter(spec(path=str(inbox)), store)
    runner, outbox = runner_for(store, [adapter])
    assert runner.poll_once(T0) == 1
    assert (inbox / "new.jsonl").exists()
    assert runner.poll_once(T0 + 5) == 1
    assert not (inbox / "new.jsonl").exists()
    assert [e["payload"]["vendorEventId"] for e in queued(outbox)] == ["r1", "r2"]


def test_csv_files_are_read_with_their_header(tmp_path, store):
    inbox = tmp_path / "csv"
    inbox.mkdir()
    write_file(inbox / "day.csv", [
        "Spout;Volume;Time",
        f"SP-1;1.5;{int(T0)}",
        "SP-2;abc;",
        f"SP-2;2;{int(T0 * 1000)};extra",
        ";;",
        f"SP-2;0.5;{int(T0)}",
    ])
    adapter = build_adapter(
        parse_spec({"name": "csv", "type": "csv_dir", "path": str(inbox), "unit": "oz_uk", "delimiter": ";",
                    "fields": {"externalId": "Spout", "volume": "Volume", "at": "Time"}}),
        store,
    )
    runner, outbox = runner_for(store, [adapter])
    assert runner.poll_once(T0) == 2
    events = queued(outbox)
    assert [e["payload"]["volumeMl"] for e in events] == [42.62, 14.207]
    assert events[0]["occurredAt"] == iso(T0) and events[0]["payload"]["endedAt"] == iso(T0)
    assert adapter.bad_records == 2
    assert (inbox / "done" / "day.csv").exists()


def test_a_crash_between_queueing_and_moving_does_not_read_the_file_again(tmp_path, store):
    inbox = tmp_path / "pours"
    inbox.mkdir()
    write_file(inbox / "a.jsonl", [pour("r1")])
    adapter = build_adapter(spec(path=str(inbox)), store)
    runner, outbox = runner_for(store, [adapter])
    adapter.finish = lambda: None  # the box dies before the file is moved
    assert runner.poll_once(T0) == 1
    assert (inbox / "a.jsonl").exists()
    again = build_adapter(spec(path=str(inbox)), store)
    runner2, _ = runner_for(store, [again])
    assert runner2.poll_once(T0 + 10) == 0
    assert (inbox / "done" / "a.jsonl").exists()
    assert len(queued(outbox)) == 1


def test_a_missing_directory_is_a_failure(tmp_path, store):
    adapter = build_adapter(spec(path=str(tmp_path / "nowhere")), store)
    with pytest.raises(AdapterError):
        adapter.poll(T0)


# -- devices and ids -------------------------------------------------------------------------------------------------------


def test_unknown_and_disabled_devices_are_dropped_and_counted(tmp_path, store):
    inbox = tmp_path / "pours"
    inbox.mkdir()
    write_file(inbox / "a.jsonl", [pour("r1", spout="SP-9"), pour("r2", spout="SP-9"), pour("r3", spout="SP-3"),
                                   pour("r4")])
    adapter = build_adapter(spec(path=str(inbox)), store)
    runner, outbox = runner_for(store, [adapter])
    assert runner.poll_once(T0) == 1
    assert [e["deviceId"] for e in queued(outbox)] == ["dev-1"]
    counters = runner.counters()
    assert counters["unknownDevices"] == 2 and counters["disabledDevices"] == 1


def test_nothing_is_read_before_the_devices_are_known(tmp_path, store):
    inbox = tmp_path / "pours"
    inbox.mkdir()
    write_file(inbox / "a.jsonl", [pour("r1")])
    runner, outbox = runner_for(store, [build_adapter(spec(path=str(inbox)), store)], devices=None)
    assert runner.poll_once(T0) == 0
    assert (inbox / "a.jsonl").exists()
    runner.set_devices(DEVICES)
    assert runner.poll_once(T0 + 1) == 1


def test_the_same_vendor_record_is_one_event(tmp_path, store):
    inbox = tmp_path / "pours"
    inbox.mkdir()
    no_id = json.dumps({"spout": "SP-1", "ml": 30, "end": iso(T0 - 5)})
    write_file(inbox / "a.jsonl", [pour("r1"), no_id], mtime=T0 - 60)
    write_file(inbox / "b.jsonl", [pour("r1"), no_id], mtime=T0 - 50)
    adapter = build_adapter(spec(path=str(inbox)), store)
    runner, outbox = runner_for(store, [adapter])
    assert runner.poll_once(T0) == 2
    events = queued(outbox)
    assert events[0]["sourceEventId"] == "agent-1:meter:bar:r1"
    hashed = events[1]["sourceEventId"]
    assert hashed.startswith("agent-1:meter:bar:") and len(hashed.rsplit(":", 1)[1]) == 16
    assert "vendorEventId" not in events[1]["payload"]
    # Read again after it was sent: the same eventId and sourceEventId, so the ledger answers duplicate.
    outbox.settle(outbox.batch(), {"results": [{"eventId": e["eventId"], "status": "accepted"} for e in events]})
    write_file(inbox / "c.jsonl", [pour("r1")])
    runner.poll_once(T0 + 10)
    assert [(e["eventId"], e["sourceEventId"]) for e in queued(outbox)] == [
        (events[0]["eventId"], events[0]["sourceEventId"])
    ]
    long_id = "x" * 190
    reading = adapter.to_reading({"spout": "SP-1", "ml": 1, "rid": long_id})
    assert len(runner.source_event_id("bar", reading)) <= 200


def test_a_pour_event_has_the_ledger_shape(tmp_path, store):
    inbox = tmp_path / "pours"
    inbox.mkdir()
    write_file(inbox / "a.jsonl", [pour("r1", start=T0 - 10, end=T0 - 7), json.dumps({"spout": "SP-2", "ml": 5})])
    runner, outbox = runner_for(store, [build_adapter(spec(path=str(inbox)), store)])
    runner.poll_once(T0)
    first, second = queued(outbox)
    assert set(first) <= ENVELOPE_FIELDS
    assert {k: v for k, v in first.items() if k not in ("eventId", "payload")} == {
        "source": "edge-agent",
        "sourceEventId": "agent-1:meter:bar:r1",
        "type": POUR_MEASURED,
        "schemaVersion": 1,
        "occurredAt": iso(T0 - 7),
        "branchId": "branch-1",
        "deviceId": "dev-1",
        "evidenceLevel": "item",
        "evidenceSource": "sensor",
        "observationStatus": "observed",
    }
    validate_measurement(first["type"], first["payload"])
    assert "confidence" not in first and "cameraId" not in first["payload"]
    assert second["occurredAt"] == iso(T0)  # no vendor time: the time it was read
    for event_type, payload in [
        (POUR_MEASURED, {"volumeMl": 0}),
        (POUR_MEASURED, {"volumeMl": 5000.5}),
        (POUR_MEASURED, {"volumeMl": True}),
        (POUR_MEASURED, {"volumeMl": 1, "productId": ""}),
        (POUR_MEASURED, {"volumeMl": 1, "startedAt": iso(T0), "endedAt": iso(T0 - 1)}),
        (MEASUREMENT_GAP, {"from": iso(T0), "to": iso(T0 + 1), "reason": "stream_lost"}),
        (MEASUREMENT_GAP, {"from": iso(T0), "to": iso(T0 - 1), "reason": "adapter_down"}),
    ]:
        with pytest.raises(ValueError):
            validate_measurement(event_type, payload)


# -- outages ----------------------------------------------------------------------------------------------------------------


def reading_of(adapter, external_id, rid):
    return adapter.to_reading({"spout": external_id, "ml": 30, "rid": rid})


def test_an_adapter_down_for_a_while_reports_a_gap_for_its_devices(store):
    adapter = FlakyAdapter(store, pollSeconds=5)
    runner, outbox = runner_for(store, [adapter])
    adapter.readings = [reading_of(adapter, "SP-1", "r1"), reading_of(adapter, "SP-3", "r2")]
    assert runner.poll_once(T0) == 1
    adapter.failing = True
    for t in range(10, 80, 10):
        assert runner.poll_once(T0 + t) == 0
    assert runner.holdback() == T0 + 10
    adapter.failing = False
    assert runner.poll_once(T0 + 80) == 1
    gaps = [e for e in queued(outbox) if e["type"] == MEASUREMENT_GAP]
    assert len(gaps) == 1  # SP-3 was seen but is disabled; SP-2 was never seen by this adapter
    gap = gaps[0]
    assert gap["deviceId"] == "dev-1" and gap["observationStatus"] == "unavailable"
    assert gap["payload"] == {"from": iso(T0 + 10), "to": iso(T0 + 80), "reason": "adapter_down"}
    assert gap["occurredAt"] == iso(T0 + 10)
    validate_measurement(MEASUREMENT_GAP, gap["payload"])
    assert runner.holdback() is None
    assert runner.counters()["adapterFailures"] == 7
    # A short outage is not reported.
    adapter.failing = True
    runner.poll_once(T0 + 90)
    adapter.failing = False
    runner.poll_once(T0 + 100)
    assert len([e for e in queued(outbox) if e["type"] == MEASUREMENT_GAP]) == 1


def test_a_long_outage_is_reported_every_15_minutes_and_at_shutdown(store):
    adapter = FlakyAdapter(store, pollSeconds=60, devices="*")
    runner, outbox = runner_for(store, [adapter])
    adapter.failing = True
    for t in range(0, 1500, 60):
        runner.poll_once(T0 + t)
    gaps = queued(outbox)
    assert sorted(e["deviceId"] for e in gaps) == ["dev-1", "dev-2"]  # "*": every enabled device of the agent
    assert gaps[0]["payload"]["from"] == iso(T0) and gaps[0]["payload"]["to"] == iso(T0 + 900)
    assert runner.holdback() == T0 + 900
    assert runner.flush_gaps(T0 + 1500) == 2
    last = queued(outbox)[-1]["payload"]
    assert last == {"from": iso(T0 + 900), "to": iso(T0 + 1500), "reason": "adapter_down"}


def test_devices_seen_by_an_adapter_are_remembered_across_restarts(store):
    adapter = FlakyAdapter(store)
    runner, outbox = runner_for(store, [adapter])
    adapter.readings = [reading_of(adapter, "SP-2", "r1")]
    runner.poll_once(T0)
    again = FlakyAdapter(store)
    runner2, _ = runner_for(store, [again])
    again.failing = True
    runner2.poll_once(T0 + 10)
    runner2.poll_once(T0 + 100)
    assert runner2.flush_gaps(T0 + 100) == 1
    assert queued(outbox)[-1]["deviceId"] == "dev-2"


# -- http_poll --------------------------------------------------------------------------------------------------------------


def test_http_poll_carries_and_keeps_its_cursor(store):
    calls = []
    answers = [
        {"data": [{"rid": "7", "spout": "SP-1", "ml": 30}, {"rid": "8", "spout": "SP-1", "ml": "x"}]},
        {"data": []},
    ]

    def fetch(url, timeout):
        calls.append((url, timeout))
        return json.dumps(answers.pop(0)).encode()

    entry = {"name": "api", "type": "http_poll", "url": "http://127.0.0.1:8080/pours?limit=100", "itemsKey": "data",
             "cursorParam": "after", "fields": dict(FIELDS)}
    adapter = build_adapter(parse_spec(entry), store, fetch)
    runner, outbox = runner_for(store, [adapter])
    assert runner.poll_once(T0) == 1
    assert calls[0] == ("http://127.0.0.1:8080/pours?limit=100", 5.0)
    assert store.get_meta("meter:api:cursor") == "8"  # past the bad record too
    assert adapter.bad_records == 1
    # A new adapter (after a restart) starts from the stored cursor; an empty answer keeps it.
    adapter2 = build_adapter(parse_spec(entry), store, fetch)
    runner2, _ = runner_for(store, [adapter2])
    runner2.poll_once(T0 + 10)
    assert calls[1][0] == "http://127.0.0.1:8080/pours?limit=100&after=8"
    assert store.get_meta("meter:api:cursor") == "8"


def test_http_poll_without_ids_uses_the_latest_time_and_fails_on_errors(store):
    answers = [[{"spout": "SP-1", "ml": 30, "end": iso(T0 - 5)}, {"spout": "SP-1", "ml": 20, "end": iso(T0 - 9)}]]

    def fetch(url, timeout):
        if not answers:
            raise OSError("connection refused")
        return json.dumps(answers.pop(0)).encode()

    fields = {"externalId": "spout", "volume": "ml", "endedAt": "end"}
    adapter = build_adapter(parse_spec({"name": "api", "type": "http_poll", "url": "http://localhost/p",
                                        "cursorParam": "since", "fields": fields}), store, fetch)
    runner, _ = runner_for(store, [adapter])
    assert runner.poll_once(T0) == 2
    assert adapter.cursor == iso(T0 - 5)
    with pytest.raises(AdapterError):
        adapter.poll(T0 + 10)
    bad = build_adapter(parse_spec({"name": "api2", "type": "http_poll", "url": "http://localhost/p",
                                    "fields": fields}), store, lambda url, timeout: b'{"not": "a list"}')
    with pytest.raises(AdapterError):
        bad.poll(T0)
    assert with_param("http://h/p?a=1&since=x", "since", "2027-01-15T08:00:00.000Z") == (
        "http://h/p?a=1&since=2027-01-15T08%3A00%3A00.000Z"
    )


# -- configuration ----------------------------------------------------------------------------------------------------------


def test_adapter_config_errors_skip_only_the_wrong_entry(tmp_path, store):
    good = {"name": "bar-1", "type": "jsonl_dir", "path": str(tmp_path), "fields": {"externalId": "a", "volume": "b"}}
    wrong = [
        {**good, "name": "Bar"},
        {**good, "name": "x" * 41},
        {**good, "type": "ftp"},
        {**good, "unit": "pint"},
        {**good, "timezone": "Mars/Olympus"},
        {**good, "pollSeconds": 0},
        {**good, "gapAfterSeconds": "60"},
        {**good, "devices": ["SP-1"]},
        {**good, "colour": "red"},
        {**good, "fields": {"externalId": "a"}},
        {**good, "fields": {"externalId": "a", "volume": "b", "weight": "c"}},
        {**good, "path": None},
        {**good, "type": "http_poll", "url": "ftp://x"},
        "not an object",
    ]
    for entry in wrong:
        name = entry.get("name") if isinstance(entry, dict) else None
        if name == "bar-1":
            entry = {**entry, "name": "other"}
        adapters = load_adapters([good, entry], store)
        assert [a.name for a in adapters] == ["bar-1"], entry
    assert [a.name for a in load_adapters([good, good], store)] == ["bar-1"]  # names are unique
    adapter = load_adapters([{**good, "unit": "oz_us", "pollSeconds": 2, "gapAfterSeconds": 120,
                              "timezone": "Europe/Berlin", "devices": "*"}], store)[0]
    assert adapter.spec.poll_seconds == 2 and adapter.spec.gap_after_seconds == 120 and adapter.spec.all_devices
    path = tmp_path / "adapters.json"
    path.write_text('{"name": "bar"}')
    with pytest.raises(ConfigError):
        load_adapters_file(path, store)
    path.write_text("[")
    with pytest.raises(ConfigError):
        load_adapters_file(tmp_path / "missing.json", store)
    with pytest.raises(ConfigError):
        load_adapters_file(path, store)


def test_the_agent_config_lists_its_measurement_devices():
    data = {
        "agentId": "a1", "branchId": "b1", "configVersion": 3, "cameras": [],
        "measurementDevices": [
            {"id": "d1", "kind": "flow_meter", "vendor": "Acme", "externalId": "SP-1", "productId": "p1",
             "station": "Main bar", "standardPourMl": 25, "enabled": True},
            {"id": "d2", "kind": "pour_spout", "externalId": "SP-2", "enabled": False},
            {"id": "d3"},
            "junk",
        ],
    }
    parsed = AgentConfig.parse(data)
    assert parsed.measurement_devices == (
        MeasurementDevice(id="d1", external_id="SP-1", kind="flow_meter", vendor="Acme", product_id="p1",
                          station="Main bar", standard_pour_ml=25.0, enabled=True),
        MeasurementDevice(id="d2", external_id="SP-2", kind="pour_spout", enabled=False),
    )
    assert AgentConfig.parse({"agentId": "a1", "configVersion": 1}).measurement_devices == ()


# -- the agent ---------------------------------------------------------------------------------------------------------------


@pytest.fixture
def agents():
    made = []
    yield made
    for agent in made:
        agent.commands.shutdown()
        agent.ring.close()
        agent.store.close()


def test_the_agent_reads_its_meters_and_reports_them(tmp_path, agents):
    inbox = tmp_path / "pours"
    inbox.mkdir()
    write_file(inbox / "a.jsonl", [pour("r1"), pour("r2", spout="SP-9")])
    adapters = tmp_path / "adapters.json"
    adapters.write_text(json.dumps([{"name": "bar", "type": "jsonl_dir", "path": str(inbox), "fields": FIELDS},
                                    {"name": "Broken", "type": "jsonl_dir"}]))
    devices = [{"id": "dev-1", "kind": "pour_spout", "externalId": "SP-1", "enabled": True}]
    clock = FakeClock()
    agent = make_agent(tmp_path, api=FakeApi(config(measurementDevices=devices)), clock=clock,
                       LP_MEASUREMENT_ADAPTERS=str(adapters))
    agents.append(agent)
    assert agent.meters is not None and [a.name for a in agent.meters.adapters] == ["bar"]
    assert agent.meters.poll_once(T0) == 0  # no configuration yet
    agent.refresh_config()
    assert agent.meters.poll_once(T0) == 1
    assert agent.outbox.oldest_unsent("meter:bar") == T0 - 28
    _, health = agent.health_status()
    assert health["measurements"]["readingsQueued"] == 1 and health["measurements"]["unknownDevices"] == 1
    # The queued reading holds the agent's watermark back until it is sent.
    assert agent.observed_through() < T0 - 28
    agent.sender.send_once()
    assert agent.api.sent[-1][0]["type"] == POUR_MEASURED
    assert agent.outbox.oldest_unsent("meter:bar") is None


def test_meter_readings_survive_a_clock_drift_withdrawal(tmp_path, agents):
    adapters = tmp_path / "adapters.json"
    inbox = tmp_path / "pours"
    inbox.mkdir()
    write_file(inbox / "a.jsonl", [pour("r1")])
    adapters.write_text(json.dumps([{"name": "bar", "type": "jsonl_dir", "path": str(inbox), "fields": FIELDS}]))
    devices = [{"id": "dev-1", "externalId": "SP-1"}]
    agent = make_agent(tmp_path, api=FakeApi(config(measurementDevices=devices)),
                       LP_MEASUREMENT_ADAPTERS=str(adapters))
    agents.append(agent)
    agent.refresh_config()
    agent.record_drift(0.0, T0, "server", 0.0)  # last good check before anything is queued
    agent.meters.poll_once(T0)
    agent.emit("cam-1", [Observation(OCCUPANCY, "zone-2", "cam-1", T0, {"count": 1, "class": "person"}, 0.9)])
    assert agent.outbox.length() == 2
    agent.record_drift(10.0, T0 + 1, "server", 0.0)
    assert agent.drift.drifting
    assert [e["type"] for e in queued(agent.outbox)] == [POUR_MEASURED]  # the vision event was withdrawn


def test_no_adapters_file_means_no_meter_thread(tmp_path, agents):
    agent = make_agent(tmp_path)
    agents.append(agent)
    assert agent.meters is None
    assert agent.health_status()[1]["measurements"] is None
