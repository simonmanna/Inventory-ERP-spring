"""The evaluation report: ``report.json`` with every figure, and ``report.md`` for people.

The overall verdict per candidate is deliberately strict. "Eligible" needs: every profile in the dataset passed
(for cash drawers including transaction correlation, not only detection), the dataset covers the conditions the design
asks for, the weights are pinned by SHA-256 and source, the sustained multi-camera run passed on this machine, and the
licence register clears the candidate. Anything less says what is missing, so a fast model with an unchecked licence,
a good score on daytime footage only, or good detection with poor correlation is never reported as ready.

Every report records what it ran on (``provenance``) and one ``fingerprint`` per candidate: two reports with the same
fingerprint measured the same weights on the same footage with the same pipeline, settings and thresholds.

Activity research (LP7, ``activityResearch``) is reported beside the profiles and never read by the overall verdict.
"""

from __future__ import annotations

import json
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

from lp_edge.evaluation.bench import bench_verdict
from lp_edge.evaluation.provenance import fingerprint

CAVEAT = ("Scores describe this dataset only. Thresholds are the proposed values unless the report says otherwise. A "
          "candidate that passes here still needs the pilot site's own footage before its cases are trusted.")


def overall(result: dict[str, Any], coverage: dict[str, Any], licence: dict[str, Any],
            thresholds: dict[str, Any]) -> dict[str, Any]:
    # result["activityResearch"] is research only (LP7): deliberately not read here.
    blockers = []
    for profile, r in result["profiles"].items():
        status = r["verdict"]["status"]
        if status != "pass":
            blockers.append(f"{profile}: {status}")
        missing = coverage.get(profile, {}).get("missing") or []
        if missing:
            blockers.append(f"{profile} footage not representative: {'; '.join(missing)}")
    if result["labelledClassesTheModelLacks"]:
        blockers.append("the model lacks labelled classes: " + ", ".join(result["labelledClassesTheModelLacks"]))
    if licence["status"] != "cleared":
        blockers.append(f"licence {licence['status']}" + (f": {'; '.join(licence['reasons'])}" if licence["reasons"]
                                                          else ""))
    if not result["speed"]["measured"]:
        blockers.append("speed not measured on the target hardware")
    hardware = bench_verdict(result.get("bench"), thresholds["hardware"])
    if hardware["status"] != "pass":
        failed = [c["check"] for c in hardware["checks"] if c["status"] != "pass"]
        blockers.append(f"hardware {hardware['status']}: " + ("; ".join(failed) if failed else hardware.get("reason",
                                                                                                          "")))
    prov = result.get("provenance") or {}
    if prov.get("kind") == "model" and not (prov.get("expectedSha256") and prov.get("sourceUrl")):
        blockers.append("weights not pinned: give sha256 and sourceUrl in the candidates file")
    return {"status": "eligible" if not blockers else "not eligible", "blockers": blockers, "hardware": hardware}


def build(dataset: Any, coverage: dict[str, Any], thresholds: dict[str, Any], settings: dict[str, Any],
          results: list[dict[str, Any]], licences: dict[str, dict[str, Any]], environment: dict[str, Any],
          provenance: dict[str, Any] | None = None) -> dict:
    """``provenance``: ``{"agent": ..., "clips": [...], "command": [...]}`` (see :mod:`provenance`)."""
    candidates = []
    for result in results:
        licence = licences[result["candidate"]]
        entry = {**result, "licence": licence, "overall": overall(result, coverage, licence, thresholds)}
        if provenance is not None and result.get("provenance"):
            entry["fingerprint"] = fingerprint(result["provenance"], provenance["agent"], provenance["clips"],
                                               settings, thresholds)
        candidates.append(entry)
    return {
        "generatedAt": datetime.now(UTC).isoformat(timespec="seconds"),
        "provenance": provenance,
        "dataset": {"name": dataset.name, "description": dataset.description, "clips": len(dataset.clips),
                    "coverage": coverage},
        "settings": settings,
        "thresholds": thresholds,
        "environment": environment,
        "candidates": candidates,
        "caveat": CAVEAT,
    }


def _f(value: Any, digits: int = 3) -> str:
    if value is None:
        return "—"
    if isinstance(value, float):
        return f"{value:.{digits}f}"
    return str(value)


def _pct(value: Any) -> str:
    return "—" if value is None else f"{value * 100:.1f} %"


def markdown(report: dict[str, Any]) -> str:
    d = report["dataset"]
    s = report["settings"]
    lines = [
        f"# Detector evaluation — {d['name']}",
        "",
        f"Generated {report['generatedAt']}. {d['clips']} clips. Analysis at {s['fps']} frames/s, width "
        f"{s['maxWidth']} px; event tolerance {s['toleranceSeconds']} s; box IoU {s['iou']}.",
        f"Thresholds: {report['thresholds']['source']}.",
        "",
        f"> {report['caveat']}",
        "",
        "## Summary",
        "",
        "| Candidate | Overall | Licence | Hardware run | p95 latency (ms) | Peak memory (MB) | Fingerprint |",
        "|---|---|---|---|---|---|---|",
    ]
    for c in report["candidates"]:
        sp = c["speed"]
        lines.append(f"| {c['candidate']} | **{c['overall']['status']}** | {c['licence']['status']} | "
                     f"{c['overall']['hardware']['status']} | {_f(sp['latencyMsP95'], 1)} | "
                     f"{_f(c['peakMemoryMb'], 0)} | {(c.get('fingerprint') or '—')[:12]} |")
    lines += ["", "## Footage coverage", "", "| Profile | Clips | Shifts | Events | Frames | Missing |",
              "|---|---|---|---|---|---|"]
    for profile, cov in d["coverage"].items():
        lines.append(f"| {profile} | {cov['clips']} | {cov['shifts']} | {cov['labelledEvents']} | "
                     f"{cov['labelledFrames']} | {'; '.join(cov['missing']) or 'none'} |")
    for c in report["candidates"]:
        lines += ["", f"## {c['candidate']} ({c['source']}, {c['version']})", ""]
        if c["overall"]["blockers"]:
            lines += ["Not eligible because:", ""] + [f"- {b}" for b in c["overall"]["blockers"]] + [""]
        for profile, r in c["profiles"].items():
            e = r["events"]
            lines += [f"### {profile}: {r['verdict']['status']}", "",
                      "| Check | Status | Value | Limit |", "|---|---|---|---|"]
            for ch in r["verdict"]["checks"]:
                lines.append(f"| {ch['check']} | {ch['status']} | {_f(ch.get('value'), 4)} | "
                             f"{_f(ch.get('limit'), 4) if 'limit' in ch else ch.get('detail', '')} |")
            lines += ["", f"Events: {e['truePositives']} correct of {e['predicted']} predicted and {e['labelled']} "
                      f"labelled (precision {_pct(e['precision'])}, recall {_pct(e['recall'])}). "
                      f"Count error: max {_pct(r['counts']['maxError'])}, mean {_pct(r['counts']['meanError'])}. "
                      f"Tracking: {r['tracking']['identitySwitches']} identity switches, MOTA "
                      f"{_f(r['tracking']['mota'])}.", ""]
            business = r.get("business")
            if business:
                corr = business["transactionCorrelation"]
                conf = business["confirmedFindings"]
                lines += ["Accuracy by level (each must hold on its own; strong detection does not make up for weak"
                          " correlation):", "", "| Level | Clips | Labelled | Predicted | Precision | Recall |",
                          "|---|---|---|---|---|---|",
                          f"| 2. drawer events | {r['clips']} | {e['labelled']} | {e['predicted']} | "
                          f"{_pct(e['precision'])} | {_pct(e['recall'])} |",
                          f"| 3. transaction correlation ({business['rule']}) | {corr['clips']} | {corr['labelled']} | "
                          f"{corr['predicted']} | {_pct(corr['precision'])} | {_pct(corr['recall'])} |",
                          f"| 4. confirmed findings | {conf['clips']} | {conf['labelled']} | {conf['predicted']} | "
                          f"{_pct(conf['precision'])} | {_pct(conf['recall'])} |", "",
                          f"Level 1 (objects) is the class table below. The pipeline reached the rule's verdict on "
                          f"{_pct(corr['verdictAgreement'])} of the openings it detected.", ""]
            batches = r.get("batches") or {}
            if batches.get("batches"):
                lines += ["Transaction batches (items counted over the scanner, rule V-R1; items carried past it, rule"
                          " V-R2, are reported and not checked):", "",
                          "| Batch | Clip | Zone | True items | Counted | Error | Carried past (labelled / counted) |",
                          "|---|---|---|---|---|---|---|"]
                for b in batches["perBatch"]:
                    lines.append(f"| {b['batch']} | {b['clip']} | {b['zone']} | {b['true']} | {b['predicted']} | "
                                 f"{_pct(b['error'])} | {_f(b['bypassedTrue'])} / {b['bypassedPredicted']} |")
                lines.append("")
            if r["detection"]:
                lines += ["| Class | Labelled | Precision | Recall | AP@IoU |", "|---|---|---|---|---|"]
                for cls, m in r["detection"].items():
                    lines.append(f"| {cls} | {m['labelled']} | {_pct(m['precision'])} | {_pct(m['recall'])} | "
                                 f"{_f(m['averagePrecision'])} |")
                lines.append("")
            if r["zonesNotObservable"]:
                lines += ["Zones the agent could not observe with this candidate:", ""]
                lines += [f"- {k}: {v}" for k, v in r["zonesNotObservable"].items()] + [""]
            if r["clipErrors"]:
                lines += ["Clips that failed:", ""] + [f"- {k}: {v}" for k, v in r["clipErrors"].items()] + [""]
        lines += _research_lines(c)
        lines += _bench_lines(c)
        prov = c.get("provenance") or {}
        if prov:
            lines += ["### Provenance", "", f"- {prov['kind']} file {prov['file']}, {prov['bytes']} bytes, SHA-256 "
                      f"{prov['sha256']} (expected {prov['expectedSha256'] or 'not given'}); source "
                      f"{prov['sourceUrl'] or 'not given'}",
                      "- tracker: " + ", ".join(f"{k} {v}" for k, v in prov["tracker"].items()),
                      f"- fingerprint {c.get('fingerprint') or '—'}", ""]
        lic = c["licence"]
        lines += ["### Licence", "", "| Entry | Component | Licence | Status | Checked |", "|---|---|---|---|---|"]
        for entry in lic["entries"]:
            checked = f"{entry['checkedBy']}, {entry['checkedOn']}" if entry["checkedBy"] else "—"
            lines.append(f"| {entry['name']} | {entry['component']} | {entry['licence']} | {entry['status']} | "
                         f"{checked} |")
        lines += [""] + [f"- {r}" for r in lic["reasons"]]
    p = report.get("provenance") or {}
    if p:
        a = p["agent"]
        lines += ["", "## Footage and pipeline", "", f"Agent {a['version']} (commit {a['commit'] or 'unknown'}), "
                  f"zone-profile and tracker code SHA-256 {a['profilesSha256']}.", "",
                  "| Clip | Profile | Conditions | Video SHA-256 | Labels SHA-256 |", "|---|---|---|---|---|"]
        for clip in p["clips"]:
            cond = clip["conditions"]
            lines.append(f"| {clip['id']} | {clip['profile']} | {cond['period']}, {cond['lighting']}, {cond['angle']} "
                         f"| {(clip['videoSha256'] or '—')[:16]} | {(clip['labelsSha256'] or '—')[:16]} |")
        if p.get("command"):
            lines += ["", "Command: `" + " ".join(p["command"]) + "`"]
    env = report["environment"]
    lines += ["", "## Hardware", "", f"{env.get('platform')}, {env.get('cpus')} CPUs, Python {env.get('python')}, "
              f"ONNX Runtime {env.get('onnxruntime') or 'not installed'} {env.get('onnxProviders') or ''}.", ""]
    return "\n".join(lines)


def _research_lines(c: dict[str, Any]) -> list[str]:
    """Activity research (LP7): only when activities were labelled or reported."""
    research = c.get("activityResearch") or {}
    if not research.get("measured") and not research.get("segmentsOnUnlabelledClips"):
        return []
    lines = [f"### Activity research (LP7; {research['note']})", ""]
    if not research["measured"]:
        return lines + [f"{research['segmentsOnUnlabelledClips']} segment(s) reported, not scored: "
                        f"{research['reason']}.", ""]
    lines += [f"Segments matched on temporal IoU ≥ {research['minIou']} on {research['clips']} labelled clip(s); "
              f"recognizers: {', '.join(research['recognizers']) or 'none'}.", "",
              "| Activity | Labelled | Predicted | Precision | Recall | F1 |", "|---|---|---|---|---|---|"]
    for name, m in [*research["perActivity"].items(), ("all", research["overall"])]:
        lines.append(f"| {name} | {m['labelled']} | {m['predicted']} | {_pct(m['precision'])} | {_pct(m['recall'])} | "
                     f"{_f(m['f1'])} |")
    return lines + [""]


def _bench_lines(c: dict[str, Any]) -> list[str]:
    hw = c["overall"]["hardware"]
    bench = c.get("bench") or {}
    lines = [f"### Hardware run: {hw['status']}", ""]
    if not bench.get("measured"):
        return lines + [bench.get("reason") or hw.get("reason") or "not run", ""]
    lines += ["| Check | Status | Value | Limit |", "|---|---|---|---|"]
    for ch in hw["checks"]:
        lines.append(f"| {ch['check']} | {ch['status']} | {_f(ch.get('value'), 2)} | "
                     f"{_f(ch.get('limit'), 2) if 'limit' in ch else ch.get('detail', '')} |")
    lines += ["", f"{bench['cameras']} cameras for {bench['seconds']} s at {bench['targetFps']} frames/s; slowest "
              f"window {_f(bench['minWindowFps'], 2)} frames/s; last ÷ first window {_f(bench['sustainedRatio'], 3)}; "
              f"event latency p95 {_f(bench['eventLatencySecondsP95'], 2)} s over {bench['events']} events.", "",
              "| Camera | Frames | Dropped | Achieved fps | Slowest window fps | Frame latency p95 (ms) |",
              "|---|---|---|---|---|---|"]
    for cam in bench["perCamera"]:
        lines.append(f"| {cam['camera']} | {cam['frames']} | {cam['dropped']} | {_f(cam['achievedFps'], 2)} | "
                     f"{_f(cam['minWindowFps'], 2)} | {_f(cam['frameLatencyMsP95'], 1)} |")
    res = bench["resources"]
    lines += ["", "| Resource | Mean | Max | First | Last |", "|---|---|---|---|---|"]
    for key in ("processCpuPct", "systemCpuPct", "rssMb", "cpuTemperatureC", "gpuUtilisationPct", "gpuMemoryMb",
                "gpuTemperatureC"):
        v = res[key]
        lines.append(f"| {key} | {_f(v['mean'], 1)} | {_f(v['max'], 1)} | {_f(v['first'], 1)} | {_f(v['last'], 1)} |")
    if res["notAvailable"]:
        lines += ["", "Not available on this machine: " + ", ".join(res["notAvailable"]) + "."]
    return lines + [""]


def write(report: dict[str, Any], out_dir: Path) -> tuple[Path, Path]:
    out_dir.mkdir(parents=True, exist_ok=True)
    json_path = out_dir / "report.json"
    md_path = out_dir / "report.md"
    json_path.write_text(json.dumps(report, indent=2, default=str), encoding="utf-8")
    md_path.write_text(markdown(report), encoding="utf-8")
    return json_path, md_path
