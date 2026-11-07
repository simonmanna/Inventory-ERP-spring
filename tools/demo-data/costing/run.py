"""Costing demo seeder for MENA. Local stack only, API only.

    python run.py --dry-run
    python run.py --confirm MENA [phase ...]"""
import argparse
import importlib

from base import ORG, core

PHASES = ["cd_masters", "cd_scenario", "cd_verify"]

if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--dry-run", action="store_true")
    p.add_argument("--confirm")
    p.add_argument("phases", nargs="*")
    args = p.parse_args()
    todo = args.phases or PHASES
    print("target", core.BASE, "tenant", ORG, "phases", todo, "steps done", len(core.STATE.get("steps", {})))
    if args.dry_run:
        raise SystemExit(0)
    core.guard(args.confirm)
    for ph in todo:
        print("#", ph, flush=True)
        try:
            importlib.import_module(ph).run()
        except core.ApiError as e:
            print("FAILED in", ph, ":", e)
            raise SystemExit(1)
