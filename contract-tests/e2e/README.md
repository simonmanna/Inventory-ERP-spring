# POS sell-loop e2e against Spring

`pos-sell-loop.spec.ts` is `reference/e2e/pos-sell-loop.spec.ts` with one fix: the reference copy never sends an
`Idempotency-Key` to `POST /cash-sessions/open|close`, but Nest marks both `@Idempotent({ required: true })`
(`cash-session.controller.ts`), so the reference spec fails against Nest too. Nothing else changed.

Run (fresh database, `seed` profile, port 3101 so it does not clash with a local Nest on 3000/3001):

```bash
docker exec ierp-db psql -U erp -d postgres -c "CREATE DATABASE erp_e2e"
cd backend && ./gradlew bootJar --offline
PORT=3101 SPRING_PROFILES_ACTIVE=dev,seed JDBC_DATABASE_URL='jdbc:postgresql://localhost:5434/erp_e2e?stringtype=unspecified' java -jar build/libs/inventory-erp.jar
cd contract-tests/e2e && POS_API_BASE=http://localhost:3101 npx --yes vitest@4.1.9 run --config vitest.config.mjs
```

The suite is re-runnable against the same database (it reuses an open session and measures X-report deltas).

Last result (2026-10-20, P5): 5/5 green, twice in a row on the same database.
