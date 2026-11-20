"""Loss-prevention pilot demo for one tenant (default DEMO). See README.md.

    LP_DEMO_PASSWORD='<admin password>' python3 run.py --confirm DEMO [setup] [events] [reconcile] [report]
"""
import os
import subprocess
import sys
from datetime import date, datetime, timedelta, timezone

import events
import setup
from api import STATE, admin, agent_session, check_target, log, rows, save

STEPS = ["setup", "backdate", "events", "reconcile", "report"]
ENGINES = ["cashier", "bar", "cashier_vision", "bar_pour", "kitchen_flow", "service_vision"]


def heartbeat(agent):
    """The simulated agent reports every camera healthy and observed up to now (the engines' watermark)."""
    now = datetime.now(timezone.utc).isoformat(timespec="seconds")
    agent.call("POST", "/loss-prevention/agents/heartbeat", {
        "agentVersion": "demo-replay", "modelVersion": "none (replayed observations)", "clockDriftMs": 12,
        "diskFreeBytes": 400_000_000_000, "diskTotalBytes": 1_000_000_000_000, "queueLength": 0,
        "observedThrough": now, "cameras": [{"cameraId": c, "streamState": "ok", "fps": 10, "lastFrameAt": now,
                                             "observedThrough": now} for c in STATE["cameras"].values()]})


def backdate():
    """Dates the demo's agent, cameras, zones and meters to 08:00 the day before the pilot's first day.

    A zone or meter only judges time after it was created, so a replayed pilot needs its install dated before the
    replayed days. This is the one step that is not an API call: it runs psql as postgres on LP_DEMO_DB (default
    erp) and changes only the rows whose ids are in the state file.
    """
    if "start" not in STATE:
        STATE["start"] = (date.today() - timedelta(days=events.DAYS)).isoformat()
        save()
    at = f"{date.fromisoformat(STATE['start']) - timedelta(days=1)} 05:00:00+00"
    q = lambda ids: ",".join("'" + i + "'" for i in ids)
    sql = f"""
UPDATE "LpAgent" SET "createdAt" = '{at}', "enrolledAt" = '{at}' WHERE id = '{STATE['agent']['id']}';
UPDATE "LpCamera" SET "createdAt" = '{at}' WHERE id IN ({q(STATE['cameras'].values())});
UPDATE "LpZone" SET "createdAt" = '{at}' WHERE id IN ({q(STATE['zones'].values())});
UPDATE "LpMeasurementDevice" SET "createdAt" = '{at}' WHERE id IN ({q(STATE['meters'].values())});
"""
    db = os.environ.get("LP_DEMO_DB", "erp")
    out = subprocess.run(["sudo", "-u", "postgres", "psql", "-v", "ON_ERROR_STOP=1", "-d", db, "-c", sql],
                         capture_output=True, text=True)
    if out.returncode:
        raise SystemExit(out.stderr)
    log(f"  installed at {at} on {db}: {out.stdout.split()}")


def reconcile(s):
    heartbeat(agent_session())
    start = date.fromisoformat(STATE["start"])
    end = start + timedelta(days=events.DAYS - 1)
    for engine in ENGINES:
        res = s.call("POST", "/loss-prevention/runs/replay", {"engine": engine, "from": start.isoformat(),
                                                             "to": end.isoformat(),
                                                             "branchId": STATE["ids"]["branch"]}, key=True)
        log(f"  replay {engine}: {str(res)[:160]}")
    log("  insights:", s.call("POST", "/loss-prevention/insights/recompute",
                              {"from": start.isoformat(), "to": end.isoformat(),
                               "branchId": STATE["ids"]["branch"]}))


def report(s):
    cases = rows(s.call("GET", "/loss-prevention/cases?pageSize=200"))
    by = {}
    for c in cases:
        by.setdefault(c.get("ruleCode") or c.get("rule"), []).append(c)
    log(f"  {len(cases)} cases")
    for code in sorted(by, key=str):
        log(f"    {code}: {len(by[code])}")
    log("  planted:", ", ".join(sorted({k for k, _ in STATE.get("planted", [])})))


def main():
    check_target(sys.argv)
    steps = sys.argv[3:] or STEPS
    s = admin()
    for step in steps:
        log(f"== {step}")
        if step == "setup":
            setup.run(s)
        elif step == "backdate":
            backdate()
        elif step == "events":
            heartbeat(agent_session())
            events.run(s, agent_session())
        elif step == "reconcile":
            reconcile(s)
        elif step == "report":
            report(s)
        else:
            raise SystemExit(f"Unknown step {step}; steps: {' '.join(STEPS)}")


if __name__ == "__main__":
    main()
