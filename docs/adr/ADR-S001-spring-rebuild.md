# ADR-S001: Rebuild the POS-CAFE API on Spring Boot with a frozen wire contract

- Status: accepted (2026-10-19)
- Supersedes: — (legacy ADRs in `docs/legacy/adr` still describe the domain)

## Context
POS-CAFE runs a NestJS 11 + Prisma API. The owner wants the same product on Spring Boot while the
original stays untouched. The React web client and the Android POS must keep working.

## Decision
1. Java 25, Spring Boot 4.1, Spring MVC on virtual threads, Spring Modulith, JPA/Hibernate 7, Flyway,
   PostgreSQL 16.
2. Freeze the HTTP contract of the Nest API (`docs/CONTRACT.md`); the Nest source is the spec.
3. Reuse the exact database schema (`V1__baseline.sql` from `pg_dump`) so the Spring API can take
   over existing databases and so both APIs can be compared on identical data.
4. Generate the persistence model and contract constants from the Prisma schema and
   `packages/shared` instead of hand-writing ~300 entities.
5. Deviations from Nest behaviour (approved):
   - access tokens no longer embed the permission list (smaller headers; permissions read from DB);
   - WhatsApp via Cloud API only (Baileys has no Java equivalent);
   - real S3 storage driver (Nest's was a stub);
   - Thymeleaf + OpenPDF instead of a headless-browser pool for document PDFs (pixel parity not
     required unless a later ADR says so).

## Consequences
- Phased port with a parity harness; every module must reach zero unexplained diffs.
- Schema changes after cut-over are Spring-owned Flyway migrations (V3+).
