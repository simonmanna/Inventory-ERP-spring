#!/usr/bin/env node
// Dashboards as code for the embedded Superset (ADR-S078). Creates or updates, idempotently:
//   - the "ERP bi" database connection (the superset_reader login, read-only, `bi` schema only),
//   - a dataset per `bi` view,
//   - one dashboard per tools/superset-provision/dashboards/<module>.json, with its charts and layout,
//   - the embedded config of each dashboard, then prints the SUPERSET_DASHBOARD_<MODULE>=<uuid> lines for the API.
//
//   node tools/superset-provision/provision.mjs                      provision
//   node tools/superset-provision/provision.mjs --check-isolation    also prove the guest-token row-level security
//
// Environment: SUPERSET_URL, SUPERSET_ADMIN_USERNAME, SUPERSET_ADMIN_PASSWORD, BI_DB_URI
// (postgresql+psycopg2://superset_reader:<password>@<host>:<port>/<db>), SUPERSET_EMBED_ALLOWED_DOMAINS (space
// separated ERP origins; empty = any origin may frame), BI_CHECK_ORGS (two organization ids, for --check-isolation).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const env = (name, fallback) => {
  const v = process.env[name] ?? fallback;
  if (v === undefined || v === '') throw new Error(`${name} must be set`);
  return v;
};
const BASE = env('SUPERSET_URL').replace(/\/+$/, '');
const DB_NAME = 'ERP bi';
const BI_VIEWS = ['sales_lines', 'sales_invoices', 'purchase_lines', 'vendor_bills', 'stock_on_hand', 'stock_movements',
  'journal_lines', 'payments', 'pos_sales', 'pos_sale_lines', 'pos_cash_sessions', 'pos_voided_items', 'hr_employees',
  'hr_attendance', 'hr_leave', 'hr_payroll_costs', 'production_orders', 'production_materials', 'production_variances'];
const PROBE_PREFIX = '[isolation probe] ';

// ---- HTTP -----------------------------------------------------------------------------------------------------------

let bearer = '';
let csrf = '';
let cookie = '';

async function request(method, url, body, headers = {}) {
  const res = await fetch(`${BASE}${url}`, {
    method,
    headers: {
      'Content-Type': 'application/json',
      Referer: BASE,
      ...(bearer ? { Authorization: `Bearer ${bearer}` } : {}),
      ...(csrf && method !== 'GET' ? { 'X-CSRFToken': csrf } : {}),
      ...(cookie ? { Cookie: cookie } : {}),
      ...headers,
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const setCookie = res.headers.getSetCookie?.() ?? [];
  if (setCookie.length) cookie = setCookie.map((c) => c.split(';')[0]).join('; ');
  const text = await res.text();
  let json;
  try { json = text ? JSON.parse(text) : {}; } catch { json = { raw: text.slice(0, 300) }; }
  return { status: res.status, json };
}

async function api(method, url, body, headers) {
  const r = await request(method, url, body, headers);
  if (r.status >= 400) throw new Error(`${method} ${url} -> HTTP ${r.status}: ${JSON.stringify(r.json).slice(0, 500)}`);
  return r.json;
}

async function login() {
  const r = await api('POST', '/api/v1/security/login', {
    username: env('SUPERSET_ADMIN_USERNAME', 'admin'), password: env('SUPERSET_ADMIN_PASSWORD'),
    provider: 'db', refresh: false,
  });
  bearer = r.access_token;
  csrf = (await api('GET', '/api/v1/security/csrf_token/')).result;
}

/** Rison for the `q` filter lists Superset's list endpoints take. */
function rison(value) {
  if (value === null) return '!n';
  if (typeof value === 'boolean') return value ? '!t' : '!f';
  if (typeof value === 'number') return String(value);
  if (Array.isArray(value)) return `!(${value.map(rison).join(',')})`;
  if (typeof value === 'object') return `(${Object.entries(value).map(([k, v]) => `${k}:${rison(v)}`).join(',')})`;
  const s = String(value);
  return /^[A-Za-z_][A-Za-z0-9_.\-]*$/.test(s) ? s : `'${s.replace(/!/g, '!!').replace(/'/g, "!'")}'`;
}
const q = (filters) => `q=${encodeURIComponent(rison({ filters, page_size: 100 }))}`;
const eq = (col, value) => ({ col, opr: 'eq', value });

// ---- Database and datasets ------------------------------------------------------------------------------------------

async function ensureDatabase() {
  const found = await api('GET', `/api/v1/database/?${q([eq('database_name', DB_NAME)])}`);
  const body = {
    database_name: DB_NAME, sqlalchemy_uri: env('BI_DB_URI'), expose_in_sqllab: true, allow_dml: false,
    allow_file_upload: false, allow_ctas: false, allow_cvas: false,
  };
  if (found.result?.length) {
    const id = found.result[0].id;
    await api('PUT', `/api/v1/database/${id}`, body);
    return id;
  }
  return (await api('POST', '/api/v1/database/', body)).id;
}

async function ensureDatasets(databaseId) {
  const ids = {};
  for (const view of BI_VIEWS) {
    const found = await api('GET', `/api/v1/dataset/?${q([eq('table_name', view), eq('schema', 'bi')])}`);
    const hit = found.result?.find((d) => d.database?.id === databaseId) ?? found.result?.[0];
    if (hit) {
      ids[view] = hit.id;
      await api('PUT', `/api/v1/dataset/${hit.id}/refresh`);
    } else {
      ids[view] = (await api('POST', '/api/v1/dataset/', { database: databaseId, schema: 'bi', table_name: view })).id;
    }
  }
  return ids;
}

// ---- Charts and dashboards ------------------------------------------------------------------------------------------

const metric = (sql, label) => ({ expressionType: 'SQL', sqlExpression: sql, label });

function chartParams(c, datasetId) {
  // `where` (optional): a SQL filter for this chart only, e.g. "status = 'completed'".
  const adhoc = c.where ? [{ expressionType: 'SQL', clause: 'WHERE', sqlExpression: c.where }] : [];
  const base = { datasource: `${datasetId}__table`, adhoc_filters: adhoc, time_range: 'No filter' };
  const m = metric(c.metric, c.name);
  switch (c.viz) {
    case 'big_number_total':
      return { viz_type: 'big_number_total', ...base, metric: m, header_font_size: 0.4, subheader_font_size: 0.15 };
    case 'bar_by_month':
      return { viz_type: 'echarts_timeseries_bar', ...base, x_axis: c.time, time_grain_sqla: 'P1M', metrics: [m],
        groupby: [], row_limit: 10000, show_legend: false };
    case 'pie':
      return { viz_type: 'pie', ...base, groupby: [c.groupby], metric: m, row_limit: 25, show_legend: true,
        label_type: 'key_percent' };
    case 'table':
      return { viz_type: 'table', ...base, query_mode: 'aggregate', groupby: [c.groupby], metrics: [m], row_limit: 15,
        order_desc: true, timeseries_limit_metric: m };
    case 'probe':
      return { viz_type: 'table', ...base, query_mode: 'aggregate', groupby: ['organizationId'],
        metrics: [metric('COUNT(*)', 'rows')], row_limit: 1000 };
    default:
      throw new Error(`Unknown viz "${c.viz}" in chart "${c.name}"`);
  }
}

async function ensureChart(dashboardId, c, datasetId) {
  const params = chartParams(c, datasetId);
  const body = {
    slice_name: c.name, viz_type: params.viz_type, datasource_id: datasetId, datasource_type: 'table',
    params: JSON.stringify(params), dashboards: [dashboardId],
  };
  const found = await api('GET', `/api/v1/chart/?${q([eq('slice_name', c.name),
    { col: 'dashboards', opr: 'rel_m_m', value: dashboardId }])}`);
  if (found.result?.length) {
    await api('PUT', `/api/v1/chart/${found.result[0].id}`, body);
    return found.result[0].id;
  }
  return (await api('POST', '/api/v1/chart/', body)).id;
}

function layout(title, placed) {
  const pos = {
    DASHBOARD_VERSION_KEY: 'v2',
    ROOT_ID: { type: 'ROOT', id: 'ROOT_ID', children: ['GRID_ID'] },
    HEADER_ID: { id: 'HEADER_ID', type: 'HEADER', meta: { text: title } },
    GRID_ID: { type: 'GRID', id: 'GRID_ID', children: [], parents: ['ROOT_ID'] },
  };
  let row = null;
  let used = 12;
  placed.forEach(({ id, name, width }, i) => {
    if (used + width > 12) {
      row = `ROW-${i}`;
      used = 0;
      pos.GRID_ID.children.push(row);
      pos[row] = { type: 'ROW', id: row, children: [], parents: ['ROOT_ID', 'GRID_ID'],
        meta: { background: 'BACKGROUND_TRANSPARENT' } };
    }
    const key = `CHART-${id}`;
    pos[row].children.push(key);
    pos[key] = { type: 'CHART', id: key, children: [], parents: ['ROOT_ID', 'GRID_ID', row],
      meta: { width, height: width <= 4 ? 25 : 50, chartId: id, sliceName: name } };
    used += width;
  });
  return pos;
}

async function ensureDashboard(spec, datasets, allowedDomains) {
  let dash = await request('GET', `/api/v1/dashboard/${spec.slug}`);
  const id = dash.status === 200 ? dash.json.result.id
    : (await api('POST', '/api/v1/dashboard/', { dashboard_title: spec.title, slug: spec.slug, published: true })).id;
  const placed = [];
  for (const c of spec.charts) {
    if (!datasets[c.dataset]) throw new Error(`${spec.module}: unknown dataset ${c.dataset}`);
    placed.push({ id: await ensureChart(id, c, datasets[c.dataset]), name: c.name, width: c.width ?? 6 });
  }
  // One hidden probe per dataset the dashboard reads: --check-isolation queries it with a guest token.
  const probes = {};
  for (const ds of new Set(spec.charts.map((c) => c.dataset))) {
    probes[ds] = await ensureChart(id, { name: `${PROBE_PREFIX}${spec.module} ${ds}`, viz: 'probe' }, datasets[ds]);
  }
  await api('PUT', `/api/v1/dashboard/${id}`, {
    dashboard_title: spec.title, slug: spec.slug, published: true,
    position_json: JSON.stringify(layout(spec.title, placed)),
    json_metadata: JSON.stringify({ color_scheme: 'supersetColors', refresh_frequency: 0, cross_filters_enabled: true }),
  });
  const embedded = await api('POST', `/api/v1/dashboard/${id}/embedded`, { allowed_domains: allowedDomains });
  return { id, uuid: embedded.result.uuid, probes, datasets };
}

// ---- Isolation check ------------------------------------------------------------------------------------------------

/** A guest token like the API's; `org` null = no clause, the control that proves a probe can see a leak. */
async function guestToken(uuid, org) {
  return (await api('POST', '/api/v1/security/guest_token/', {
    user: { username: 'isolation-check', first_name: 'Isolation', last_name: 'Check' },
    resources: [{ type: 'dashboard', id: uuid }],
    rls: org === null ? [] : [{ clause: `"organizationId" = '${org}'` }],
  })).token;
}

async function probeRows(chartId, dashboardId, datasetId, token) {
  const formData = { ...chartParams({ viz: 'probe' }, datasetId), slice_id: chartId, dashboardId };
  const saved = bearer;
  bearer = '';
  try {
    const r = await request('POST', '/api/v1/chart/data', {
      datasource: { id: datasetId, type: 'table' }, force: true, form_data: formData, result_format: 'json',
      result_type: 'full', queries: [{ columns: ['organizationId'], metrics: [metric('COUNT(*)', 'rows')],
        orderby: [], filters: [], extras: { having: '', where: '' }, row_limit: 1000, time_range: 'No filter' }],
    }, { 'X-GuestToken': token });
    if (r.status !== 200) throw new Error(`guest chart data HTTP ${r.status}: ${JSON.stringify(r.json).slice(0, 300)}`);
    return r.json.result[0].data;
  } finally {
    bearer = saved;
  }
}

async function checkIsolation(results) {
  const orgs = env('BI_CHECK_ORGS').split(/[\s,]+/).filter(Boolean);
  if (orgs.length < 2) throw new Error('BI_CHECK_ORGS needs two organization ids');
  let failures = 0;
  let vacuous = 0;
  for (const [module, r] of Object.entries(results)) {
    for (const [ds, chartId] of Object.entries(r.probes)) {
      const seen = {};
      for (const org of orgs) {
        const rows = await probeRows(chartId, r.id, r.datasets[ds], await guestToken(r.uuid, org));
        const foreign = rows.filter((row) => row.organizationId !== org);
        seen[org] = rows.reduce((n, row) => n + Number(row.rows), 0);
        if (foreign.length) {
          failures++;
          console.error(`FAIL ${module}/${ds}: a guest token for ${org} returned rows of`,
            foreign.map((x) => x.organizationId));
        }
      }
      const withRows = orgs.filter((org) => seen[org] > 0);
      if (withRows.length === 0) vacuous++;
      // Control: without a clause the same probe must see every organization that has rows, or it proves nothing.
      const control = await probeRows(chartId, r.id, r.datasets[ds], await guestToken(r.uuid, null));
      const controlOrgs = new Set(control.map((row) => row.organizationId));
      if (withRows.some((org) => !controlOrgs.has(org))) {
        failures++;
        console.error(`FAIL ${module}/${ds}: the unfiltered control did not see every organization; probe is blind`);
      }
      console.log(`  ${module}/${ds}: rows per organization ${JSON.stringify(seen)}, unfiltered control sees`
        + ` ${controlOrgs.size} organization(s)`);
    }
  }
  if (vacuous) console.warn(`WARN ${vacuous} dataset(s) had no rows for any checked organization: not proven there`);
  if (failures) {
    console.error(`Isolation check FAILED (${failures}). Do not expose these dashboards.`);
    process.exit(2);
  }
  console.log('Isolation check passed: every guest token saw its own organization only.');
}

// ---- main -----------------------------------------------------------------------------------------------------------

await login();
const databaseId = await ensureDatabase();
const datasets = await ensureDatasets(databaseId);
const allowedDomains = (process.env.SUPERSET_EMBED_ALLOWED_DOMAINS ?? '').split(/\s+/).filter(Boolean);
const results = {};
const specDir = path.join(here, 'dashboards');
for (const file of fs.readdirSync(specDir).filter((f) => f.endsWith('.json')).sort()) {
  const spec = JSON.parse(fs.readFileSync(path.join(specDir, file), 'utf8'));
  results[spec.module] = { ...(await ensureDashboard(spec, datasets, allowedDomains)), datasets };
  console.log(`dashboard ${spec.module}: id ${results[spec.module].id}`);
}
console.log('\n# API configuration (.env):');
for (const [module, r] of Object.entries(results)) console.log(`SUPERSET_DASHBOARD_${module.toUpperCase()}=${r.uuid}`);
if (process.argv.includes('--check-isolation')) {
  console.log('\nChecking tenant isolation through guest tokens...');
  await checkIsolation(results);
}
