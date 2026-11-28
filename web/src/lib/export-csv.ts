/**
 * Neutralise CSV formula injection: a cell that starts with = + - @ (or a
 * leading tab/CR that spreadsheets strip before parsing) is treated as a
 * formula by Excel/Sheets. Item, waiter and customer names are user-editable,
 * so prefix such cells with a single quote to force them to render as text.
 */
const NUMBER = /^[-+]?(\d+(\.\d*)?|\.\d+)([eE][-+]?\d+)?$/;

/**
 * IPR S-8: a plain number (a negative quantity or value such as `-3` or `-12.50`) is data, not a formula: it stays a
 * number so the spreadsheet can sum it. Text that only starts like a formula is still guarded.
 */
export function csvCell(v: string | number | null | undefined): string {
  const s = String(v ?? '');
  const guarded = !NUMBER.test(s.trim()) && /^[=+\-@\t\r]/.test(s) ? `'${s}` : s;
  return `"${guarded.replace(/"/g, '""')}"`;
}

export function exportCSV(filename: string, headers: string[], rows: string[][]) {
  const csv = [
    headers.map(csvCell).join(','),
    ...rows.map((r) => r.map(csvCell).join(',')),
  ].join('\n');

  const blob = new Blob(['\uFEFF' + csv], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  a.click();
  // Revoking synchronously can cancel the download in some browsers.
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
