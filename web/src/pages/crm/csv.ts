import { api } from '@/lib/api';
import { tx } from '@/lib/i18n/tx';
import type { CrmLookups, Opportunity } from '@/features/crm/suite-api';

/**
 * Download rows as a CSV file. A cell typed by a user that starts with = + - @ would run as a formula in a
 * spreadsheet, so it is prefixed with an apostrophe (numbers stay numbers).
 */
export function downloadCsv(filename: string, lines: Array<Array<string | number | null | undefined>>) {
  const cell = (c: string) => (/^[=+\-@\t\r]/.test(c) && Number.isNaN(Number(c)) ? `'${c}` : c);
  const csv = lines.map((l) => l.map((c) => `"${cell(String(c ?? '')).replace(/"/g, '""')}"`).join(',')).join('\n');
  const url = URL.createObjectURL(new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8' }));
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  URL.revokeObjectURL(url);
}

const MAX_ROWS = 5000;

/** Every lead or opportunity matching the current filters (all pages, up to 5,000), as a CSV download. */
export async function exportDeals(path: '/crm/leads' | '/crm/opportunities', filters: Record<string, string | number | undefined>, lk: CrmLookups | undefined) {
  const rows: Opportunity[] = [];
  for (let page = 1; rows.length < MAX_ROWS; page++) {
    const params = Object.fromEntries(Object.entries({ ...filters, page, pageSize: 200 }).filter(([, v]) => v !== undefined && String(v) !== ''));
    const res = (await api.get<{ data: Opportunity[]; meta: { totalPages: number } }>(path, { params })).data;
    rows.push(...res.data);
    if (page >= res.meta.totalPages || res.data.length === 0) break;
  }
  const name = (list: Array<{ id: string; name: string }> | undefined, id: string | null) => (id ? list?.find((x) => x.id === id)?.name ?? '' : '');
  const lead = path === '/crm/leads';
  const head = [tx('Name'), tx('Customer'), tx('Company'), tx('Contact'), tx('Email'), tx('Phone'), tx('City'), tx('Country'),
    tx('Stage'), tx('Status'), tx('Amount'), tx('Currency'), tx('Probability %'), tx('Expected close'), tx('Salesperson'),
    tx('Team'), tx('Source'), tx('Tags'), tx('Priority'), tx('Created')];
  const body = rows.slice(0, MAX_ROWS).map((d) => [d.name, d.partnerName, d.companyName, d.contactName, d.email, d.phone, d.city, d.country,
    d.stageName, d.status, d.amount, d.currencyCode, d.probability, d.expectedClose?.slice(0, 10), d.ownerName,
    name(lk?.teams, d.teamId), name(lk?.sources, d.sourceId), d.tagIds.map((t) => name(lk?.tags, t)).filter(Boolean).join('; '),
    d.priority, d.createdAt?.slice(0, 10)]);
  downloadCsv(`crm-${lead ? 'leads' : 'opportunities'}-${new Date().toISOString().slice(0, 10)}.csv`, [head, ...body]);
  return rows.length;
}
