/**
 * Collect every phrase passed to tx(...) in the web sources and report
 * coverage per locale. Source of truth for the phrase maps in
 * src/lib/i18n/phrases/<lng>.json.
 *
 *   node scripts/i18n-phrases.cjs            # coverage report
 *   node scripts/i18n-phrases.cjs --write    # add missing keys (value = "")
 *   node scripts/i18n-phrases.cjs --prune    # drop keys no longer in source
 *   node scripts/i18n-phrases.cjs --list ti  # print untranslated phrases
 *   node scripts/i18n-phrases.cjs --check    # CI gate (pnpm web:i18n): see below
 *   node scripts/i18n-phrases.cjs --report <file.md>  # completeness report (+ <file>.csv of untranslated phrases)
 *
 * --check fails on stale phrases, unknown {{placeholders}} in a translation, invalid / unreadable phrase or locale
 * files and keys missing from a keyed locale. An untranslated phrase is only a warning (owner decision 2026-11-16):
 * tx() falls back to the English source, so a partial translation is safe to ship.
 */
const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');
const SRC = path.join(ROOT, 'src');
const PHRASE_DIR = path.join(SRC, 'lib', 'i18n', 'phrases');
const LOCALES = ['ti', 'lg', 'sw'];

const files = [];
(function walk(d) {
  for (const e of fs.readdirSync(d, { withFileTypes: true })) {
    const p = path.join(d, e.name);
    if (e.isDirectory()) walk(p);
    else if (/\.(tsx|ts)$/.test(p)) files.push(p);
  }
})(SRC);

const phrases = new Set();
const RE = /\btx\(\s*('(?:[^'\\]|\\.)*'|"(?:[^"\\]|\\.)*")/g;
for (const f of files) {
  const src = fs.readFileSync(f, 'utf8');
  let m;
  while ((m = RE.exec(src))) {
    const lit = m[1];
    const body = lit.slice(1, -1);
    phrases.add(body.replace(/\\(['"\\])/g, '$1'));
  }
}
const all = [...phrases].sort((a, b) => a.localeCompare(b));

/** Parse a JSON file; an invalid file is a hard failure with a clear message (never a silent skip). */
function readJson(file) {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch (e) {
    console.error(`i18n check failed: ${path.relative(ROOT, file)} is not valid JSON (${e.message})`);
    process.exit(1);
  }
}

const listFor = process.argv.includes('--list') ? process.argv[process.argv.indexOf('--list') + 1] : null;
const write = process.argv.includes('--write');
const prune = process.argv.includes('--prune');

console.log('phrases in source:', all.length);
for (const lng of LOCALES) {
  const file = path.join(PHRASE_DIR, lng + '.json');
  const map = readJson(file);
  const missing = all.filter((p) => !map[p] || !String(map[p]).trim());
  const stale = Object.keys(map).filter((k) => !phrases.has(k));
  console.log(
    `  ${lng}: ${all.length - missing.length}/${all.length} translated` +
    ` (${Math.round(((all.length - missing.length) / all.length) * 100)}%)` +
    `, missing ${missing.length}, stale ${stale.length}`,
  );
  if (listFor === lng) missing.forEach((p) => console.log(p));
  if (write || prune) {
    const next = {};
    for (const p of all) next[p] = map[p] ?? '';
    if (!prune) for (const k of stale) next[k] = map[k];
    fs.writeFileSync(file, JSON.stringify(next, null, 2) + '\n');
  }
}
if (write || prune) console.log('phrase maps updated');

// --check: CI gate. Hard failures: a stale phrase, a translation introducing a {{var}} the source does not have, an
// invalid file, a keyed locale (locales/<lng>.json) missing a key present in en.json. Untranslated phrases: warning.
const LOCALE_DIR = path.join(SRC, 'lib', 'i18n', 'locales');
const flat = (o, pre = '') =>
  Object.entries(o).flatMap(([k, v]) => (v && typeof v === 'object' ? flat(v, pre + k + '.') : [pre + k]));

function audit() {
  const problems = [];
  const coverage = [];
  const untranslated = new Map(); // phrase -> Set(locale)
  const vars = (s) => s.match(/\{\{\w+\}\}/g) || [];
  for (const lng of LOCALES) {
    const map = readJson(path.join(PHRASE_DIR, lng + '.json'));
    let missing = 0;
    for (const p of all) {
      const v = map[p];
      if (typeof v !== 'string' && v !== undefined) problems.push(`${lng}: non-string translation for "${p}"`);
      if (!v || !String(v).trim()) {
        missing++;
        if (!untranslated.has(p)) untranslated.set(p, new Set());
        untranslated.get(p).add(lng);
      } else {
        const allowed = new Set(vars(p));
        for (const x of vars(v)) if (!allowed.has(x)) problems.push(`${lng}: unknown placeholder ${x} in "${p}"`);
      }
    }
    for (const k of Object.keys(map)) if (!phrases.has(k)) problems.push(`${lng}: stale phrase "${k}"`);
    coverage.push({ lng, translated: all.length - missing, missing });
  }
  const enKeys = flat(readJson(path.join(LOCALE_DIR, 'en.json')));
  for (const lng of LOCALES) {
    const keys = new Set(flat(readJson(path.join(LOCALE_DIR, lng + '.json'))));
    for (const k of enKeys) if (!keys.has(k)) problems.push(`${lng}: locales/${lng}.json missing key "${k}"`);
  }
  return { problems, coverage, untranslated };
}

const LANG_NAME = { ti: 'Tigrinya', lg: 'Luganda', sw: 'Swahili' };

if (process.argv.includes('--report')) {
  const out = process.argv[process.argv.indexOf('--report') + 1];
  if (!out) {
    console.error('--report needs a file path');
    process.exit(1);
  }
  const { coverage, untranslated } = audit();
  const pct = (n) => ((n / all.length) * 100).toFixed(1);
  const md = [
    '# Translation completeness',
    '',
    `Generated by \`node web/scripts/i18n-phrases.cjs --report\`. Phrases in source: **${all.length}** (every \`tx('…')\``,
    'literal under `web/src`). An untranslated phrase renders in English (`tx` falls back to the source text).',
    '',
    '| Locale | Translated | Missing | Coverage |',
    '|---|---:|---:|---:|',
    ...coverage.map((c) => `| ${LANG_NAME[c.lng]} (\`${c.lng}\`) | ${c.translated} | ${c.missing} | ${pct(c.translated)} % |`),
    `| Total missing (phrase × locale) | | ${coverage.reduce((a, c) => a + c.missing, 0)} | |`,
    '',
    `Distinct untranslated phrases: **${untranslated.size}**. The full list, one row per phrase with the locales that`,
    `still need it, is in \`${path.basename(out).replace(/\.md$/, '')}.csv\` (for translators: fill the locale columns`,
    'and merge into `web/src/lib/i18n/phrases/<lng>.json`).',
    '',
  ].join('\n');
  fs.writeFileSync(out, md);
  const csvEsc = (v) => (/[",\n]/.test(v) ? `"${v.replace(/"/g, '""')}"` : v);
  const csv = ['phrase,needs,' + LOCALES.join(',')]
    .concat([...untranslated.entries()].map(([p, set]) => [csvEsc(p), [...set].join(' '), ...LOCALES.map(() => '')].join(',')))
    .join('\n') + '\n';
  fs.writeFileSync(out.replace(/\.md$/, '') + '.csv', csv);
  console.log(`report written: ${out}`);
}

if (process.argv.includes('--check')) {
  const { problems, coverage } = audit();
  for (const c of coverage) {
    if (c.missing) console.warn(`  warning: ${c.lng}: ${c.missing} untranslated phrase(s) (English fallback)`);
  }
  if (problems.length) {
    problems.slice(0, 50).forEach((p) => console.error('  ' + p));
    console.error(`i18n check failed: ${problems.length} problem(s). Run with --prune / fix the translations, then re-check.`);
    process.exit(1);
  }
  console.log('i18n check passed' + (coverage.some((c) => c.missing) ? ' (with untranslated-phrase warnings)' : ''));
}
