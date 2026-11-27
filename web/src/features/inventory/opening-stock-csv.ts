/**
 * IPR R5 / ADR-S090 — the opening-stock CSV file, parsed in the browser into the rows that
 * `POST /inventory/opening-stock/preview` and `/import` take. The server validates every row; this only maps columns.
 *
 * Columns (header row required, any order, case and spaces ignored):
 *   product (code or SKU) · variant (variant SKU, products with variants only) · location (location code) ·
 *   quantity (base unit) · unit_cost (blank = the product's cost price; STANDARD products: blank or the standard) ·
 *   lot · expiry (YYYY-MM-DD) · serials (separated by | or ;)
 */

export interface OpeningStockRow {
  rowNo: number;
  productCode: string | null;
  variantSku: string | null;
  locationCode: string | null;
  quantity: string | null;
  unitCost: string | null;
  batchNumber: string | null;
  expiryDate: string | null;
  serialNumbers: string[];
}

export interface ParsedOpeningStock {
  rows: OpeningStockRow[];
  /** File-level problems (missing header columns, too many rows). Row problems come back from the preview. */
  errors: string[];
}

export const OPENING_STOCK_MAX_ROWS = 20000;

export const OPENING_STOCK_TEMPLATE =
  'product,variant,location,quantity,unit_cost,lot,expiry,serials\n'
  + 'SKU-001,,MAIN,10,2.50,,,\n'
  + 'SKU-LOT,,MAIN,4,3.00,LOT-A,2027-12-31,\n'
  + 'SKU-SER,,MAIN,2,100,,,SN-1|SN-2\n';

const ALIASES: Record<string, keyof Omit<OpeningStockRow, 'rowNo'>> = {
  product: 'productCode', productcode: 'productCode', code: 'productCode', sku: 'productCode',
  variant: 'variantSku', variantsku: 'variantSku',
  location: 'locationCode', locationcode: 'locationCode',
  quantity: 'quantity', qty: 'quantity',
  unitcost: 'unitCost', cost: 'unitCost',
  lot: 'batchNumber', batch: 'batchNumber', batchnumber: 'batchNumber', lotnumber: 'batchNumber',
  expiry: 'expiryDate', expirydate: 'expiryDate',
  serials: 'serialNumbers', serial: 'serialNumbers', serialnumbers: 'serialNumbers',
};

const REQUIRED: Array<keyof Omit<OpeningStockRow, 'rowNo'>> = ['productCode', 'locationCode', 'quantity'];

/** RFC 4180-style parse: quotes, escaped quotes, newlines inside quotes; comma or semicolon picked from line 1. */
export function parseCsvText(text: string): string[][] {
  const src = text.replace(/^﻿/, '');
  const firstLine = src.split(/\r?\n/, 1)[0] ?? '';
  const sep = (firstLine.match(/;/g)?.length ?? 0) > (firstLine.match(/,/g)?.length ?? 0) ? ';' : ',';
  const out: string[][] = [];
  let row: string[] = [];
  let cell = '';
  let quoted = false;
  for (let i = 0; i < src.length; i++) {
    const c = src[i];
    if (quoted) {
      if (c === '"' && src[i + 1] === '"') { cell += '"'; i++; } else if (c === '"') quoted = false;
      else cell += c;
    } else if (c === '"') quoted = true;
    else if (c === sep) { row.push(cell); cell = ''; } else if (c === '\n' || c === '\r') {
      if (c === '\r' && src[i + 1] === '\n') i++;
      row.push(cell); cell = '';
      if (row.some((x) => x.trim() !== '')) out.push(row);
      row = [];
    } else cell += c;
  }
  row.push(cell);
  if (row.some((x) => x.trim() !== '')) out.push(row);
  return out;
}

const blank = (v: string | undefined) => {
  const t = (v ?? '').trim();
  return t === '' ? null : t;
};

/** Map a CSV file to opening-stock rows (row numbers are the spreadsheet line numbers, header = line 1). */
export function parseOpeningStockCsv(text: string): ParsedOpeningStock {
  const table = parseCsvText(text);
  if (table.length === 0) return { rows: [], errors: ['The file is empty'] };
  const header = table[0].map((h) => ALIASES[h.toLowerCase().replace(/[\s_\-]/g, '')]);
  const missing = REQUIRED.filter((k) => !header.includes(k));
  const errors: string[] = [];
  if (missing.length > 0) {
    const names: Record<string, string> = { productCode: 'product', locationCode: 'location', quantity: 'quantity' };
    errors.push(`Missing column(s): ${missing.map((m) => names[m]).join(', ')}`);
    return { rows: [], errors };
  }
  const rows: OpeningStockRow[] = table.slice(1).map((cells, i) => {
    const r: OpeningStockRow = {
      rowNo: i + 2, productCode: null, variantSku: null, locationCode: null, quantity: null, unitCost: null,
      batchNumber: null, expiryDate: null, serialNumbers: [],
    };
    header.forEach((key, col) => {
      if (!key) return;
      if (key === 'serialNumbers') {
        r.serialNumbers = (cells[col] ?? '').split(/[|;]/).map((s) => s.trim()).filter(Boolean);
      } else {
        r[key] = blank(cells[col]);
      }
    });
    return r;
  });
  if (rows.length === 0) errors.push('The file has a header but no rows');
  if (rows.length > OPENING_STOCK_MAX_ROWS) {
    errors.push(`An opening-stock file holds at most ${OPENING_STOCK_MAX_ROWS} rows; split it`);
  }
  return { rows, errors };
}

/** Row errors of a preview grouped by row number, for the table. */
export function errorsByRow(errors: Array<{ rowNo: number; message: string }>): Map<number, string[]> {
  const m = new Map<number, string[]>();
  for (const e of errors) m.set(e.rowNo, [...(m.get(e.rowNo) ?? []), e.message]);
  return m;
}

/** A run's state in words for the status badge. */
export function runStatusLabel(status: string): { label: string; tone: 'ok' | 'warn' | 'bad' | 'info' } {
  switch (status) {
    case 'completed': return { label: 'Completed', tone: 'ok' };
    case 'partial': return { label: 'Partly posted — retry the failed batches', tone: 'warn' };
    case 'failed': return { label: 'Failed — retry the batches', tone: 'bad' };
    default: return { label: 'Posting', tone: 'info' };
  }
}
