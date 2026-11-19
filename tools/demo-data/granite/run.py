"""Granite demo for one organisation, through the running API only (no SQL).

Usage:  python3 run.py --confirm DEMO [catalogue] [services] [documents] [processing]
With no step names all four run, in that order. Back up the database first. Safe to re-run: products, services, steps
and the route are skipped by code; documents, jobs and sales are recorded in state.<ORG>.json and not repeated.
Environment: ERP_BASE (default http://127.0.0.1:3001/api/v1), GRANITE_ORG (DEMO), GRANITE_EMAIL (admin@demo.test),
GRANITE_PASSWORD (Admin@123, the local seed default).
"""
import sys

import api
import catalogue
import documents
import processing
import services

STEPS = {"catalogue": catalogue, "services": services, "documents": documents, "processing": processing}


def main():
    api.login(sys.argv)
    chosen = sys.argv[3:] or list(STEPS)
    unknown = [s for s in chosen if s not in STEPS]
    if unknown:
        raise SystemExit(f"Unknown steps {unknown}; choose from {list(STEPS)}")
    ctx = api.Ctx()
    for name in chosen:
        api.log(f"== {name}")
        STEPS[name].run(ctx)
    api.log("done")


if __name__ == "__main__":
    main()
