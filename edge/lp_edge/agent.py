"""The agent: configuration, cameras, heartbeat, commands, outbox, clips and retention, in one process.

Threads: the main loop (configuration, heartbeat, markers, clip registration, retention), the outbox sender, the
command long poll and its workers, the clip producer, two threads per enabled camera, the pour-meter reader (when
``LP_MEASUREMENT_ADAPTERS`` names adapters), and the local health server.
Everything the agent must not lose is in SQLite or on disk, so it can be stopped at any moment.
"""

from __future__ import annotations

import hashlib
import json
import logging
import shutil
import signal
import threading
import time
from collections.abc import Callable, Mapping
from pathlib import Path
from typing import Any

from lp_edge import __version__
from lp_edge.api import ApiClient, ApiError, CredentialsRejected, TokenProvider
from lp_edge.camera import CameraObserver, CameraWorker, FrameSource, Unobserved
from lp_edge.clips import SETTLE_SECONDS, ClipStore, ClipUnavailable
from lp_edge.clock import DriftMonitor, server_offset, sntp_offset
from lp_edge.commands import REPORTED, CommandDispatcher, CommandError
from lp_edge.credentials import Credentials
from lp_edge.crypto import Cipher, load_or_create_key
from lp_edge.detectors import load_detector
from lp_edge.detectors.base import Detector
from lp_edge.events import Observation, envelope
from lp_edge.health import HealthServer
from lp_edge.live import MAX_FRAME_BYTES, LiveManager, LiveSession
from lp_edge.measurements import ConfigError, MeasurementRunner, load_adapters_file
from lp_edge.measurements.runner import OUTBOX_PREFIX as METER_PREFIX
from lp_edge.models import ModelManager
from lp_edge.outbox import Outbox, OutboxSender
from lp_edge.profiles import ProfileEnv
from lp_edge.profiles.references import EncryptedReferenceStore
from lp_edge.remote_config import AgentConfig, Camera, ModelSpec, ReleaseSpec
from lp_edge.ringbuffer import RingBuffer
from lp_edge.settings import Settings
from lp_edge.signing import TrustStore, release_message
from lp_edge.store import Store
from lp_edge.streams import StreamRegistry
from lp_edge.timeutil import Clock, SystemClock, iso, iso_or_none, parse_iso
from lp_edge.uploader import CHUNK, UploadFailed, upload

log = logging.getLogger(__name__)

SNAPSHOT_MAX_BYTES = 1_500_000
MARKER_SECONDS = 10.0
REGISTER_SECONDS = 5.0
RETENTION_SECONDS = 300.0
SNTP_SECONDS = 300.0
RESTART_EXIT_CODE = 75  # EX_TEMPFAIL: the container's restart policy brings the agent back


def disk_usage(path: Path) -> tuple[int, int]:
    usage = shutil.disk_usage(path)
    return usage.free, usage.total


class Agent:
    def __init__(
        self,
        settings: Settings,
        credentials: Credentials,
        *,
        api: ApiClient | None = None,
        clock: Clock | None = None,
        open_source: Callable[[str], FrameSource | None] | None = None,
        start_cameras: bool = True,
    ) -> None:
        self.settings = settings
        self.credentials = credentials
        self.clock = clock or SystemClock()
        self._start_cameras = start_cameras
        settings.data_dir.mkdir(parents=True, exist_ok=True)
        self.store = Store(settings.db_file)
        self.cipher = Cipher(load_or_create_key(settings.key_file, settings.buffer_key))
        self.ring = RingBuffer(settings.buffer_dir, self.cipher)
        self.clips = ClipStore(
            self.store,
            settings.clips_dir,
            self.cipher,
            self.ring,
            tmp_dir=settings.tmp_dir,
            fps=settings.buffer_fps,
            clock=self.clock,
        )
        self.outbox = Outbox(self.store, self.clock)
        self.references = EncryptedReferenceStore(settings.references_dir, self.cipher)
        self.streams = StreamRegistry(settings.streams_file)
        self.trust = TrustStore.parse(settings.trust_keys)
        if api is None:
            import requests  # noqa: PLC0415

            session = requests.Session()
            tokens = TokenProvider(credentials, session, verify=settings.tls_verify)
            api = ApiClient(
                credentials.api_url,
                tokens=tokens,
                session=session,
                verify=settings.tls_verify,
                allow_http=settings.allow_http,
            )
        self.api = api
        self.models = ModelManager(settings.models_dir, self.trust, self.api.download)
        good_seq = int(self.store.get_meta("clock_good_seq") or 0)
        self.drift = DriftMonitor(last_good_seq=good_seq)
        self.commands = CommandDispatcher(self.store, self.api.command_result, clock=self.clock)
        self.live = LiveManager()
        self.sender = OutboxSender(self.outbox, self.api.send_events, can_send=lambda: not self.drift.drifting)
        self.health = HealthServer(settings.health_port, self.health_status, self.desired_release)
        self._open_source = open_source or self._capture_source
        self._stream_digests: dict[str, str] = {}  # alias -> SHA-256 of the address in use (never the address)
        self.config: AgentConfig | None = None
        self.detector: Detector | None = None
        self.workers: dict[str, CameraWorker] = {}
        self.observers: dict[str, CameraObserver] = {}
        self.frozen: dict[str, Unobserved] = {}
        self._env = ProfileEnv(references=self.references)
        self._lock = threading.RLock()
        self._config_lock = threading.Lock()
        self._stop = threading.Event()
        self._wake_sender = threading.Event()
        self._config_wanted = threading.Event()
        self._threads: list[threading.Thread] = []
        self._started_at = self.clock.time()
        self._loop_alive = time.monotonic()
        self._first_config = True
        self._last_heartbeat_at: float | None = None
        self._server_health: str | None = None
        self._health_reasons: list[Any] = []
        self._credentials_rejected = False
        self._release: ReleaseSpec | None = None
        self._release_error: str | None = None
        self._model_spec: ModelSpec | None = None
        self._model_error: str | None = None
        self._model_thread: threading.Thread | None = None
        self._credentials_mtime = self._mtime(settings.credentials_file)
        self.meters = self._load_meters()
        self.exit_code = 0
        self._register_commands()

    # -- running -----------------------------------------------------------------------------------------------------

    def run(self) -> int:
        if threading.current_thread() is threading.main_thread():
            signal.signal(signal.SIGTERM, lambda *_: self.stop())
            signal.signal(signal.SIGINT, lambda *_: self.stop())
        self._check_identity()
        try:
            self.health.start()
        except OSError as exc:
            log.error("the health endpoint could not start on 127.0.0.1:%d: %s", self.settings.health_port, exc)
        cached = self.store.get_meta("config")
        if cached:
            try:
                self._apply_config(AgentConfig.parse(json.loads(cached)))
            except (ValueError, TypeError):
                log.warning("the saved configuration could not be read")
        self.refresh_config()
        self._spawn(self._sender_loop, "sender")
        self._spawn(self._poll_loop, "commands")
        self._spawn(self._clip_loop, "clips")
        if self.meters is not None:
            self._spawn(self._meter_loop, "measurements")
        log.info("lp-edge %s running as agent %s", __version__, self.credentials.agent_id)
        try:
            self._main_loop()
        finally:
            self._shutdown()
        return self.exit_code

    def stop(self, exit_code: int | None = None) -> None:
        if exit_code is not None:
            self.exit_code = exit_code
        self._stop.set()
        self._wake_sender.set()

    def _spawn(self, target: Callable[[], None], name: str) -> None:
        thread = threading.Thread(target=target, name=name, daemon=True)
        self._threads.append(thread)
        thread.start()

    def _main_loop(self) -> None:
        due = {"heartbeat": 0.0, "marker": 0.0, "register": 0.0, "retention": 0.0, "reports": 0.0, "sntp": 0.0}
        while not self._stop.is_set():
            self._loop_alive = time.monotonic()
            now = time.monotonic()
            try:
                if self._config_wanted.is_set():
                    self._config_wanted.clear()
                    self.refresh_config()
                if self.settings.ntp_server and now >= due["sntp"]:
                    due["sntp"] = now + SNTP_SECONDS
                    self._measure_sntp()
                if now >= due["heartbeat"]:
                    due["heartbeat"] = now + (self.config.heartbeat_seconds if self.config else 60)
                    self.heartbeat()
                if now >= due["marker"]:
                    due["marker"] = now + MARKER_SECONDS
                    self.save_markers()
                    self._check_credentials_file()
                    self.check_stream_addresses()
                if now >= due["register"]:
                    due["register"] = now + REGISTER_SECONDS
                    self.register_clips()
                if now >= due["reports"]:
                    due["reports"] = now + 60.0
                    self.commands.report_pending()
                if now >= due["retention"]:
                    due["retention"] = now + RETENTION_SECONDS
                    self.retention()
            except Exception:  # noqa: BLE001 - the main loop must keep going
                log.exception("main loop step failed")
            self._stop.wait(1.0)

    def _shutdown(self) -> None:
        log.info("stopping")
        self._stop.set()
        self.live.stop_all()
        with self._lock:
            workers = list(self.workers.values())
        for worker in workers:
            worker.stop()
        self.save_markers()
        self.ring.close()
        self.commands.shutdown()
        self.health.stop()
        for thread in self._threads:
            thread.join(5.0)
        self.store.close()

    def _check_identity(self) -> None:
        """A box enrolled again as another agent starts clean: the old agent's queue is not sent as the new one."""
        known = self.store.get_meta("agent_id")
        if known and known != self.credentials.agent_id:
            log.warning("this box was enrolled again as agent %s; the previous agent's queue is set aside",
                        self.credentials.agent_id)
            self.outbox.withdraw_after(0, "the box was enrolled again as another agent")
            with self.store.transaction() as conn:
                conn.execute("UPDATE clips SET state = 'rejected', reason = 'enrolled again' WHERE state != 'uploaded'")
                conn.execute("DELETE FROM meta WHERE key LIKE 'camera:%' OR key = 'config'")
                conn.execute("DELETE FROM commands")
        self.store.put_meta("agent_id", self.credentials.agent_id)

    def _check_credentials_file(self) -> None:
        mtime = self._mtime(self.settings.credentials_file)
        if mtime is not None and mtime != self._credentials_mtime:
            log.warning("the credentials changed (enrolled again); restarting")
            self.stop(RESTART_EXIT_CODE)

    @staticmethod
    def _mtime(path: Path) -> float | None:
        try:
            return path.stat().st_mtime
        except OSError:
            return None

    # -- configuration -------------------------------------------------------------------------------------------------

    def refresh_config(self) -> bool:
        with self._config_lock:
            try:
                data = self.api.get_config()
            except CredentialsRejected as exc:
                self._rejected(exc)
                return False
            except ApiError as exc:
                log.warning("the configuration could not be read: %s", exc)
                return False
            try:
                config = AgentConfig.parse(data)
            except (ValueError, TypeError) as exc:
                log.error("the configuration is not valid: %s", exc)
                return False
            self.store.put_meta("config", json.dumps(data))
            self._apply_config(config)
            return True

    def _apply_config(self, config: AgentConfig) -> None:
        with self._lock:
            previous = self.config
            self.config = config
            self.drift.set_limit(config.max_drift_ms)
            self._env.max_clip_seconds = float(config.max_clip_seconds)
            self.clips.max_bytes = config.max_clip_bytes
            self._apply_release(config.release)
            if config.model != self._model_spec:
                self._model_spec = config.model
                self._start_model(config.model)
            self._apply_cameras(config)
            if self.meters is not None:
                self.meters.set_devices(config.measurement_devices)
            drawer_zones = {z.id for c in config.cameras for z in c.zones if z.zone_type == "cash_drawer"}
            self.references.remove_except(drawer_zones)
            self._first_config = False
            if previous is None or previous.config_version != config.config_version:
                log.info("configuration %d applied (%d cameras)", config.config_version, len(config.cameras))

    def _apply_release(self, release: ReleaseSpec | None) -> None:
        self._release, self._release_error = None, None
        if release is None:
            return
        if self.trust is None:
            self._release_error = "LP_EDGE_TRUST_KEY is not set: the release cannot be verified"
        elif not self.trust.verify(release_message(release.version, release.image, release.digest), release.signature):
            self._release_error = f"the signature of release {release.version} does not verify"
        else:
            self._release = release
            return
        log.error("%s", self._release_error)

    def desired_release(self) -> dict[str, Any] | None:
        release = self._release
        return release.as_dict() if release is not None else None

    def _start_model(self, spec: ModelSpec | None) -> None:
        if spec is None:
            self._model_error = None
            self._set_detector(None)
            return

        def load() -> None:
            try:
                path = self.models.ensure(spec)
                detector = load_detector(path, spec, self.settings.ort_providers)
            except Exception as exc:  # noqa: BLE001
                self._model_error = str(exc)
                log.error("detector model %s not used: %s", spec.version, exc)
                if self._model_spec == spec:
                    self._set_detector(None)
                return
            if self._model_spec == spec:
                self._model_error = None
                self._set_detector(detector)

        thread = threading.Thread(target=load, name="model", daemon=True)
        self._model_thread = thread
        thread.start()

    def _set_detector(self, detector: Detector | None) -> None:
        with self._lock:
            if detector is None and self.detector is None:
                return
            self.detector = detector
            classes = self._detector_classes()
            for observer in self.observers.values():
                observer.configure(observer.camera, classes)

    def _detector_classes(self) -> frozenset[str] | None:
        return frozenset(self.detector.classes) if self.detector is not None else None

    def _apply_cameras(self, config: AgentConfig) -> None:
        wanted = {camera.id: camera for camera in config.cameras}
        for camera_id in list(self.workers):
            camera = wanted.get(camera_id)
            if camera is None or not camera.enabled:
                self._stop_worker(camera_id, "disabled")
        for camera_id in list(self.observers):
            camera = wanted.get(camera_id)
            if camera is None or not camera.observed:
                self._freeze(camera_id)
        for camera_id in list(self.frozen):
            if camera_id not in wanted:
                # Removed from the configuration: nothing more is reported about it.
                self.frozen.pop(camera_id, None)
                self.store.delete_meta(f"camera:{camera_id}")
        for camera in config.cameras:
            if not camera.observed and camera.id not in self.frozen:
                # Not analysed: its watermark stays where observation stopped (or where this agent first saw it).
                marker = self._marker(camera.id)
                since = float(marker["watermark"]) if marker is not None else self.clock.time()
                self.frozen[camera.id] = Unobserved("disabled", since)
                if marker is None:
                    self.store.put_meta(f"camera:{camera.id}", json.dumps({"watermark": since, "state": "disabled"}))
        for camera in config.cameras:
            if not camera.enabled:
                continue
            worker = self.workers.get(camera.id)
            if worker is not None and worker.camera.stream_key == camera.stream_key:
                worker.camera = camera
                if worker.observer is not None:
                    worker.observer.configure(camera, self._detector_classes())
                continue
            observer = self.observers.get(camera.id)
            if worker is not None:
                self._stop_worker(camera.id, "stream_lost")
            if camera.observed:
                if observer is None:
                    observer = CameraObserver(
                        camera,
                        env=self._env,
                        emit=self.emit,
                        detector_classes=self._detector_classes(),
                        unobserved=self._initial_unobserved(camera.id),
                        start_grace=self.settings.stall_seconds,
                    )
                    self.observers[camera.id] = observer
                    self.frozen.pop(camera.id, None)
                else:
                    observer.configure(camera, self._detector_classes())
            else:
                observer = None
            self._start_worker(camera, observer)

    def _initial_unobserved(self, camera_id: str) -> Unobserved:
        frozen = self.frozen.get(camera_id)
        if frozen is not None:
            return Unobserved("disabled", frozen.since)
        marker = self._marker(camera_id)
        if marker is not None:
            reason = "disabled" if marker.get("state") == "disabled" else "agent_down"
            return Unobserved(reason, float(marker["watermark"]))
        return Unobserved(None, self.clock.time())

    def _marker(self, camera_id: str) -> dict[str, Any] | None:
        raw = self.store.get_meta(f"camera:{camera_id}")
        if not raw:
            return None
        try:
            marker = json.loads(raw)
            float(marker["watermark"])
            return marker
        except (ValueError, KeyError, TypeError):
            return None

    def _start_worker(self, camera: Camera, observer: CameraObserver | None) -> None:
        if not self._start_cameras:
            return
        worker = CameraWorker(
            camera,
            open_source=lambda alias=camera.stream_alias: self._open_source(alias),
            observer=observer,
            buffer=self._buffer_frame,
            detector=lambda: self.detector,
            analysis_allowed=lambda: not self.drift.drifting,
            analysis_fps=self.settings.analysis_fps,
            buffer_fps=self.settings.buffer_fps,
            analysis_max_width=self.settings.analysis_max_width,
            stall_seconds=self.settings.stall_seconds,
            clock=self.clock,
            resize=_resize,
            motion_factory=_motion_model,
        )
        self.workers[camera.id] = worker
        worker.start()

    def _stop_worker(self, camera_id: str, reason: str) -> None:
        worker = self.workers.pop(camera_id, None)
        if worker is None:
            return
        worker.stop()
        self.ring.close_camera(camera_id)
        if worker.observer is not None:
            worker.observer.interrupt(reason)

    def _freeze(self, camera_id: str) -> None:
        observer = self.observers.pop(camera_id, None)
        if observer is None:
            return
        observer.interrupt("disabled")
        self.frozen[camera_id] = Unobserved("disabled", observer.watermark())
        self.store.put_meta(
            f"camera:{camera_id}", json.dumps({"watermark": observer.watermark(), "state": "disabled"})
        )

    def _capture_source(self, alias: str) -> FrameSource | None:
        url = self.streams.url_for(alias)
        if url is None:
            self._stream_digests.pop(alias, None)
            return None
        from lp_edge.video import CaptureSource  # noqa: PLC0415

        self._stream_digests[alias] = _digest(url)
        return CaptureSource(url)

    def check_stream_addresses(self) -> None:
        """Reconnect a camera whose address was changed on this box while its stream was running."""
        with self._lock:
            workers = list(self.workers.values())
        for worker in workers:
            alias = worker.camera.stream_alias
            in_use = self._stream_digests.get(alias)
            if in_use is None:
                continue
            url = self.streams.url_for(alias)
            if url is None or _digest(url) != in_use:
                log.info("camera %s: its stream address changed on this box; reconnecting", worker.camera.id)
                self._stream_digests.pop(alias, None)
                worker.reconnect()

    def _buffer_frame(self, camera_id: str, ts: float, frame: Any) -> None:
        from lp_edge.video import encode_jpeg, resize_to_width  # noqa: PLC0415

        jpeg = encode_jpeg(resize_to_width(frame, self.settings.buffer_max_width), self.settings.buffer_jpeg_quality)
        self.ring.append(camera_id, ts, jpeg)

    # -- observations ---------------------------------------------------------------------------------------------------

    def emit(self, camera_id: str, observations: list[Observation]) -> None:
        """Store observations (and their clip plans) in one transaction; nothing is stored while the clock is off."""
        if not observations:
            return
        if self.drift.drifting:
            log.debug("%d observations dropped: the clock is off", len(observations))
            return
        branch_id = (self.config.branch_id if self.config else "") or self.credentials.branch_id
        with self.store.transaction() as conn:
            for observation in observations:
                try:
                    event = envelope(observation, agent_id=self.credentials.agent_id, branch_id=branch_id)
                except ValueError as exc:
                    log.error("an observation of zone %s was dropped: %s", observation.zone_id, exc)
                    continue
                if observation.clip is not None:
                    self.clips.plan(camera_id, observation.zone_id, observation.clip, event["eventId"], conn=conn)
                self.outbox.add(event, camera_id, conn=conn)
        self._wake_sender.set()

    # -- pour meters ---------------------------------------------------------------------------------------------------

    def _load_meters(self) -> MeasurementRunner | None:
        path = self.settings.measurement_adapters
        if path is None:
            return None
        try:
            adapters = load_adapters_file(path, self.store)
        except ConfigError as exc:
            log.error("%s; no meter is read", exc)
            return None
        if not adapters:
            log.warning("the meter adapters file names no usable adapter; no meter is read")
            return None
        log.info("%d meter adapters configured", len(adapters))
        return MeasurementRunner(
            adapters,
            self.store,
            self.outbox,
            agent_id=self.credentials.agent_id,
            branch_id=lambda: (self.config.branch_id if self.config else "") or self.credentials.branch_id,
            on_queued=self._wake_sender.set,
        )

    def _meter_loop(self) -> None:
        assert self.meters is not None
        self.meters.run(self._stop, self.clock, paused=lambda: self.drift.drifting)

    def camera_watermark(self, camera_id: str) -> float | None:
        observer = self.observers.get(camera_id)
        if observer is not None:
            mark = observer.watermark()
        elif camera_id in self.frozen:
            mark = self.frozen[camera_id].since
        else:
            return None
        oldest = self.outbox.oldest_unsent(camera_id)
        if oldest is not None:
            mark = min(mark, oldest - 0.001)
        return min(mark, self.clock.time())

    def observed_through(self) -> float:
        """The agent's watermark: the least of its analysed cameras', or how far the outbox has drained.

        Meter readings still queued, and the unreported outage of a failing meter adapter, hold it back too.
        """
        marks = []
        if self.config is not None:
            for camera in self.config.cameras:
                if camera.observed:
                    mark = self.camera_watermark(camera.id)
                    if mark is not None:
                        marks.append(mark)
        if marks:
            if self.meters is not None:
                oldest_meter = self.outbox.oldest_unsent_with_prefix(METER_PREFIX)
                if oldest_meter is not None:
                    marks.append(oldest_meter - 0.001)
                held = self.meters.holdback()
                if held is not None:
                    marks.append(held)
            return min(marks)
        oldest = self.outbox.oldest_unsent()
        now = self.clock.time()
        mark = min(now, oldest - 0.001) if oldest is not None else now
        held = self.meters.holdback() if self.meters is not None else None
        return min(mark, held) if held is not None else mark

    def save_markers(self) -> None:
        for camera_id, observer in list(self.observers.items()):
            self.store.put_meta(
                f"camera:{camera_id}", json.dumps({"watermark": observer.watermark(), "state": "observed"})
            )

    # -- heartbeat and clock ----------------------------------------------------------------------------------------------

    def heartbeat_body(self) -> dict[str, Any]:
        cameras = []
        for camera in self.config.cameras if self.config else ():
            worker = self.workers.get(camera.id)
            if worker is not None and camera.enabled:
                status = worker.status()
            else:
                status = {"cameraId": camera.id, "streamState": "disabled", "fps": 0.0, "lastFrameAt": None}
            status["observedThrough"] = iso_or_none(self.camera_watermark(camera.id))
            cameras.append(status)
        try:
            free, total = disk_usage(self.settings.data_dir)
        except OSError:
            free, total = 0, 0
        return {
            "agentVersion": __version__,
            "modelVersion": self.detector.version if self.detector is not None else None,
            "configVersion": self.config.config_version if self.config else 0,
            "clockDriftMs": self.drift.drift_ms,
            "diskFreeBytes": free,
            "diskTotalBytes": total,
            "queueLength": self.outbox.length(),
            "observedThrough": iso(self.observed_through()),
            "cameras": cameras,
        }

    def heartbeat(self) -> None:
        body = self.heartbeat_body()
        sent = self.clock.time()
        try:
            answer = self.api.heartbeat(body)
        except CredentialsRejected as exc:
            self._rejected(exc)
            return
        except ApiError as exc:
            log.warning("heartbeat failed: %s", exc)
            return
        received = self.clock.time()
        self._credentials_rejected = False
        self._last_heartbeat_at = received
        if not isinstance(answer, Mapping):
            return
        self._server_health = answer.get("health")
        self._health_reasons = list(answer.get("healthReasons") or [])
        server_time = answer.get("serverTime")
        if server_time and not self.settings.ntp_server:
            try:
                offset = server_offset(str(server_time), sent, received)
            except ValueError:
                offset = None
            if offset is not None:
                self.record_drift(offset, received, "server", (received - sent) / 2.0)
        version = answer.get("configVersion")
        if version is not None and self.config is not None and str(version) != str(self.config.config_version):
            self._config_wanted.set()
        commands = answer.get("commands")
        if isinstance(commands, list) and commands:
            self.commands.submit(c for c in commands if isinstance(c, Mapping))

    def _measure_sntp(self) -> None:
        assert self.settings.ntp_server
        try:
            offset, delay = sntp_offset(self.settings.ntp_server)
        except (OSError, ValueError) as exc:
            log.warning("the clock could not be measured with SNTP: %s", exc)
            return
        self.record_drift(offset, self.clock.time(), "ntp", max(0.0, delay) / 2.0)

    def record_drift(self, offset: float, at: float, source: str, uncertainty: float) -> None:
        change = self.drift.record(offset, at, outbox_seq=self.outbox.max_seq(), source=source, uncertainty=uncertainty)
        if not self.drift.drifting:
            self.store.put_meta("clock_good_seq", str(self.drift.last_good_seq))
        if change is None:
            return
        if change.drifting:
            log.error(
                "the clock is off by %d ms (limit %d ms): no observations until it is right",
                self.drift.drift_ms,
                self.drift.max_drift_ms,
            )
            start = change.last_good_at if change.last_good_at is not None else at
            for observer in list(self.observers.values()):
                observer.interrupt("clock_drift", since=min(start, observer.watermark()), report=False)
            # Meter readings stay: their times are the vendor's, and the meters are not read while the clock is off.
            withdrawn = self.outbox.withdraw_after(
                change.last_good_seq, "made while the clock was off", keep_prefix=METER_PREFIX
            )
            if withdrawn:
                log.warning("%d observations made while the clock was off were withdrawn", withdrawn)
        else:
            log.info("the clock is right again; observations resume")

    def _rejected(self, exc: ApiError) -> None:
        if not self._credentials_rejected:
            log.error("%s", exc.message)
        self._credentials_rejected = True

    # -- sending ----------------------------------------------------------------------------------------------------------

    def _sender_loop(self) -> None:
        self.sender.run(self._stop, self._wake_sender, self.clock)

    def _poll_loop(self) -> None:
        backoff = 1.0
        while not self._stop.is_set():
            wait = self.config.command_wait_seconds if self.config else 25
            try:
                commands = self.api.poll_commands(wait)
            except CredentialsRejected as exc:
                self._rejected(exc)
                self._stop.wait(60.0)
                continue
            except ApiError as exc:
                log.debug("command poll failed: %s", exc)
                self._stop.wait(backoff)
                backoff = min(60.0, backoff * 2)
                continue
            backoff = 1.0
            if commands:
                self.commands.submit(commands)
            if wait == 0:
                self._stop.wait(5.0)

    def _clip_loop(self) -> None:
        while not self._stop.is_set():
            try:
                for row in self.clips.due(self.clock.time()):
                    self.clips.produce(row)
            except Exception:  # noqa: BLE001
                log.exception("clip production failed")
            self._stop.wait(2.0)

    def register_clips(self) -> None:
        rows = self.clips.pending_registration(100)
        if not rows:
            return
        try:
            answer = self.api.register_clips([ClipStore.registration(r) for r in rows])
        except ApiError as exc:
            if exc.status in (400, 422) and len(rows) > 1:
                for row in rows:
                    self._register_one(row)
                return
            if exc.status in (400, 422):
                self.clips.apply_registration(rows, {"results": [{"clipId": rows[0].clip_id, "status": "rejected",
                                                                  "reason": exc.message}]})
                return
            log.warning("clips could not be registered: %s", exc)
            return
        self.clips.apply_registration(rows, answer if isinstance(answer, dict) else {})

    def _register_one(self, row: Any) -> None:
        try:
            answer = self.api.register_clips([ClipStore.registration(row)])
        except ApiError as exc:
            if exc.status in (400, 422):
                self.clips.apply_registration([row], {"results": [{"clipId": row.clip_id, "status": "rejected",
                                                                   "reason": exc.message}]})
            return
        self.clips.apply_registration([row], answer if isinstance(answer, dict) else {})

    # -- retention ----------------------------------------------------------------------------------------------------------

    def retention(self) -> None:
        now = self.clock.time()
        days = self.config.buffer_days if self.config else 7
        cutoff = now - days * 86_400
        segments = self.ring.purge_older_than(cutoff)
        clips = self.clips.purge(cutoff)
        self.outbox.purge_rejected(now - 30 * 86_400)
        self.commands.purge(now - 2 * 86_400)
        short = 0
        while True:
            try:
                free, total = disk_usage(self.settings.data_dir)
            except OSError:
                break
            reserve = max(total * self.settings.reserve_percent / 100.0, float(self.settings.reserve_bytes))
            if free >= reserve:
                break
            removed = self.ring.purge_oldest(30)
            if removed == 0:
                log.warning("the disk is short of space and the buffer is empty")
                break
            short += removed
        if segments or clips or short:
            log.info("retention: %d old segments, %d clips, %d segments for space", segments, clips, short)

    # -- commands -------------------------------------------------------------------------------------------------------------

    def _register_commands(self) -> None:
        self.commands.register("config_changed", self._cmd_config_changed)
        self.commands.register("snapshot", self._cmd_snapshot)
        self.commands.register("calibrate", self._cmd_calibrate)
        self.commands.register("clip_upload", self._cmd_clip_upload)
        self.commands.register("live_start", self._cmd_live_start)
        self.commands.register("live_stop", self._cmd_live_stop)

    def _cmd_config_changed(self, command: Mapping[str, Any]) -> None:
        if not self.refresh_config():
            raise CommandError("the configuration could not be read")

    def _worker(self, payload: Mapping[str, Any]) -> CameraWorker:
        camera_id = str(payload.get("cameraId") or "")
        worker = self.workers.get(camera_id)
        if worker is None:
            raise CommandError("the camera is not enabled on this agent")
        return worker

    def _wait_frame(self, worker: CameraWorker, seconds: float = 10.0) -> tuple[float, Any]:
        deadline = time.monotonic() + seconds
        while True:
            latest = worker.latest_frame(max_age=10.0)
            if latest is not None:
                return latest
            if time.monotonic() >= deadline or self._stop.is_set():
                raise CommandError("the camera is not delivering pictures")
            self._stop.wait(0.5)

    def _cmd_snapshot(self, command: Mapping[str, Any]) -> object:
        from lp_edge.video import jpeg_within  # noqa: PLC0415

        worker = self._worker(command.get("payload") or {})
        _, frame = self._wait_frame(worker)
        jpeg = jpeg_within(frame, SNAPSHOT_MAX_BYTES)
        try:
            self.api.post_snapshot(str(command["id"]), jpeg)
        except ApiError as exc:
            raise CommandError(f"the picture could not be sent: {exc.message}") from None
        return REPORTED

    def _cmd_calibrate(self, command: Mapping[str, Any]) -> None:
        payload = command.get("payload") or {}
        observer = self.observers.get(str(payload.get("cameraId") or ""))
        if observer is None:
            raise CommandError("the camera is not analysed by this agent")
        future = observer.request_calibration(str(payload.get("zoneId") or ""))
        try:
            future.result(timeout=20.0)
        except TimeoutError:
            raise CommandError("no picture from the camera to calibrate with") from None
        except Exception as exc:  # noqa: BLE001
            raise CommandError(str(exc)) from None

    def _cmd_clip_upload(self, command: Mapping[str, Any]) -> None:
        payload = command.get("payload") or {}
        evidence_id = str(payload.get("evidenceId") or "")
        if not evidence_id:
            raise CommandError("evidenceId is missing")
        if payload.get("kind") == "still":
            self._upload_still(evidence_id, payload)
            return
        clip_id = payload.get("clipId")
        if clip_id:
            row = self._flagged_clip(str(clip_id))
        else:
            row = self._range_clip(evidence_id, payload)
        if row.byte_size is None or row.sha256 is None:
            raise CommandError(f"Clip {row.clip_id} is no longer on the agent", code="not_found")
        try:
            upload(
                self.api,
                evidence_id,
                total=int(row.byte_size),
                sha256=row.sha256,
                read=lambda offset, length: self.clips.read(row, offset, length),
                content_type=row.content_type,
                chunk_size=self.config.chunk_bytes if self.config else CHUNK,
                stop=self._stop,
            )
        except UploadFailed as exc:
            raise CommandError(exc.message, code=exc.code) from None
        except ClipUnavailable as exc:
            raise CommandError(str(exc), code="not_found") from None
        self.clips.mark_uploaded(row.clip_id, evidence_id)
        log.info("clip %s uploaded for evidence %s", row.clip_id, evidence_id)

    def _upload_still(self, evidence_id: str, payload: Mapping[str, Any]) -> None:
        """The buffered frame nearest ``at``, uploaded as a still (LP1c); waits for the frame when ``at`` is now."""
        camera_id = str(payload.get("cameraId") or "")
        try:
            at = parse_iso(str(payload.get("at")))
        except ValueError:
            raise CommandError("at must be an ISO-8601 instant") from None
        wait = at + SETTLE_SECONDS - self.clock.time()
        if 0 < wait <= 120:
            self._stop.wait(wait)
        try:
            _, data = self.clips.still(camera_id, at)
        except ClipUnavailable as exc:
            raise CommandError(str(exc), code="not_found") from None
        except Exception as exc:  # noqa: BLE001 - a frame that cannot be decoded fails this command only
            raise CommandError(f"the still could not be made: {type(exc).__name__}") from None
        try:
            upload(
                self.api,
                evidence_id,
                total=len(data),
                sha256=hashlib.sha256(data).hexdigest(),
                read=lambda offset, length: data[offset : offset + length],
                content_type="image/jpeg",
                chunk_size=self.config.chunk_bytes if self.config else CHUNK,
                stop=self._stop,
            )
        except UploadFailed as exc:
            raise CommandError(exc.message, code=exc.code) from None
        log.info("still of %s uploaded for evidence %s", camera_id, evidence_id)

    def _flagged_clip(self, clip_id: str) -> Any:
        row = self.clips.find(clip_id)
        if row is None:
            raise CommandError(f"Clip {clip_id} is no longer on the agent", code="not_found")
        if row.state == "pending":
            wait = row.ready_at - self.clock.time()
            if wait > 0:
                self._stop.wait(min(wait, 120.0))
            row = self.clips.find(clip_id) or row
            if row.state == "pending":
                row = self.clips.produce(row)
        if not row.available:
            raise CommandError(f"Clip {clip_id} is no longer on the agent", code="not_found")
        return row

    def _range_clip(self, evidence_id: str, payload: Mapping[str, Any]) -> Any:
        camera_id = str(payload.get("cameraId") or "")
        try:
            start = parse_iso(str(payload.get("from")))
            end = parse_iso(str(payload.get("to")))
        except ValueError:
            raise CommandError("from and to must be ISO-8601 instants") from None
        if end <= start:
            raise CommandError("to must be after from")
        limit = self.config.max_clip_seconds if self.config else 600
        end = min(end, start + limit)
        wait = end + SETTLE_SECONDS - self.clock.time()
        if 0 < wait <= 120:
            self._stop.wait(wait)
        end = min(end, self.clock.time())
        if end <= start:
            raise CommandError("no video in the buffer for that period", code="not_found")
        try:
            return self.clips.cut_range(evidence_id, camera_id, start, end)
        except ClipUnavailable as exc:
            raise CommandError(str(exc), code="not_found") from None

    def _cmd_live_start(self, command: Mapping[str, Any]) -> None:
        from lp_edge.video import jpeg_within, resize_to_width  # noqa: PLC0415

        payload = command.get("payload") or {}
        session_id = str(payload.get("sessionId") or "")
        if not session_id:
            raise CommandError("sessionId is missing")
        worker = self._worker(payload)
        max_width = int(payload.get("maxWidth") or 960)
        try:
            expires_at = parse_iso(str(payload.get("expiresAt")))
        except ValueError:
            expires_at = self.clock.time() + 300.0
        session = LiveSession(
            session_id,
            frames=lambda: worker.latest_frame(max_age=5.0),
            encode=lambda frame: jpeg_within(resize_to_width(frame, max(160, max_width)), MAX_FRAME_BYTES, quality=70),
            push=self.api.post_live_frame,
            fps=float(payload.get("fps") or 2.0),
            expires_at=expires_at,
            clock=self.clock,
        )
        try:
            self.live.start(session)
        except RuntimeError as exc:
            raise CommandError(str(exc)) from None

    def _cmd_live_stop(self, command: Mapping[str, Any]) -> None:
        payload = command.get("payload") or {}
        self.live.stop(str(payload.get("sessionId") or ""))

    # -- health -------------------------------------------------------------------------------------------------------------

    def health_status(self) -> tuple[int, dict[str, Any]]:
        stalled = time.monotonic() - self._loop_alive > 30.0
        if stalled:
            state = "stalled"
        elif self._credentials_rejected:
            state = "credentials_rejected"
        else:
            state = "ok"
        cameras = []
        for camera in self.config.cameras if self.config else ():
            worker = self.workers.get(camera.id)
            status = worker.status() if worker else {"cameraId": camera.id, "streamState": "disabled", "fps": 0.0}
            status.update(name=camera.name, streamAlias=camera.stream_alias, analysed=camera.analysed,
                          observedThrough=iso_or_none(self.camera_watermark(camera.id)))
            cameras.append(status)
        body = {
            "status": state,
            "version": __version__,
            "agentId": self.credentials.agent_id,
            "branchId": self.credentials.branch_id,
            "configVersion": self.config.config_version if self.config else None,
            "modelVersion": self.detector.version if self.detector is not None else None,
            "modelError": self._model_error,
            "release": {
                "desired": self._release.version if self._release else None,
                "error": self._release_error,
            },
            "server": {
                "health": self._server_health,
                "healthReasons": self._health_reasons,
                "lastHeartbeatAt": iso_or_none(self._last_heartbeat_at),
            },
            "clock": {"driftMs": self.drift.drift_ms, "off": self.drift.drifting, "source": self.drift.source},
            "queueLength": self.outbox.length(),
            "rejectedObservations": self.outbox.rejected_count(),
            "lastSendError": self.sender.last_error,
            "clips": self.clips.counts(),
            "liveViews": len(self.live.active()),
            "observedThrough": iso(self.observed_through()),
            "cameras": cameras,
            "measurements": self.meters.counters() if self.meters is not None else None,
        }
        return (503 if state != "ok" else 200), body


def _digest(url: str) -> str:
    return hashlib.sha256(url.encode("utf-8")).hexdigest()


def _resize(frame: Any, max_width: int) -> Any:
    from lp_edge.video import resize_to_width  # noqa: PLC0415

    return resize_to_width(frame, max_width)


def _motion_model() -> Any:
    from lp_edge.detectors.motion import MotionModel  # noqa: PLC0415

    return MotionModel()
