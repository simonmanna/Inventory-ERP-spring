-- V3: the "fleet" feature (fleet & logistics module, ERP ADR-S009) is sold with the plans that already carry the
-- vertical modules (rental, manufacturing, repair): enterprise-yearly and perpetual-onprem. Additive: plans an
-- operator edited keep their edits; the key is only appended where it is missing.

UPDATE plan
SET features = features || '["fleet"]'::jsonb
WHERE code IN ('enterprise-yearly', 'perpetual-onprem')
  AND NOT features ? 'fleet';
