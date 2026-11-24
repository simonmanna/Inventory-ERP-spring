# Quality-control demo (tenant DEMO, ADR-S087)

A concrete-block plant's quality control, through the running API only (no SQL):

- teams "Block Plant QC" and "Receiving Inspection", the worksheet template "Block curing checklist";
- control points on the block / paver products (found by name: block, paver, brick, kerb, slab; else the first
  stockable products): compressive strength (MPa, I-MR SPC), block height (mm, X̄-R in subgroups of 5), a blocking
  visual pass/fail check, a curing worksheet on work orders, a delivery load photo and, when a cement product exists,
  a random 25 % cement-bag weight check on receipts (pre-receipt);
- ~95 executed checks with realistic measurements and failures, so the control charts, capability and Pareto have
  data, plus 4 pending checks for the operator queue;
- tags, root causes and 5 quality alerts across New / Confirmed / Action Proposed / Solved, one escalated to an NCR in
  Quality.

```bash
# back up the database first
cd tools/demo-data/quality-control
QC_PASSWORD='<admin password>' python3 seed.py --confirm DEMO
```

| Variable | Default |
|---|---|
| `ERP_BASE` | `http://127.0.0.1:3001/api/v1` (only localhost is accepted; run it on the server) |
| `QC_ORG` | `DEMO` |
| `QC_EMAIL` | `admin@demo.test` |
| `QC_PASSWORD` | `Admin@123` (the local seed default) |

Re-running is safe: teams, the template, control points, tags and root causes are matched by name; checks and alerts
are recorded in `state.<ORG>.json` and skipped. Delete that file only together with the data. Execution dates are the
run date (the API sets them), so the pass-rate trend shows one period.

Note: the control points are active. New production orders, work orders and deliveries of those products get
generated checks, and the visual check blocks completion until it is done (the point of the demo). Deactivate a
point under Quality Control → Control Points to stop that.
