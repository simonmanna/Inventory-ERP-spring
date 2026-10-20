// Exports the Nest DMS seed registry (reference/nest-api/src/modules/documents/dms.seed-data.ts)
// to backend/src/main/resources/dms/registry.json for the Java DmsRegistrySeeder.
// Run: node tools/dms-registry/export.mjs   (Node >= 22.18 — native TypeScript type stripping)
import { copyFileSync, mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', '..');
const src = join(root, 'reference', 'nest-api', 'src', 'modules', 'documents', 'dms.seed-data.ts');
const tmp = mkdtempSync(join(tmpdir(), 'dms-export-'));
const copy = join(tmp, 'dms.seed-data.ts');
copyFileSync(src, copy);
try {
  const m = await import(pathToFileURL(copy).href);
  const out = {
    lifecycles: m.DMS_LIFECYCLES,
    types: m.DMS_TYPE_POLICIES,
    relationTypes: m.DMS_RELATION_TYPES,
    templates: m.DMS_TEMPLATE_SEEDS,
    permissions: m.buildPermissionRows(),
  };
  const target = join(root, 'backend', 'src', 'main', 'resources', 'dms', 'registry.json');
  writeFileSync(target, JSON.stringify(out, null, 2) + '\n');
  console.log(`wrote ${target}: ${out.lifecycles.length} lifecycles, ${out.types.length} types, `
    + `${out.relationTypes.length} relation types, ${out.templates.length} templates, ${out.permissions.length} permissions`);
} finally {
  rmSync(tmp, { recursive: true, force: true });
}
