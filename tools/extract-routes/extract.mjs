#!/usr/bin/env node
// Extracts the full HTTP surface of the NestJS reference API (reference/nest-api/src) into:
//   docs/spec/routes.json             machine-readable inventory (every route)
//   docs/spec/generated/<module>.md   per-module route tables, DTO field tables, verbatim error messages
// The hand-written docs/spec/<module>.md files link to the generated tables. Re-run after any
// change to the reference source:  node tools/extract-routes/extract.mjs
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const apiSrc = path.join(root, 'reference/nest-api/src');
const outDir = path.join(root, 'docs/spec/generated');
fs.mkdirSync(outDir, { recursive: true });

// ---- permission constant resolution (PERMISSIONS.a.b -> 'x:y') ----
const tmp = path.join(os.tmpdir(), `extract-perms-${process.pid}.mts`);
fs.copyFileSync(path.join(root, 'packages/shared/src/permissions.ts'), tmp);
const { PERMISSIONS } = await import(pathToFileURL(tmp).href);
fs.rmSync(tmp, { force: true });
const resolvePerm = (expr) => {
  const s = expr.trim();
  const lit = s.match(/^['"`]([^'"`]+)['"`]$/);
  if (lit) return lit[1];
  const m = s.match(/^PERMISSIONS((?:\.\w+)+)$/);
  if (!m) return s;
  let node = PERMISSIONS;
  for (const k of m[1].slice(1).split('.')) node = node?.[k];
  return typeof node === 'string' ? node : s;
};

// ---- helpers ----
function walk(dir, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walk(p, out);
    else if (e.name.endsWith('.ts') && !e.name.endsWith('.spec.ts')) out.push(p);
  }
  return out;
}
function walkAll(dir, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name);
    if (e.isDirectory()) walkAll(p, out);
    else if (e.name.endsWith('.ts')) out.push(p);
  }
  return out;
}
const rel = (p) => path.relative(root, p).split(path.sep).join('/');
function moduleOf(file) {
  const r = path.relative(apiSrc, file).split(path.sep);
  if (r[0] === 'modules') return r[1] === 'fixed-asset' ? 'fixedasset' : r[1];
  return 'kernel'; // kernel/, health/, observability/, app-level
}
const joinPath = (...parts) =>
  '/' + parts.flatMap((p) => String(p ?? '').split('/')).filter(Boolean).join('/');
function strArgs(s) {
  // Decorator argument: '' | 'x' | ['a','b'] | { path: 'x' }
  if (!s || !s.trim()) return [''];
  const arr = [...s.matchAll(/['"`]([^'"`]*)['"`]/g)].map((m) => m[1]);
  return arr.length ? arr : [''];
}
// Split "(...)" content honoring nested parens/brackets.
function balanced(src, openIdx) {
  let depth = 0;
  for (let i = openIdx; i < src.length; i++) {
    const c = src[i];
    if (c === '(' || c === '[' || c === '{') depth++;
    else if (c === ')' || c === ']' || c === '}') {
      depth--;
      if (depth === 0) return src.slice(openIdx + 1, i);
    } else if (c === "'" || c === '"' || c === '`') {
      const end = src.indexOf(c, i + 1);
      if (end > 0) i = end;
    }
  }
  return '';
}

// Remove // and /* */ comments (string-aware) so decorators quoted in docs are ignored.
function stripComments(src) {
  let out = '';
  for (let i = 0; i < src.length; i++) {
    const c = src[i];
    const n = src[i + 1];
    if (c === "'" || c === '"' || c === '`') {
      let j = i + 1;
      while (j < src.length && src[j] !== c) j += src[j] === '\\' ? 2 : 1;
      out += src.slice(i, j + 1);
      i = j;
    } else if (c === '/' && n === '/') {
      while (i < src.length && src[i] !== '\n') i++;
      out += '\n';
    } else if (c === '/' && n === '*') {
      const end = src.indexOf('*/', i + 2);
      const chunk = src.slice(i, end < 0 ? src.length : end + 2);
      out += chunk.replace(/[^\n]/g, '');
      i = end < 0 ? src.length : end + 1;
    } else out += c;
  }
  return out;
}
const sources = new Map();
const read = (f) => {
  if (!sources.has(f)) sources.set(f, stripComments(fs.readFileSync(f, 'utf8')));
  return sources.get(f);
};

// ---- DTO classes (class-validator) across the whole source tree ----
const files = walk(apiSrc);
const dtos = {};
for (const f of files) {
  const src = read(f);
  for (const m of src.matchAll(/(?:export\s+)?class\s+(\w+)(?:\s+extends\s+([\w<>, ()]+?))?\s*\{/g)) {
    const name = m[1];
    if (!/(Dto|Query|Input|Body|Params|Request)$/i.test(name) && !/Dto/.test(name)) continue;
    const body = balanced(src, m.index + m[0].length - 1);
    const fields = [];
    // Collapse decorators then read "name?: type;" lines.
    const lines = body.split('\n');
    let pending = [];
    for (let raw of lines) {
      const line = raw.trim();
      if (!line || line.startsWith('//') || line.startsWith('*') || line.startsWith('/*')) continue;
      const decos = [...line.matchAll(/@(\w+)(\(([^()]|\([^()]*\))*\))?/g)].map((d) => d[0]);
      const rest = line.replace(/@(\w+)(\(([^()]|\([^()]*\))*\))?/g, '').trim();
      pending.push(...decos);
      const fm = rest.match(/^(?:readonly\s+)?(\w+)(\?|!)?\s*:\s*([^;=]+)/);
      if (fm) {
        const decorators = pending.filter((d) => !/^@Api/.test(d));
        fields.push({
          name: fm[1],
          type: fm[3].trim(),
          optional: fm[2] === '?' || decorators.some((d) => d.startsWith('@IsOptional')),
          rules: decorators.filter((d) => !d.startsWith('@IsOptional')).join(' '),
        });
        pending = [];
      }
    }
    dtos[name] = { name, extends: m[2]?.trim(), file: rel(f), fields };
  }
}

// ---- controllers ----
const routes = [];
for (const f of files) {
  const src = read(f);
  if (!src.includes('@Controller(')) continue;
  for (const cm of src.matchAll(/@Controller\(/g)) {
    const base = strArgs(balanced(src, cm.index + '@Controller'.length))[0];
    const classMatch = src.slice(cm.index).match(/class\s+(\w+)/);
    if (!classMatch) continue;
    const classStart = cm.index + classMatch.index;
    // Class decorators: from the end of the previous top-level statement to the class keyword.
    const prevEnd = Math.max(src.lastIndexOf('\n}', cm.index), src.lastIndexOf(';\n', cm.index), 0);
    const header = src.slice(prevEnd, classStart);
    const classPerms = [...header.matchAll(/@RequirePermissions\(/g)].flatMap((p) =>
      balanced(header, p.index + '@RequirePermissions'.length).split(',').filter(Boolean).map(resolvePerm),
    );
    const classPublic = /@Public\(\)/.test(header);
    const classModule = header.match(/@RequiresModule\(\s*['"`]?([\w.-]+)/)?.[1];
    const bodyOpen = src.indexOf('{', classStart);
    const body = balanced(src, bodyOpen);
    const methodRe = /@(Get|Post|Put|Patch|Delete|Sse|All)\(/g;
    for (const mm of body.matchAll(methodRe)) {
      const verb = mm[1] === 'Sse' ? 'GET(SSE)' : mm[1].toUpperCase();
      const paths = strArgs(balanced(body, mm.index + mm[1].length + 1));
      // decorator block = from previous method end to the method signature
      const after = body.slice(mm.index);
      const sig = after.match(/\n\s*(?:public\s+|private\s+|protected\s+)?(?:async\s+)?(\w+)\s*\(/);
      if (!sig) continue;
      const decoBlock = after.slice(0, sig.index);
      const sigStart = mm.index + sig.index + sig[0].length - 1;
      const params = balanced(body, sigStart);
      const perms = [...decoBlock.matchAll(/@RequirePermissions\(/g)].flatMap((p) =>
        balanced(decoBlock, p.index + '@RequirePermissions'.length).split(',').map((s) => s.trim()).filter(Boolean).map(resolvePerm),
      );
      const bodyDto = params.match(/@Body\([^)]*\)\s*\w+\??\s*:\s*([\w<>[\]]+)/)?.[1];
      const queryDto = params.match(/@Query\(\)\s*\w+\??\s*:\s*([\w<>[\]]+)/)?.[1];
      const queryParams = [...params.matchAll(/@Query\(\s*['"`](\w+)['"`]/g)].map((q) => q[1]);
      const httpCode = decoBlock.match(/@HttpCode\(\s*(?:HttpStatus\.)?(\w+)/)?.[1];
      for (const p of paths) {
        routes.push({
          module: moduleOf(f),
          method: verb,
          path: joinPath('api/v1', base, p),
          handler: `${classMatch[1]}.${sig[1]}`,
          permissions: [...new Set([...classPerms, ...perms])],
          public: classPublic || /@Public\(\)/.test(decoBlock),
          idempotent: /@Idempotent\(/.test(decoBlock),
          requiresModule: decoBlock.match(/@RequiresModule\(\s*['"`]?([\w.-]+)/)?.[1] ?? classModule,
          httpCode: httpCode ?? (verb === 'POST' ? 'CREATED(201)' : 'OK(200)'),
          body: bodyDto,
          query: queryDto,
          queryParams,
          file: rel(f),
        });
      }
    }
  }
}

// ---- verbatim error messages per module (from services/controllers) ----
const errors = {};
for (const f of files) {
  const src = read(f);
  for (const m of src.matchAll(/new\s+(\w+Exception)\(\s*(['"`])((?:\\.|(?!\2).)*)\2/g)) {
    const mod = moduleOf(f);
    (errors[mod] ??= []).push({ type: m[1], message: m[3], file: rel(f) });
  }
}

// `school` is dead code in Nest (never imported by app.module) and is not ported.
for (let i = routes.length - 1; i >= 0; i--) if (routes[i].module === 'school') routes.splice(i, 1);
fs.writeFileSync(path.join(root, 'docs/spec/routes.json'), JSON.stringify(routes, null, 2));

// ---- markdown ----
const byModule = {};
for (const r of routes) (byModule[r.module] ??= []).push(r);
const esc = (s) => String(s ?? '').replace(/\|/g, '\\|').replace(/\n/g, ' ');
const totals = [];
for (const [mod, list] of Object.entries(byModule).sort()) {
  list.sort((a, b) => a.path.localeCompare(b.path) || a.method.localeCompare(b.method));
  const usedDtos = new Set();
  for (const r of list) {
    for (const d of [r.body, r.query]) {
      const n = d?.replace(/\[\]$/, '');
      if (n && dtos[n]) {
        usedDtos.add(n);
        if (dtos[n].extends) for (const p of dtos[n].extends.split(/[(), ]+/)) if (dtos[p]) usedDtos.add(p);
      }
    }
  }
  const out = [];
  out.push(`<!-- @generated by tools/extract-routes — do not edit. Re-run: node tools/extract-routes/extract.mjs -->`);
  out.push(`# ${mod} — generated route inventory`, '');
  out.push(`${list.length} routes. Source: \`reference/nest-api/src\`. Paths include the global prefix. "Idem" = \`@Idempotent()\` (see CONTRACT.md §6; the money-route regex in \`kernel/idempotency\` also applies).`, '');
  out.push('| # | Method | Path | Permissions | Public | Idem | Status | Body DTO | Query | Handler | Source |');
  out.push('|---|---|---|---|---|---|---|---|---|---|---|');
  list.forEach((r, i) =>
    out.push(
      `| ${i + 1} | ${r.method} | \`${esc(r.path)}\` | ${esc(r.permissions.map((p) => '`' + p + '`').join(' '))} | ${r.public ? 'yes' : ''} | ${r.idempotent ? 'yes' : ''} | ${esc(r.httpCode)} | ${esc(r.body)} | ${esc([r.query, ...r.queryParams].filter(Boolean).join(', '))} | ${esc(r.handler)} | \`${esc(r.file)}\` |`,
    ),
  );
  out.push('', '## DTOs (class-validator rules to mirror with Bean Validation)', '');
  for (const n of [...usedDtos].sort()) {
    const d = dtos[n];
    out.push(`### ${n}${d.extends ? ` extends ${esc(d.extends)}` : ''}`, '', `Source: \`${d.file}\``, '');
    if (!d.fields.length) {
      out.push('_(no own fields)_', '');
      continue;
    }
    out.push('| Field | Type | Optional | Rules |', '|---|---|---|---|');
    for (const fl of d.fields) out.push(`| ${fl.name} | \`${esc(fl.type)}\` | ${fl.optional ? 'yes' : ''} | ${esc(fl.rules)} |`);
    out.push('');
  }
  const errs = errors[mod] ?? [];
  out.push('## Error messages (copy verbatim)', '', `${errs.length} thrown messages with a literal string. Template literals keep their \`\${...}\` placeholders.`, '');
  out.push('| Exception | Message | Source |', '|---|---|---|');
  const seenMsg = new Set();
  for (const e of errs) {
    const k = e.type + e.message;
    if (seenMsg.has(k)) continue;
    seenMsg.add(k);
    out.push(`| ${e.type} | ${esc(e.message)} | \`${esc(e.file)}\` |`);
  }
  // Source + test inventory: every file to read while porting, and every spec to port.
  const modDirs = mod === 'kernel'
    ? ['kernel', 'health', 'observability'].map((d) => path.join(apiSrc, d))
    : [path.join(apiSrc, 'modules', mod === 'fixedasset' ? 'fixed-asset' : mod)];
  const all = modDirs.filter((d) => fs.existsSync(d)).flatMap((d) => walkAll(d));
  const srcFiles = all.filter((f) => !f.endsWith('.spec.ts'));
  const specFiles = [
    ...all.filter((f) => f.endsWith('.spec.ts')),
    ...walkAll(path.join(root, 'reference/nest-api/test')).filter((f) => f.endsWith('.spec.ts') && fs.readFileSync(f, 'utf8').match(new RegExp(`modules/${mod === 'fixedasset' ? 'fixed-asset' : mod}/|kernel/`.replace('kernel/', mod === 'kernel' ? 'kernel/' : '\\$never'))))
  ];
  out.push('', '## Source files to port', '', '| File | Lines |', '|---|---|');
  for (const f of srcFiles.sort()) out.push(`| \`${rel(f)}\` | ${fs.readFileSync(f, 'utf8').split('\n').length} |`);
  out.push('', '## Tests to port (Jest -> JUnit 5)', '');
  for (const f of [...new Set(specFiles)].sort()) out.push(`- \`${rel(f)}\``);
  fs.writeFileSync(path.join(outDir, `${mod}.md`), out.join('\n') + '\n');
  totals.push([mod, list.length]);
}
console.log(`routes: ${routes.length}, dto classes: ${Object.keys(dtos).length}`);
console.log(totals.map(([m, n]) => `${m}=${n}`).join(' '));
