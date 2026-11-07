"""GreenRock quarry demo seeder.

    python run.py --dry-run                        # show target and phases, write nothing
    python run.py --confirm GRC [phase ...]  # seed (a re-run skips finished steps)

Local stack only. Every write goes through the running API (real postings, stock, approvals)."""
import argparse
import importlib

import core

PHASES = ["masters", "quarry", "crushing", "quality", "pricing", "sales", "fleetops", "extras", "fix_stock", "verify"]

if __name__ == "__main__":
    p = argparse.ArgumentParser()
    p.add_argument("--dry-run", action="store_true")
    p.add_argument("--confirm")
    p.add_argument("phases", nargs="*")
    args = p.parse_args()
    todo = args.phases or PHASES
    print("target", core.BASE, "tenant", core.ORG, "phases", todo, "steps done", len(core.STATE.get("steps", {})))
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
