# Contract parity harness

Proves the Spring API answers exactly like the NestJS reference for the same request on the same data.

## Setup

1. Two Postgres 16 databases, both migrated with Prisma and seeded with the **Nest** seed so ids match:
   ```bash
   cd reference/nest-api && pnpm install            # or use the legacy POS-CAFE checkout
   DATABASE_URL=postgresql://erp:erp@localhost:5434/parity_nest   npx prisma migrate deploy && npx tsx prisma/seed.ts
   DATABASE_URL=postgresql://erp:erp@localhost:5434/parity_spring npx prisma migrate deploy && npx tsx prisma/seed.ts
   ```
   (Spring Flyway then baselines `parity_spring` at V2 and adds V3+.)
2. Start Nest on `:3000` against `parity_nest` (legacy repo: `pnpm dev:api`) and Spring on `:3001`
   against `parity_spring` (`./gradlew bootRun` with `JDBC_DATABASE_URL=...parity_spring?stringtype=unspecified`).
   Use the **same JWT secrets** in both.
3. Run: `NEST_URL=http://localhost:3000 SPRING_URL=http://localhost:3001 node contract-tests/run.mjs --module kernel`

## Scenarios

`scenarios/<module>-<area>.json`:
```json
{ "name": "...", "vars": { "k": "v" },
  "steps": [ { "name": "...", "method": "POST", "path": "/api/v1/...", "headers": {}, "body": {}, "capture": { "token": "$.accessToken" } } ] }
```
- `{{var}}` substitution in path/headers/body; `capture` stores a response value per side.
- `token` var is sent as bearer automatically. Money routes: set `"headers": { "Idempotency-Key": "{{someUuid}}" }`.
- Normalization replaces UUIDs, timestamps, tokens and volatile keys (`id`, `createdAt`, ...).
- Known, approved differences: `allowed-diffs.json` — `[{ "step": "login: success", "path": "$.body.accessToken", "reason": "..." }]`.

Record new scenarios from real traffic: export a HAR from the browser while using the web app against
Nest, then convert the relevant requests into steps (keep them deterministic).
