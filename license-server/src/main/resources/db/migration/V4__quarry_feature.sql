-- V4: the "quarry" feature (quarry & extraction module, ERP ADR-S012) is sold with the plans that already carry the
-- vertical modules (rental, manufacturing, repair, fleet): enterprise-yearly and perpetual-onprem. Additive: plans an
-- operator edited keep their edits; the key is only appended where it is missing.

UPDATE plan
SET features = features || '["quarry"]'::jsonb
WHERE code IN ('enterprise-yearly', 'perpetual-onprem')
  AND NOT features ? 'quarry';
