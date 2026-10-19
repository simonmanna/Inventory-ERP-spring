#!/usr/bin/env node
// Generates the accounting catalogs consumed by the Spring accounting module as JSON resources:
//   packages/shared/src/accounting/account-category.ts -> contracts/account-categories.json, report-sections.json
//   packages/shared/src/accounting/account-mappings.ts -> contracts/account-mappings.json
//   reference/nest-api/src/modules/accounting/coa/coa-template.ts -> contracts/coa-template.json
// Requires Node >= 22.18 (built-in TypeScript type stripping). Run: node tools/contracts2java/accounting.mjs
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const resDir = path.join(root, 'backend/src/main/resources/contracts');
const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), 'accounting-contracts-'));

function stage(file, name, rewrite = (s) => s) {
  const out = path.join(tmpDir, name);
  fs.writeFileSync(out, rewrite(fs.readFileSync(file, 'utf8')));
  return out;
}

try {
  const category = stage(path.join(root, 'packages/shared/src/accounting/account-category.ts'), 'account-category.mts');
  const mappings = stage(path.join(root, 'packages/shared/src/accounting/account-mappings.ts'), 'account-mappings.mts');
  const coa = stage(path.join(root, 'reference/nest-api/src/modules/accounting/coa/coa-template.ts'), 'coa-template.mts',
    (s) => s.replace(/from '@erp\/shared'/, "from './account-category.mts'"));

  const cat = await import(pathToFileURL(category).href);
  const map = await import(pathToFileURL(mappings).href);
  const tpl = await import(pathToFileURL(coa).href);

  const writeJson = (name, value) => fs.writeFileSync(path.join(resDir, name), `${JSON.stringify(value, null, 2)}\n`);
  writeJson('account-categories.json', cat.ACCOUNT_CATEGORY_SEED.map((c) => ({
    ...c, systemVersion: cat.categorySystemVersion(c),
  })));
  writeJson('report-sections.json', cat.REPORT_SECTION_DEFS);
  writeJson('account-mappings.json', {
    groups: map.ACCOUNT_MAPPING_GROUPS,
    groupLabels: map.ACCOUNT_MAPPING_GROUP_LABELS,
    registry: map.ACCOUNT_MAPPING_REGISTRY,
  });
  writeJson('coa-template.json', {
    accounts: tpl.COA_TEMPLATE,
    mappings: tpl.COA_MAPPINGS,
    journals: tpl.COA_JOURNALS,
  });
  console.log('accounting catalogs written to', path.relative(root, resDir));
} finally {
  fs.rmSync(tmpDir, { recursive: true, force: true });
}
