# Cement-block PLM demo (tenant DEMO)

A concrete block plant's product lifecycle in Manufacturing → Product lifecycle: block and paver products with BOMs,
lifecycle states from concept to obsolete, change requests, change orders in every state, and controlled documents.
Everything goes through the running API (no SQL). There are no stock moves or GL postings.

```bash
# back up the database first
cd tools/demo-data/plm-blocks
PLM_ADMIN_PASSWORD='<admin password>' PLM_ENGINEER_PASSWORD='<10+ characters>' python3 run.py --confirm DEMO
```

| Variable | Default |
|---|---|
| `ERP_BASE` | `http://127.0.0.1:3001/api/v1` (only localhost is accepted; run it on the server) |
| `PLM_ORG` | `DEMO` |
| `PLM_ADMIN_EMAIL` | `admin@demo.test` (reviews requests, approves and applies changes) |
| `PLM_ADMIN_PASSWORD` | `Admin@123` (the local seed default) |
| `PLM_ENGINEER_EMAIL` | `ruth.achieng@demo.test` (created by the script) |
| `PLM_ENGINEER_PASSWORD` | required; the new engineer user's password |

The admin must be allowed to decide at the approval stage (the shipped stages have no named approvers; on the live
server `admin@demo.test` is the required approver of Engineering review). Nobody approves their own change, so the
engineer raises the requests and changes and the admin decides.

Re-running is safe. Categories, products, BOMs, the role, the user and PLM items are skipped when they exist. Every
other step is recorded in `state.<ORG>.json` and not repeated; delete that file only together with the data.

## What it creates

- **Categories** Concrete Blocks & Pavers, Block Plant Materials.
- **Materials** `BLK-RM-*`: cement (bag), quarry dust, chippings, pozzolana, pumice (tonne), plasticiser (litre).
- **Products and BOMs** (per batch of 100): hollow blocks 4, 6, 8 and 9 inch, solid 4 and 6 inch, a lightweight
  pumice block, an 80 mm paver (draft BOM) and a kerbstone (no BOM).
- **Role and user** Block Plant Engineering (PLM request and change, BOM create and update, read access) and
  Ruth Achieng.
- **Lifecycle** 6 released items; the 4 inch solid block phased out by a change order (replacement: 4 inch hollow);
  the 9 inch block phased out then obsolete (replacement: 8 inch); the lightweight block concept → design →
  prototype; the paver concept → design; the kerbstone a concept.
- **Requests** cement cost reduction (accepted and converted), chipped corners (accepted, under investigation),
  river sand (rejected), three-core 8 inch block raised by the admin (new).
- **Change orders**
  - 6 inch plasticiser mix, from the request: applied. Revision B, BOM v2 active, mix design released.
  - 8 inch pozzolana: waiting for approval in Engineering review.
  - 4 inch mould insert: approved and queued until the 1st of next month; the effectivity job applies it.
  - 4 inch solid block phase-out: applied.
  - Curing procedure revision 2: approved, ready to apply.
- **Documents** mix design, mould drawing, crushing test procedure, plasticiser datasheet, pozzolana certificate
  (released); curing procedure rev 2 and lightweight trial plan (drafts).

## Live server

Seeded into live DEMO on 2026-11-24 at 14:10 CET. The backup taken before seeding is
`/opt/inventory-erp/backups/pre-plm-blocks-demo-20261124-141010.dump`. The state file sits next to this README in
the `/home/ierp-plm` worktree. The numbers start at ECO-00005, ECR-00005 and DOC-00007, because the granite PLM demo
holds the earlier ones.

The 9 inch block is obsolete but its BOM is still active. Live's PLM does not archive a product's own BOMs when the
product becomes obsolete, and the delete route would hide the BOM's history.
