#!/usr/bin/env node
// Dashboards as code for the embedded Superset (ADR-S078). Creates or updates, idempotently:
//   - the "ERP bi" database connection (the superset_reader login, read-only, `bi` schema only),
//   - a dataset per `bi` view, with its time column,
//   - one dashboard per tools/superset-provision/dashboards/<module>.json: charts, tabs, headings, native filters
//     (period + select filters) and cross-filtering; charts no longer in the file are deleted,
//   - the embedded config of each dashboard, then prints the SUPERSET_DASHBOARD_<MODULE>=<uuid> lines for the API.
//
//   node tools/superset-provision/provision.mjs                        provision
//   node tools/superset-provision/provision.mjs --check-isolation      also prove the guest-token row-level security
//   node tools/superset-provision/provision.mjs --validate             only check the dashboard files (no Superset)
//   node tools/superset-provision/provision.mjs --write-config <file>  also write {"dashboards": {module: uuid}} for
//                                                                      the API (app.bi.superset.dashboards-file)
//
// Environment: SUPERSET_URL, SUPERSET_ADMIN_USERNAME, SUPERSET_ADMIN_PASSWORD, BI_DB_URI
// (postgresql+psycopg2://superset_reader:<password>@<host>:<port>/<db>), SUPERSET_EMBED_ALLOWED_DOMAINS (space
// separated ERP origins; empty = any origin may frame), BI_CHECK_ORGS (two organization ids, for --check-isolation).
//
// Dashboard file (see dashboards/sales.json):
//   { module, title, slug, filters: [{ name, column, dataset } | { name, type: "time" }],
//     charts: [{ name, viz, dataset, metric | metrics, ..., width, height, tab }] }
// `viz` is one of the builders in VIZ below; a chart with viz "heading" is a markdown block ({ text }).
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const args = process.argv.slice(2);
const flag = (name) => args.includes(name);
const option = (name) => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : undefined;
};
const env = (name, fallback) => {
  const v = process.env[name] ?? fallback;
  if (v === undefined || v === '') throw new Error(`${name} must be set`);
  return v;
};

const DB_NAME = 'ERP bi';
/** Every `bi` view and the time column the dashboard's period filter applies to (null: a snapshot, no period). */
const DATASETS = {
  sales_lines: 'issueDate', sales_invoices: 'issueDate', purchase_lines: 'orderDate', vendor_bills: 'issueDate',
  stock_on_hand: null, stock_movements: 'createdAt', journal_lines: 'postingDate', payments: 'paymentDate',
  pos_sales: 'tradingDate', pos_sale_lines: 'tradingDate', pos_cash_sessions: 'businessDate',
  pos_voided_items: 'voidDate', hr_employees: null, hr_attendance: 'attendanceDate', hr_leave: 'startDate',
  hr_payroll_costs: 'paymentDate', production_orders: 'completedDate', production_materials: 'completedDate',
  production_variances: 'varianceDate', expenses: 'expenseDate', crm_deals: 'createdAt',
};
const PROBE_PREFIX = '[isolation probe] ';
const COLOR_SCHEME = 'supersetColors';

// ---- Chart builders -------------------------------------------------------------------------------------------------

/** A metric: SQL text, or { sql, label }. */
const metricOf = (m, label) => (typeof m === 'string'
  ? { expressionType: 'SQL', sqlExpression: m, label }
  : { expressionType: 'SQL', sqlExpression: m.sql, label: m.label });
/** A column: a view column name, or { sql, label } for a computed one (e.g. the weekday of a timestamp). */
const columnOf = (c) => (typeof c === 'string' ? c : { expressionType: 'SQL', sqlExpression: c.sql, label: c.label });
const labelOf = (c) => (typeof c === 'string' ? c : c.label);
const columnsOf = (c) => (c === undefined ? [] : (Array.isArray(c) ? c : [c]).map(columnOf));
/** A series split: blank values get a label, else the series limit (an inner join on the value) drops them. */
const seriesOf = (c) => (c === undefined ? [] : (Array.isArray(c) ? c : [c]).map((x) => (typeof x === 'string'
  ? { expressionType: 'SQL', sqlExpression: `COALESCE(CAST("${x}" AS TEXT), 'Not set')`, label: x } : columnOf(x))));
const metricsOf = (c) => (c.metrics ? c.metrics.map((m, i) => metricOf(m, m.label ?? `${c.name} ${i + 1}`))
  : [metricOf(c.metric, c.label ?? c.name)]);
/** The time column a chart reads: its own `time`, else its dataset's; `time: false` opts out of the period filter. */
const timeOf = (c) => (c.time === false ? null : (c.time ?? DATASETS[c.dataset]));

const NUMBER = 'SMART_NUMBER';

/** Each builder returns the chart's viz_type-specific form data; `base` adds the datasource and the filters. */
const VIZ = {
  // A single figure.
  kpi: (c, m) => ({ viz_type: 'big_number_total', metric: m[0], subheader: c.subheader ?? '',
    header_font_size: 0.4, subheader_font_size: 0.15, y_axis_format: c.format ?? NUMBER }),
  // A figure with its monthly trend line and the change against the previous period.
  kpi_trend: (c, m) => ({ viz_type: 'big_number', metric: m[0], x_axis: timeOf(c), time_grain_sqla: c.grain ?? 'P1M',
    compare_lag: 1, compare_suffix: c.compareSuffix ?? 'vs previous month', show_trend_line: true,
    start_y_axis_at_zero: true, header_font_size: 0.4, subheader_font_size: 0.125, y_axis_format: c.format ?? NUMBER,
    rolling_type: 'None' }),
  // Time series: one line / area / column group per period, optionally split by `series`.
  line: (c, m) => timeseries('echarts_timeseries_line', c, m, { seriesType: 'smooth', markerEnabled: true,
    markerSize: 5 }),
  area: (c, m) => timeseries('echarts_area', c, m, { stack: c.series ? 'Stack' : null, opacity: 0.4,
    seriesType: 'smooth' }),
  bar_time: (c, m) => timeseries('echarts_timeseries_bar', c, m, { stack: c.series || c.stack ? 'Stack' : null }),
  // Ranked categories (top N), horizontal by default.
  bar: (c, m) => ({ viz_type: 'echarts_timeseries_bar', x_axis: columnOf(c.x), metrics: m,
    groupby: seriesOf(c.series), stack: c.series ? 'Stack' : null, orientation: c.vertical ? 'vertical' : 'horizontal',
    // Ranked by the first metric, or in the category's own order (aging buckets, pipeline stages) with order "x".
    // A horizontal bar chart draws its first category at the bottom, so ascending puts the largest on top.
    x_axis_sort: c.order === 'x' ? labelOf(c.x) : m[0].label, x_axis_sort_asc: c.order === 'x' || !c.vertical,
    x_axis_sort_series: 'sum', x_axis_sort_series_ascending: false,
    row_limit: c.limit ?? 10, show_value: !c.series, show_legend: !!c.series || m.length > 1,
    legendOrientation: 'top', legendType: 'scroll', rich_tooltip: true, y_axis_format: c.format ?? NUMBER,
    truncateXAxis: true, y_axis_title: '', x_axis_title: '' }),
  donut: (c, m) => ({ viz_type: 'pie', groupby: columnsOf(c.x), metric: m[0], row_limit: c.limit ?? 10,
    donut: true, innerRadius: 45, outerRadius: 72, show_labels: true, labels_outside: true, label_line: true,
    label_type: 'percent', show_labels_threshold: 4, show_legend: true, legendOrientation: 'right',
    legendType: 'scroll', number_format: c.format ?? NUMBER, sort_by_metric: true, show_total: true }),
  treemap: (c, m) => ({ viz_type: 'treemap_v2', groupby: columnsOf(c.x), metric: m[0], row_limit: c.limit ?? 50,
    show_labels: true, show_upper_labels: true, label_type: 'key_value', number_format: c.format ?? NUMBER }),
  sunburst: (c, m) => ({ viz_type: 'sunburst_v2', columns: columnsOf(c.x), metric: m[0], row_limit: c.limit ?? 200,
    show_labels: true, label_type: 'key', number_format: c.format ?? NUMBER, show_total: true }),
  heatmap: (c, m) => ({ viz_type: 'heatmap_v2', x_axis: columnOf(c.x), groupby: columnOf(c.y), metric: m[0],
    row_limit: 10000, linear_color_scheme: c.colors ?? 'superset_seq_1', show_values: true, show_legend: true,
    normalize_across: 'heatmap', sort_x_axis: 'alpha_asc', sort_y_axis: 'alpha_asc', xscale_interval: -1,
    yscale_interval: -1, left_margin: 'auto', bottom_margin: 'auto', value_bounds: [null, null],
    y_axis_format: c.format ?? NUMBER, x_axis_time_format: 'smart_date' }),
  funnel: (c, m) => ({ viz_type: 'funnel', groupby: columnsOf(c.x), metric: m[0], row_limit: c.limit ?? 10,
    sort_by_metric: !c.keepOrder, show_labels: true, show_tooltip_labels: true, label_type: 'key_value_percent',
    tooltip_label_type: 'key_value_percent', show_legend: false, number_format: c.format ?? NUMBER }),
  gauge: (c, m) => ({ viz_type: 'gauge_chart', metric: m[0], min_val: c.min ?? 0, max_val: c.max ?? 100,
    start_angle: 225, end_angle: -45, show_progress: true, round_cap: true, overlap: true, show_pointer: false,
    number_format: c.format ?? NUMBER, font_size: 15, split_number: 5, row_limit: 1 }),
  // Ranked table with in-cell bars.
  table: (c, m) => ({ viz_type: 'table', query_mode: 'aggregate', groupby: columnsOf(c.x), metrics: m,
    timeseries_limit_metric: m[0], order_desc: true, row_limit: c.limit ?? 15, show_cell_bars: true,
    color_pn: true, include_search: !!c.search, table_timestamp_format: '%Y-%m-%d',
    percent_metrics: c.share ? [m[0]] : [], conditional_formatting: [] }),
  // A plain list of rows, e.g. the most overdue invoices.
  list: (c) => ({ viz_type: 'table', query_mode: 'raw', all_columns: c.columns,
    order_by_cols: (c.orderBy ?? []).map(([col, asc]) => JSON.stringify([col, asc])), row_limit: c.limit ?? 20,
    include_search: true, table_timestamp_format: '%Y-%m-%d', server_pagination: false, page_length: 10 }),
  // Rows x columns with totals, e.g. category x month.
  pivot: (c, m) => ({ viz_type: 'pivot_table_v2', groupbyRows: columnsOf(c.rows), groupbyColumns: columnsOf(c.cols),
    metrics: m, aggregateFunction: 'Sum', rowTotals: true, colTotals: true, transposePivot: false,
    metricsLayout: 'COLUMNS', valueFormat: c.format ?? NUMBER, row_limit: 10000, rowOrder: 'value_z_to_a',
    colOrder: 'key_a_to_z', combineMetric: false }),
  probe: () => ({ viz_type: 'table', query_mode: 'aggregate', groupby: ['organizationId'],
    metrics: [metricOf('COUNT(*)', 'rows')], row_limit: 1000 }),
};

function timeseries(vizType, c, m, extra) {
  return { viz_type: vizType, x_axis: timeOf(c) ?? c.time, time_grain_sqla: c.grain ?? 'P1M', metrics: m,
    groupby: seriesOf(c.series), series_limit: c.seriesLimit ?? 8, series_limit_metric: m[0], order_desc: true,
    row_limit: 10000, show_legend: !!c.series || m.length > 1, legendOrientation: 'top', legendType: 'scroll',
    rich_tooltip: true, tooltipTimeFormat: 'smart_date', x_axis_time_format: 'smart_date', zoomable: false,
    y_axis_format: c.format ?? NUMBER, truncateYAxis: false, show_value: false, only_total: true,
    x_axis_title: '', y_axis_title: '', ...extra };
}

const DEFAULT_HEIGHT = { kpi: 22, kpi_trend: 30, gauge: 30, heading: 8 };

/** The full form data of one chart: its builder's fields, the datasource, its own WHERE and the period filter. */
function chartParams(c, datasetId) {
  const build = VIZ[c.viz];
  if (!build) throw new Error(`Unknown viz "${c.viz}" in chart "${c.name}"`);
  const adhoc = [];
  if (c.where) adhoc.push({ expressionType: 'SQL', clause: 'WHERE', sqlExpression: c.where });
  const t = c.viz === 'probe' ? null : timeOf(c);
  // The native period filter fills in this placeholder; "No filter" until a viewer picks a period.
  if (t) adhoc.push({ expressionType: 'SIMPLE', clause: 'WHERE', subject: t, operator: 'TEMPORAL_RANGE',
    comparator: 'No filter' });
  const m = c.viz === 'list' || c.viz === 'probe' ? [] : metricsOf(c);
  return { datasource: `${datasetId}__table`, adhoc_filters: adhoc, time_range: 'No filter',
    color_scheme: COLOR_SCHEME, ...build(c, m) };
}

// ---- Validation (no Superset needed) -------------------------------------------------------------------------------

function loadSpecs() {
  const specDir = path.join(here, 'dashboards');
  return fs.readdirSync(specDir).filter((f) => f.endsWith('.json')).sort()
    .map((file) => ({ file, ...JSON.parse(fs.readFileSync(path.join(specDir, file), 'utf8')) }));
}

function validate(spec) {
  const errors = [];
  const names = new Set();
  for (const c of spec.charts) {
    if (c.viz === 'heading') {
      if (!c.text) errors.push(`heading without text`);
      continue;
    }
    if (names.has(c.name)) errors.push(`duplicate chart name "${c.name}"`);
    names.add(c.name);
    if (!(c.dataset in DATASETS)) errors.push(`${c.name}: unknown dataset ${c.dataset}`);
    if (!VIZ[c.viz] || c.viz === 'probe') errors.push(`${c.name}: unknown viz ${c.viz}`);
    if (c.viz !== 'list' && !c.metric && !c.metrics) errors.push(`${c.name}: no metric`);
    if (['line', 'area', 'bar_time', 'kpi_trend'].includes(c.viz) && !timeOf(c) && !c.time) {
      errors.push(`${c.name}: a time series needs a time column`);
    }
    if (c.width && (c.width < 1 || c.width > 12)) errors.push(`${c.name}: width must be 1-12`);
  }
  const used = new Set(spec.charts.map((c) => c.dataset).filter(Boolean));
  for (const f of spec.filters ?? []) {
    if (f.type === 'time') continue;
    if (!used.has(f.dataset)) errors.push(`filter ${f.name}: dataset ${f.dataset} is not used by any chart`);
  }
  return errors.map((e) => `${spec.file}: ${e}`);
}

const specs = loadSpecs();
const problems = specs.flatMap(validate);
if (problems.length) {
  problems.forEach((p) => console.error(p));
  process.exit(1);
}
if (flag('--validate')) {
  console.log(`${specs.length} dashboard files, ${specs.reduce((n, s) => n + s.charts.length, 0)} charts: valid`);
  process.exit(0);
}

const BASE = env('SUPERSET_URL').replace(/\/+$/, '');

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
const q = (filters, extra = {}) => `q=${encodeURIComponent(rison({ filters, page_size: 100, ...extra }))}`;
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

/** A dataset per view: { view: { id, columns: Set<name> } }; the time column is marked temporal and the default. */
async function ensureDatasets(databaseId) {
  const out = {};
  for (const [view, timeColumn] of Object.entries(DATASETS)) {
    const found = await api('GET', `/api/v1/dataset/?${q([eq('table_name', view), eq('schema', 'bi')])}`);
    const hit = found.result?.find((d) => d.database?.id === databaseId) ?? found.result?.[0];
    let id;
    if (hit) {
      id = hit.id;
      await api('PUT', `/api/v1/dataset/${id}/refresh`);
    } else {
      id = (await api('POST', '/api/v1/dataset/', { database: databaseId, schema: 'bi', table_name: view })).id;
    }
    const ds = (await api('GET', `/api/v1/dataset/${id}`)).result;
    if (timeColumn && ds.main_dttm_col !== timeColumn) {
      await api('PUT', `/api/v1/dataset/${id}?override_columns=false`, { main_dttm_col: timeColumn });
    }
    out[view] = { id, columns: new Set(ds.columns.map((col) => col.column_name)) };
  }
  return out;
}

// ---- Charts and dashboards ------------------------------------------------------------------------------------------

async function dashboardCharts(dashboardId) {
  const found = await api('GET', `/api/v1/chart/?${q([{ col: 'dashboards', opr: 'rel_m_m', value: dashboardId }],
    { columns: ['id', 'slice_name'], page_size: 100 })}`);
  return found.result ?? [];
}

async function ensureChart(dashboardId, existing, c, datasetId) {
  const params = chartParams(c, datasetId);
  const body = {
    slice_name: c.name, viz_type: params.viz_type, datasource_id: datasetId, datasource_type: 'table',
    params: JSON.stringify(params), dashboards: [dashboardId], description: c.description ?? null,
  };
  const hit = existing.find((x) => x.slice_name === c.name);
  if (hit) {
    await api('PUT', `/api/v1/chart/${hit.id}`, body);
    return hit.id;
  }
  return (await api('POST', '/api/v1/chart/', body)).id;
}

/** The dashboard layout: rows of up to 12 columns, in tabs when charts name a `tab`, with markdown headings. */
function layout(title, placed) {
  const pos = {
    DASHBOARD_VERSION_KEY: 'v2',
    ROOT_ID: { type: 'ROOT', id: 'ROOT_ID', children: ['GRID_ID'] },
    HEADER_ID: { id: 'HEADER_ID', type: 'HEADER', meta: { text: title } },
    GRID_ID: { type: 'GRID', id: 'GRID_ID', children: [], parents: ['ROOT_ID'] },
  };
  const tabbed = placed.some((p) => p.tab);
  const containers = new Map(); // tab title (or '') -> { id, parents }
  if (tabbed) {
    pos.GRID_ID.children.push('TABS-main');
    pos['TABS-main'] = { type: 'TABS', id: 'TABS-main', children: [], parents: ['ROOT_ID', 'GRID_ID'], meta: {} };
  }
  const containerFor = (tab) => {
    const key = tabbed ? (tab ?? placed.find((p) => p.tab).tab) : '';
    if (!containers.has(key)) {
      if (!tabbed) {
        containers.set(key, { id: 'GRID_ID', parents: ['ROOT_ID'] });
      } else {
        const id = `TAB-${containers.size + 1}`;
        pos['TABS-main'].children.push(id);
        pos[id] = { type: 'TAB', id, children: [], parents: ['ROOT_ID', 'GRID_ID', 'TABS-main'],
          meta: { text: key, defaultText: 'Tab title', placeholder: 'Tab title' } };
        containers.set(key, { id, parents: ['ROOT_ID', 'GRID_ID', 'TABS-main'] });
      }
      containers.get(key).row = null;
      containers.get(key).used = 12;
    }
    return containers.get(key);
  };
  placed.forEach((p, i) => {
    const box = containerFor(p.tab);
    if (box.used + p.width > 12 || p.heading) {
      box.row = `ROW-${i}`;
      box.used = 0;
      pos[box.id].children.push(box.row);
      pos[box.row] = { type: 'ROW', id: box.row, children: [], parents: [...box.parents, box.id],
        meta: { background: 'BACKGROUND_TRANSPARENT' } };
    }
    const parents = [...box.parents, box.id, box.row];
    if (p.heading) {
      const key = `MARKDOWN-${i}`;
      pos[box.row].children.push(key);
      pos[key] = { type: 'MARKDOWN', id: key, children: [], parents,
        meta: { width: 12, height: p.height, code: p.text } };
      box.used = 12;
      return;
    }
    const key = `CHART-${p.id}`;
    pos[box.row].children.push(key);
    pos[key] = { type: 'CHART', id: key, children: [], parents,
      meta: { width: p.width, height: p.height, chartId: p.id, sliceName: p.name } };
    box.used += p.width;
  });
  // Every chart in a row as tall as the tallest, so a gauge next to a figure is not squeezed.
  for (const node of Object.values(pos)) {
    if (node.type !== 'ROW') continue;
    const kids = node.children.map((k) => pos[k]).filter((k) => k.type === 'CHART');
    const tallest = Math.max(0, ...kids.map((k) => k.meta.height));
    kids.forEach((k) => { k.meta.height = tallest; });
  }
  return pos;
}

/**
 * Native filters: a period filter on every chart with a time column, and select filters (branch, customer, ...) on
 * the charts whose dataset has that column. Ids are stable so a viewer's saved filter state survives re-provisioning.
 */
function nativeFilters(spec, placed, datasets) {
  const charts = placed.filter((p) => !p.heading);
  return (spec.filters ?? []).map((f, i) => {
    const id = `NATIVE_FILTER-${spec.module}-${i + 1}`;
    const applies = f.type === 'time' ? (p) => !!p.time : (p) => datasets[p.dataset].columns.has(f.column);
    const inScope = charts.filter(applies).map((p) => p.id);
    const excluded = charts.filter((p) => !applies(p)).map((p) => p.id);
    const common = { id, name: f.name, type: 'NATIVE_FILTER', description: f.description ?? '',
      cascadeParentIds: [], scope: { rootPath: ['ROOT_ID'], excluded }, chartsInScope: inScope, tabsInScope: [],
      defaultDataMask: { extraFormData: {}, filterState: {}, ownState: {} } };
    if (f.type === 'time') {
      return { ...common, filterType: 'filter_time', targets: [{}], controlValues: { enableEmptyFilter: false } };
    }
    return { ...common, filterType: 'filter_select',
      targets: [{ datasetId: datasets[f.dataset].id, column: { name: f.column } }],
      controlValues: { enableEmptyFilter: false, defaultToFirstItem: false, multiSelect: true,
        searchAllOptions: false, inverseSelection: false, sortAscending: true } };
  });
}

async function ensureDashboard(spec, datasets, allowedDomains) {
  const dash = await request('GET', `/api/v1/dashboard/${spec.slug}`);
  const id = dash.status === 200 ? dash.json.result.id
    : (await api('POST', '/api/v1/dashboard/', { dashboard_title: spec.title, slug: spec.slug, published: true })).id;
  const existing = await dashboardCharts(id);
  const placed = [];
  for (const c of spec.charts) {
    if (c.viz === 'heading') {
      placed.push({ heading: true, text: c.text, tab: c.tab, height: c.height ?? DEFAULT_HEIGHT.heading, width: 12 });
      continue;
    }
    placed.push({ id: await ensureChart(id, existing, c, datasets[c.dataset].id), name: c.name, dataset: c.dataset,
      spec: c, time: !!timeOf(c), tab: c.tab, width: c.width ?? 6, height: c.height ?? DEFAULT_HEIGHT[c.viz] ?? 50 });
  }
  // Charts dropped from the file: delete them, so the dashboard holds exactly what the file says.
  const keep = new Set(placed.map((p) => p.id));
  for (const stale of await dashboardCharts(id)) {
    if (!keep.has(stale.id)) await api('DELETE', `/api/v1/chart/${stale.id}`);
  }
  const chartIds = placed.filter((p) => !p.heading).map((p) => p.id);
  await api('PUT', `/api/v1/dashboard/${id}`, {
    dashboard_title: spec.title, slug: spec.slug, published: true,
    position_json: JSON.stringify(layout(spec.title, placed)),
    json_metadata: JSON.stringify({
      color_scheme: COLOR_SCHEME, refresh_frequency: 0, cross_filters_enabled: true,
      native_filter_configuration: nativeFilters(spec, placed, datasets),
      global_chart_configuration: { scope: { rootPath: ['ROOT_ID'], excluded: [] }, chartsInScope: chartIds },
      label_colors: {}, shared_label_colors: [], timed_refresh_immune_slices: [], expanded_slices: {},
    }),
  });
  const embedded = await api('POST', `/api/v1/dashboard/${id}/embedded`, { allowed_domains: allowedDomains });
  return { id, uuid: embedded.result.uuid, datasets, charts: placed.filter((p) => !p.heading) };
}

/**
 * The isolation probes: one chart per dataset (organization + row count) on a dashboard of their own, which no ERP
 * user is ever given a token for. Guest-token RLS applies per token and dataset, not per dashboard, so a probe here
 * proves the clause for every dashboard reading that dataset, without a probe table showing on a real dashboard.
 */
async function ensureProbeDashboard(allowedDomains) {
  const spec = { module: 'isolation', title: 'ERP isolation probes (never embedded for users)',
    slug: 'erp-isolation-probes', charts: Object.keys(DATASETS).map((ds) => ({ name: `${PROBE_PREFIX}${ds}`,
      viz: 'probe', dataset: ds, width: 4 })) };
  const dash = await request('GET', `/api/v1/dashboard/${spec.slug}`);
  const id = dash.status === 200 ? dash.json.result.id
    : (await api('POST', '/api/v1/dashboard/', { dashboard_title: spec.title, slug: spec.slug, published: false })).id;
  const existing = await dashboardCharts(id);
  const probes = {};
  const placed = [];
  for (const c of spec.charts) {
    probes[c.dataset] = await ensureChart(id, existing, c, datasets[c.dataset].id);
    placed.push({ id: probes[c.dataset], name: c.name, width: 4, height: 30 });
  }
  await api('PUT', `/api/v1/dashboard/${id}`, { dashboard_title: spec.title, slug: spec.slug, published: false,
    position_json: JSON.stringify(layout(spec.title, placed)) });
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

/** The probe's query, run the way an embedded viewer runs it: guest token, no admin session. */
async function probeRows(chartId, dashboardId, datasetId, token) {
  const formData = { ...chartParams({ viz: 'probe', dataset: null }, datasetId), slice_id: chartId, dashboardId };
  const saved = bearer;
  bearer = '';
  try {
    const r = await request('POST', '/api/v1/chart/data', {
      datasource: { id: datasetId, type: 'table' }, force: true, form_data: formData, result_format: 'json',
      result_type: 'full', queries: [{ columns: ['organizationId'], metrics: [metricOf('COUNT(*)', 'rows')],
        orderby: [], filters: [], extras: { having: '', where: '' }, row_limit: 1000, time_range: 'No filter' }],
    }, { 'X-GuestToken': token });
    if (r.status !== 200) throw new Error(`guest chart data HTTP ${r.status}: ${JSON.stringify(r.json).slice(0, 300)}`);
    return r.json.result[0].data;
  } finally {
    bearer = saved;
  }
}

async function checkIsolation(probeDashboard) {
  const results = { probes: probeDashboard };
  const orgs = env('BI_CHECK_ORGS').split(/[\s,]+/).filter(Boolean);
  if (orgs.length < 2) throw new Error('BI_CHECK_ORGS needs two organization ids');
  let failures = 0;
  let vacuous = 0;
  for (const [module, r] of Object.entries(results)) {
    for (const [ds, chartId] of Object.entries(r.probes)) {
      const seen = {};
      for (const org of orgs) {
        const rows = await probeRows(chartId, r.id, r.datasets[ds].id, await guestToken(r.uuid, org));
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
      const control = await probeRows(chartId, r.id, r.datasets[ds].id, await guestToken(r.uuid, null));
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

/**
 * Every chart's columns, metrics and WHERE must run: one query per chart through the chart data API (admin session),
 * which catches a misspelt column or bad SQL before a viewer sees a broken tile. The look is checked in a browser.
 */
async function checkCharts(results) {
  let failures = 0;
  for (const [module, r] of Object.entries(results)) {
    for (const c of r.charts) {
      const spec = c.spec;
      const cols = spec.viz === 'list' ? spec.columns
        : [timeOf(spec) && ['line', 'area', 'bar_time', 'kpi_trend'].includes(spec.viz) ? timeOf(spec) : null,
          ...columnsOf(spec.x), ...columnsOf(spec.y), ...columnsOf(spec.series), ...columnsOf(spec.rows),
          ...columnsOf(spec.cols)].filter(Boolean);
      const res = await request('POST', '/api/v1/chart/data', {
        datasource: { id: r.datasets[spec.dataset].id, type: 'table' }, force: true, result_format: 'json',
        result_type: 'full', queries: [{ columns: cols, metrics: spec.viz === 'list' ? [] : metricsOf(spec),
          orderby: [], filters: [], extras: { having: '', where: spec.where ?? '' }, row_limit: 5,
          time_range: 'No filter' }],
      });
      const err = res.status !== 200 ? JSON.stringify(res.json).slice(0, 300)
        : res.json.result?.find((x) => x.error)?.error;
      if (err) {
        failures++;
        console.error(`FAIL ${module} / ${c.name}: ${err}`);
      }
    }
  }
  if (failures) {
    console.error(`${failures} chart(s) failed to run.`);
    process.exit(3);
  }
  console.log(`Every chart's query ran (${Object.values(results).reduce((n, r) => n + r.charts.length, 0)} charts).`);
}

// ---- main -----------------------------------------------------------------------------------------------------------

await login();
const databaseId = await ensureDatabase();
const datasets = await ensureDatasets(databaseId);
const allowedDomains = (process.env.SUPERSET_EMBED_ALLOWED_DOMAINS ?? '').split(/\s+/).filter(Boolean);
const results = {};
for (const spec of specs) {
  results[spec.module] = await ensureDashboard(spec, datasets, allowedDomains);
  console.log(`dashboard ${spec.module}: id ${results[spec.module].id}, ${results[spec.module].charts.length} charts`);
}
console.log('\n# API configuration (.env):');
for (const [module, r] of Object.entries(results)) console.log(`SUPERSET_DASHBOARD_${module.toUpperCase()}=${r.uuid}`);
const configFile = option('--write-config');
if (configFile) {
  const dashboards = Object.fromEntries(Object.entries(results).map(([m, r]) => [m, r.uuid]));
  fs.writeFileSync(configFile, `${JSON.stringify({ dashboards }, null, 2)}\n`);
  console.log(`wrote ${configFile}`);
}
await checkCharts(results);
if (flag('--check-isolation')) {
  console.log('\nChecking tenant isolation through guest tokens...');
  await checkIsolation(await ensureProbeDashboard(allowedDomains));
}
