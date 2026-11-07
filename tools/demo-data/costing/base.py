"""Shared plumbing for the costing demo: reuses the GreenRock guarded API client, with its own state file."""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
os.environ.setdefault("GREENROCK_ORG", "MENA")
sys.path.append(os.path.join(HERE, "..", "greenrock"))
import core  # noqa: E402

ORG = core.ORG
core.STATE_FILE = os.path.join(HERE, f"state.{ORG}.json")
core.LOG_FILE = os.path.join(HERE, "seed-log.jsonl")
core.STATE.clear()
if os.path.exists(core.STATE_FILE):
    core.STATE.update(json.load(open(core.STATE_FILE)))

A, C, once, ids, save, rows, ApiError = core.A, core.C, core.once, core.ids, core.save, core.rows, core.ApiError
TODAY = "2026-11-06"
_mena = json.load(open(os.path.join(HERE, "..", "mena", "state.json")))["MENA"]
UOM, LOC, PCAT, PARTNER, ACCOUNT = _mena["uom"], _mena["location"], _mena["pcat"], _mena["partner"], _mena["account"]
FACT, MAIN = LOC["HQ-FACT"], LOC["HQ-MAIN"]


def pid(code):
    return ids("product")[code]


def sup(code):
    return ids("partner").get(code) or PARTNER[code]
