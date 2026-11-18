"""Sustained multi-camera benchmark: does the box keep up with every intended camera at once, for long enough?

A model that is fast on one clip can still fail with four cameras running together, or slow down once the box is hot.
This runs ``cameras`` streams at once, the way the agent does: one processing thread per camera, one shared detector,
each camera paced in real time at the analysis rate and always taking the newest frame (a camera that falls behind
drops frames, as a live stream would). Each camera loops over the dataset's clips. It records:

- achieved frames per second per camera, per window (default one minute), and frames dropped;
- frame latency (frame arrival → analysed) and end-to-end event latency (the moment the event happened in the video
  → the agent's zone profile emits it; includes the profile's own confirmation time, excludes the upload);
- process and system CPU use, resident memory, CPU temperature, and on NVIDIA GPUs utilisation, memory and
  temperature (through ``nvidia-smi``), sampled every second; whatever the platform cannot report is listed.

The limits are the harness's proposal (the design document sets none) and can be replaced with ``--thresholds``.
"""

from __future__ import annotations

import logging
import os
import shutil
import subprocess
import threading
import time
from collections.abc import Callable, Iterator, Sequence
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from lp_edge.evaluation.metrics import percentile
from lp_edge.profiles import ProfileEnv, UnavailableProfile, build_profile
from lp_edge.profiles.base import FrameContext
from lp_edge.tracker import ByteTracker

log = logging.getLogger(__name__)

#: Proposed hardware limits: cameras one box must run at once (no document fixes it yet; set it per site); every
#: camera keeps at least this share of the analysis rate in every window; p95 of end-to-end event latency; shortest run
#: that counts as sustained.
HARDWARE_PROPOSED: dict[str, float] = {"minCameras": 4.0, "minFpsShare": 0.95, "maxEventLatencySecondsP95": 2.0,
                                       "minSustainedSeconds": 1800.0}


@dataclass(frozen=True)
class BenchSettings:
    cameras: int
    seconds: float
    fps: float
    window_seconds: float = 60.0
    sample_seconds: float = 1.0


@dataclass
class CameraResult:
    camera: int
    frames: int = 0
    dropped: int = 0
    observations: int = 0
    frame_latencies: list[float] = field(default_factory=list)
    event_latencies: list[float] = field(default_factory=list)
    window_frames: dict[int, int] = field(default_factory=dict)
    error: str | None = None


# --------------------------------------------------------------------------------------------- resource sampling


def _read(path: str) -> str | None:
    try:
        return Path(path).read_text(encoding="utf-8")
    except OSError:
        return None


def _system_cpu_times() -> tuple[int, int] | None:
    """(busy, total) jiffies from /proc/stat, or None off Linux."""
    text = _read("/proc/stat")
    if not text:
        return None
    values = [int(v) for v in text.splitlines()[0].split()[1:]]
    idle = values[3] + (values[4] if len(values) > 4 else 0)
    return sum(values) - idle, sum(values)


def _rss_mb() -> float | None:
    text = _read("/proc/self/statm")
    if not text:
        return None
    return int(text.split()[1]) * os.sysconf("SC_PAGE_SIZE") / (1024 * 1024)


def _cpu_temperature_c() -> float | None:
    temps = []
    for zone in Path("/sys/class/thermal").glob("thermal_zone*/temp"):
        text = _read(str(zone))
        if text and text.strip().lstrip("-").isdigit():
            temps.append(int(text) / 1000.0)
    return max(temps) if temps else None


def _gpu() -> dict[str, float] | None:
    """Utilisation %, memory MB and temperature °C of the busiest NVIDIA GPU, or None."""
    if shutil.which("nvidia-smi") is None:
        return None
    try:
        out = subprocess.run(["nvidia-smi", "--query-gpu=utilization.gpu,memory.used,temperature.gpu",
                              "--format=csv,noheader,nounits"], capture_output=True, text=True, timeout=5, check=True)
    except (OSError, subprocess.SubprocessError):
        return None
    rows = [[float(v) for v in line.split(",")] for line in out.stdout.strip().splitlines() if line.strip()]
    if not rows:
        return None
    util, mem, temp = max(rows, key=lambda r: r[0])
    return {"utilisationPct": util, "memoryMb": mem, "temperatureC": temp}


class Sampler:
    """Samples resource use every ``interval`` seconds on its own thread."""

    def __init__(self, interval: float) -> None:
        self.interval = interval
        self.samples: list[dict[str, Any]] = []
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._run, name="bench-sampler", daemon=True)

    def __enter__(self) -> Sampler:
        self._thread.start()
        return self

    def __exit__(self, *exc: object) -> None:
        self._stop.set()
        self._thread.join(timeout=10)

    def _run(self) -> None:
        cpus = os.cpu_count() or 1
        last_wall, last_proc, last_sys = time.monotonic(), time.process_time(), _system_cpu_times()
        while not self._stop.wait(self.interval):
            wall, proc, sys_times = time.monotonic(), time.process_time(), _system_cpu_times()
            sample: dict[str, Any] = {"at": wall, "processCpuPct": 100.0 * (proc - last_proc) / (wall - last_wall)
                                      / cpus, "rssMb": _rss_mb(), "cpuTemperatureC": _cpu_temperature_c(),
                                      "gpu": _gpu()}
            if sys_times and last_sys and sys_times[1] > last_sys[1]:
                sample["systemCpuPct"] = 100.0 * (sys_times[0] - last_sys[0]) / (sys_times[1] - last_sys[1])
            self.samples.append(sample)
            last_wall, last_proc, last_sys = wall, proc, sys_times


def _stat(values: Sequence[float | None]) -> dict[str, float | None]:
    present = [v for v in values if v is not None]
    if not present:
        return {"mean": None, "max": None, "first": None, "last": None}
    return {"mean": round(sum(present) / len(present), 1), "max": round(max(present), 1),
            "first": round(present[0], 1), "last": round(present[-1], 1)}


def resources(samples: list[dict[str, Any]]) -> dict[str, Any]:
    gpu = [s["gpu"] for s in samples if s.get("gpu")]
    out = {
        "samples": len(samples),
        "processCpuPct": _stat([s.get("processCpuPct") for s in samples]),
        "systemCpuPct": _stat([s.get("systemCpuPct") for s in samples]),
        "rssMb": _stat([s.get("rssMb") for s in samples]),
        "cpuTemperatureC": _stat([s.get("cpuTemperatureC") for s in samples]),
        "gpuUtilisationPct": _stat([g["utilisationPct"] for g in gpu]),
        "gpuMemoryMb": _stat([g["memoryMb"] for g in gpu]),
        "gpuTemperatureC": _stat([g["temperatureC"] for g in gpu]),
    }
    out["notAvailable"] = [k for k, v in out.items() if isinstance(v, dict) and v["max"] is None]
    return out


# --------------------------------------------------------------------------------------------- one camera


def _camera(index: int, clips: Sequence[Any], frames_of: Callable[[Any], Iterator[tuple[float, Any]]],
            detect_for: Callable[[Any], Callable], classes: frozenset[str], settings: BenchSettings, start: float,
            stop: threading.Event, clock: Callable[[], float], sleep: Callable[[float], None]) -> CameraResult:
    result = CameraResult(index)
    interval = 1.0 / settings.fps
    k = 0  # frame slot since start: slot k arrives at start + k * interval
    clip_number = index  # cameras start on different clips
    try:
        while not stop.is_set():
            clip = clips[clip_number % len(clips)]
            clip_number += 1
            detect = detect_for(clip)
            tracker = ByteTracker()
            env = ProfileEnv(new_clip_id=lambda: "bench")
            profiles = [p for p in (build_profile(z, f"bench-{index}", env, classes) for z in clip.zones)
                        if not isinstance(p, UnavailableProfile)]
            tracked = set().union(*(p.wanted_classes() for p in profiles)) if profiles else set()
            motion: Any = None
            clip_wall_zero: float | None = None
            frames = frames_of(clip)
            last_t = None
            for t, frame in frames:
                now = clock()
                if now - start >= settings.seconds or stop.is_set():
                    stop.set()
                    break
                due = start + k * interval
                if now < due:
                    sleep(due - now)
                elif now - due >= interval:
                    # Behind: a live stream offers only the newest frame, so the frames in between are lost.
                    behind = int((now - due) / interval)
                    result.dropped += behind
                    k += behind
                    skipped = 0
                    for _ in range(behind):
                        if next(frames, None) is None:
                            break
                        skipped += 1
                    if skipped < behind:
                        break
                    continue
                arrival = start + k * interval
                if clip_wall_zero is None:
                    clip_wall_zero = arrival - t
                    for p in profiles:
                        p.start(t)
                detections, _ = detect(t, frame)
                tracks = tracker.update([d for d in detections if d.cls in tracked], t)
                if any(p.needs_motion() for p in profiles):
                    if motion is None:
                        from lp_edge.detectors.motion import MotionModel  # noqa: PLC0415

                        motion = MotionModel()
                    motion.update(frame)
                height, width = (frame.shape[0], frame.shape[1]) if hasattr(frame, "shape") else (9, 16)
                context = FrameContext(ts=t, frame=frame, tracks=tracks, motion=motion, width=width, height=height)
                observations = [o for p in profiles for o in p.process(context)]
                done = clock()
                result.frames += 1
                result.frame_latencies.append(done - arrival)
                for o in observations:
                    result.event_latencies.append(done - (clip_wall_zero + o.occurred_at))
                result.observations += len(observations)
                window = int((done - start) // settings.window_seconds)
                result.window_frames[window] = result.window_frames.get(window, 0) + 1
                k += 1
                last_t = t
            if last_t is not None:
                for p in profiles:
                    p.stop(last_t)
    except Exception as err:  # noqa: BLE001  (report the camera as failed; the others go on)
        log.exception("bench camera %d failed", index)
        result.error = f"{type(err).__name__}: {err}"
    return result


# --------------------------------------------------------------------------------------------- the run


def run_bench(runner: Any, clips: Sequence[Any], frames_of: Callable[[Any], Iterator[tuple[float, Any]]],
              settings: BenchSettings, *, clock: Callable[[], float] = time.monotonic,
              sleep: Callable[[float], None] = time.sleep) -> dict[str, Any]:
    """``settings.cameras`` cameras at once for ``settings.seconds``, through one shared ``runner``."""
    if not clips:
        raise ValueError("the bench needs at least one clip")
    stop = threading.Event()
    results: list[CameraResult | None] = [None] * settings.cameras
    start = clock() + 0.5  # let every thread reach its first frame

    def work(i: int) -> None:
        results[i] = _camera(i, clips, frames_of, runner.for_clip, runner.classes, settings, start, stop, clock, sleep)

    threads = [threading.Thread(target=work, args=(i,), name=f"bench-camera-{i}") for i in range(settings.cameras)]
    with Sampler(settings.sample_seconds) as sampler:
        for thread in threads:
            thread.start()
        for thread in threads:
            thread.join()
    elapsed = max(0.0, clock() - start)
    return summarise([r for r in results if r is not None], settings, elapsed, resources(sampler.samples))


def summarise(cameras: list[CameraResult], settings: BenchSettings, elapsed: float,
              resource_use: dict[str, Any]) -> dict[str, Any]:
    full_windows = int(elapsed // settings.window_seconds)
    per_camera = []
    for c in cameras:
        windows = [c.window_frames.get(w, 0) / settings.window_seconds for w in range(full_windows)]
        p95 = percentile(c.event_latencies, 95)
        per_camera.append({
            "camera": c.camera,
            "frames": c.frames,
            "dropped": c.dropped,
            "observations": c.observations,
            "achievedFps": round(c.frames / elapsed, 2) if elapsed else None,
            "windowFps": [round(w, 2) for w in windows],
            "minWindowFps": round(min(windows), 2) if windows else None,
            "frameLatencyMsP95": None if not c.frame_latencies else round(percentile(c.frame_latencies, 95) * 1000, 1),
            "eventLatencySecondsP95": None if p95 is None else round(p95, 2),
            "error": c.error,
        })
    first = [c["windowFps"][0] for c in per_camera if c["windowFps"]]
    last = [c["windowFps"][-1] for c in per_camera if c["windowFps"]]
    all_events = [v for c in cameras for v in c.event_latencies]
    event_p95 = percentile(all_events, 95)
    return {
        "measured": True,
        "cameras": settings.cameras,
        "targetFps": settings.fps,
        "seconds": round(elapsed, 1),
        "windowSeconds": settings.window_seconds,
        "perCamera": per_camera,
        "minAchievedFps": min((c["achievedFps"] or 0.0 for c in per_camera), default=None),
        "minWindowFps": min((c["minWindowFps"] for c in per_camera if c["minWindowFps"] is not None), default=None),
        # Last full window ÷ first: below 1 means the box slowed down over the run (heat, memory, contention).
        "sustainedRatio": None if not first or not sum(first) else round(sum(last) / sum(first), 3),
        "eventLatencySecondsP95": None if event_p95 is None else round(event_p95, 2),
        "events": len(all_events),
        "resources": resource_use,
    }


def bench_verdict(bench: dict[str, Any] | None, limits: dict[str, float]) -> dict[str, Any]:
    """``pass`` when the run was long enough and every camera kept its rate in every window with low event latency."""
    if not bench or not bench.get("measured"):
        reason = (bench or {}).get("reason") or "no sustained multi-camera run (run with --cameras)"
        return {"status": "not measured", "checks": [], "reason": reason}
    checks = []

    def check(name: str, value: float | None, limit: float, at_least: bool, why: str) -> None:
        if value is None:
            checks.append({"check": name, "status": "not measured", "detail": why})
            return
        ok = value >= limit if at_least else value <= limit
        checks.append({"check": name, "status": "pass" if ok else "fail", "value": value, "limit": limit})

    check("cameras run at once", float(bench["cameras"]), limits["minCameras"], True, "")
    check("run length (s)", bench["seconds"], limits["minSustainedSeconds"], True, "")
    window = bench["minWindowFps"] if bench["minWindowFps"] is not None else bench["minAchievedFps"]
    check("slowest camera, share of the analysis rate", None if window is None else round(window / bench["targetFps"],
                                                                                            3),
          limits["minFpsShare"], True, "no frames analysed")
    check("event latency p95 (s)", bench["eventLatencySecondsP95"], limits["maxEventLatencySecondsP95"], False,
          "no event was emitted during the run")
    errors = [c for c in bench["perCamera"] if c["error"]]
    if errors:
        checks.append({"check": "cameras without errors", "status": "fail",
                       "detail": "; ".join(f"camera {c['camera']}: {c['error']}" for c in errors)})
    statuses = {c["status"] for c in checks}
    status = "fail" if "fail" in statuses else "pass" if statuses == {"pass"} else "not measured"
    return {"status": status, "checks": checks}
