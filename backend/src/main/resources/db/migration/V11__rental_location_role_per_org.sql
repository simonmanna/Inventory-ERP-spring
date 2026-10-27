-- V11: rental location roles are per organization.
--
-- The Nest schema declares `RentalLocationConfig.role @unique` on top of `@@unique([organizationId, role])`, so only
-- the first organization that bootstraps its rental locations can ever own the `stock` / `out` / ... rows; every
-- other tenant's first checkout fails on the global index. The per-org composite unique index stays and is what the
-- bootstrap upserts against. Deviation recorded in docs/spec/rental.md.

DROP INDEX IF EXISTS "RentalLocationConfig_role_key";
