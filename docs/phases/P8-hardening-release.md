# Phase P8 — Production hardening and v1.0.0

---

Prerequisite: all modules done. Goal: pass the release gate in `docs/BUILD_GUIDE.md` §7.

## Deliverables
1. **Parity**: full harness run across all modules; resolve or document every diff
   (`contract-tests/allowed-diffs.json`).
2. **E2E**: `reference/e2e` sell loop + POS recovery suite (fake-indexeddb) green against Spring in CI.
3. **Security**: `docs/SECURITY.md` §9 checklist; OWASP dependency-check or Trivy job in CI; CycloneDX
   SBOM (`org.cyclonedx.bom` Gradle plugin); RLS enforced with non-superuser role on staging.
4. **Performance**: k6 scripts in `tests/load` (checkout, tab edits, tables SSE fan-out, Z-report,
   trial balance); Hibernate statistics assertions on hot paths (no N+1); index review with
   `pg_stat_statements`; JVM tuning; CDS/AOT cache for faster startup.
5. **Observability**: Grafana dashboard JSON + Prometheus alert rules in `infra/monitoring`.
6. **Operations**: upgrade + rollback scripts (`scripts/upgrade.sh`, `scripts/upgrade.ps1`), cut-over
   rehearsal on a production DB copy (`docs/OPERATIONS.md` §5), DR drill.
7. **Quality gates**: JaCoCo ≥ 80 % on application packages enforced; PIT on POS/accounting/inventory
   calculators; Spotless formatting check in CI.
8. **Docs**: README, OPERATIONS, runbooks, API docs (springdoc) reviewed; CHANGELOG; release notes.
9. Tag `v1.0.0`; publish Docker images (GHCR) via a release workflow.

## Exit criteria
Release gate (BUILD_GUIDE §7) fully green and signed off by the owner.
