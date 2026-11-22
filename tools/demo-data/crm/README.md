# CRM demo (tenant DEMO)

A stone-works sales team's pipeline, through the running API only (no SQL): sources, tags, lost reasons, the
"Stone Works Sales" team (monthly target UGX 120M), six fictional prospect customers, 15 opportunities across every
stage (3 won, 2 lost), 6 leads (one converted), planned and completed activities (overdue / today / upcoming),
3 quotations (one submitted) and 3 customer cases. Owners are `admin@`, `manager@` and `mary@` of the tenant's domain
(falling back to the login user).

```bash
# back up the database first
cd tools/demo-data/crm
CRM_PASSWORD='<admin password>' python3 seed.py --confirm DEMO
```

| Variable | Default |
|---|---|
| `ERP_BASE` | `http://127.0.0.1:3001/api/v1` (only localhost is accepted; run it on the server) |
| `CRM_ORG` | `DEMO` |
| `CRM_EMAIL` | `admin@demo.test` |
| `CRM_PASSWORD` | `Admin@123` (the local seed default) |

Re-running is safe: lookups, the team and customers are matched by name; deals, activities, quotations and cases are
recorded in `state.<ORG>.json` and skipped. Delete that file only together with the data. Won / lost dates are the
run date (the API sets them), so the monthly trend shows them in the current month.
