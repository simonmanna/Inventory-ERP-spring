# Inventory-ERP-spring

POS-CAFE ERP rebuilt on **Spring Boot 4.1 / Java 25 / PostgreSQL 16**: café & restaurant POS
(tables, tabs, split bills, kitchen display, receipts), cash management, inventory with costing,
purchasing, accounting, invoicing, expenses, CRM, tasks, HR/payroll, manufacturing, fixed assets,
rental, repair, messaging, offline sync and encrypted backups.

The React web client (`web/`) is the unchanged POS-CAFE client; the Spring API is a wire-compatible
replacement for the original NestJS API, which is kept read-only in `reference/nest-api/` as the
behavioural specification.

> **Status:** foundation (phase P0) complete — schema, entities, build, CI, docs. Feature modules
> are built phase by phase; see [`docs/PORT_STATUS.md`](docs/PORT_STATUS.md).

## Quick start

```bash
docker compose up -d db                       # Postgres 16 on localhost:5434
cd backend && ./gradlew bootRun               # API  http://localhost:3001/api/v1  (docs: /api/docs)
pnpm install && pnpm web:dev                  # Web  http://localhost:5173
```

Full stack in containers: `cp .env.example .env` (fill secrets) then `docker compose up -d --build`
→ http://localhost:8080.

Requirements: Docker, Node 22+, pnpm 10, any JDK 17+ to launch Gradle (JDK 25 toolchain is
downloaded automatically).

## Documentation

| Doc | For |
|---|---|
| [AGENTS.md](AGENTS.md) / [CLAUDE.md](CLAUDE.md) | AI agents and developers: rules, workflow, definition of done |
| [docs/BUILD_GUIDE.md](docs/BUILD_GUIDE.md) | phases P0–P8, production bar, release gate |
| [docs/CONTRACT.md](docs/CONTRACT.md) | the wire contract the web/Android clients rely on |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | modules, layering, transactions, concurrency, tenancy |
| [docs/DATABASE.md](docs/DATABASE.md) | schema baseline, entity generation, migrations, RLS, seed |
| [docs/SECURITY.md](docs/SECURITY.md) | auth, authorization, tenant isolation, hardening |
| [docs/TESTING.md](docs/TESTING.md) | test strategy and the Nest↔Spring parity harness |
| [docs/OPERATIONS.md](docs/OPERATIONS.md) | deploy, upgrade, cut-over from Nest, backups, monitoring |
| [docs/spec/](docs/spec) | per-module specs + generated route/DTO/error inventories (1,118 routes) |
| [docs/phases/](docs/phases) | self-contained briefs for each build phase |
| [docs/legacy/](docs/legacy) | original POS-CAFE docs, ADRs, sync protocol, runbooks |

## Repository layout

```
backend/            Spring Boot application (Gradle, com.inventoryerp.*)
web/                React 18 + Vite client (from POS-CAFE)
packages/shared/    TS contract constants (permissions, events, enums) shared with web
tools/              generators: prisma2jpa, contracts2java, extract-routes, specs
contract-tests/     parity harness (Nest vs Spring)
infra/docker/       Dockerfiles + nginx
reference/          read-only NestJS source of truth (+ Prisma schema/migrations/seed, e2e)
docs/               specifications and guides
```

## Provenance

Copied from the POS-CAFE working tree on 2026-10-19 (latest commit `17126b5`, including
uncommitted work in progress at that time). The original repository is unchanged.
