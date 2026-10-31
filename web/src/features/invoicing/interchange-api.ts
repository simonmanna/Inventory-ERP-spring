import { useMutation, useQuery } from '@tanstack/react-query';
import type { PaginatedResult } from '@erp/shared';
import { api } from '@/lib/api';

// ---- Bank statement files and label printing (INT-3) ----

export interface BankFileResult {
  format: string;
  lines: number;
  moneyIn: string;
  moneyOut: string;
  warnings: string[];
  dryRun: boolean;
  imported?: number;
  skipped?: number;
  preview?: Array<{ postedAt: string; externalRef: string; description: string; amount: string }>;
}

export function useBankAccountOptions() {
  return useQuery({
    queryKey: ['bank-accounts', 'options'],
    queryFn: async () => (await api.get<PaginatedResult<{ id: string; name: string; bankName: string | null }>>(
      '/bank-accounts', { params: { page: 1, pageSize: 100 } },
    )).data.data,
  });
}

export function useBankFileImport() {
  return useMutation({
    mutationFn: async ({ bankAccountId, format, dryRun, file }: { bankAccountId: string; format: string; dryRun: boolean; file: File }) => {
      const fd = new FormData();
      fd.append('file', file);
      return (await api.post<BankFileResult>('/bank-reconciliation/import-file', fd, {
        params: { bankAccountId, format, dryRun },
        headers: { 'Content-Type': 'multipart/form-data' },
      })).data;
    },
  });
}

export type LabelType = 'product' | 'stockpile' | 'ticket';

export function useLabelSources(type: LabelType, search: string) {
  return useQuery({
    queryKey: ['labels', type, search],
    queryFn: async () => (await api.get<Array<{ id: string; title: string; subtitle: string | null; code: string }>>(
      `/labels/sources/${type}`, { params: { search: search || undefined } },
    )).data,
  });
}

/** Renders on the server, then opens the PDF or saves the ZPL file. */
export async function renderLabels(body: { type: LabelType; ids: string[]; copies: number; format: 'pdf' | 'zpl'; symbology: 'qr' | 'code128' }) {
  const res = await api.post('/labels/render', body, { responseType: 'blob' });
  const url = URL.createObjectURL(res.data as Blob);
  if (body.format === 'pdf') {
    window.open(url, '_blank', 'noopener');
  } else {
    const a = document.createElement('a');
    a.href = url;
    a.download = 'labels.zpl';
    a.click();
  }
  setTimeout(() => URL.revokeObjectURL(url), 60_000);
}
