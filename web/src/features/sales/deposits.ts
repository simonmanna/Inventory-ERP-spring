import { toast } from 'sonner';
import { api } from '@/lib/api';
import { apiErrorMessage } from '@/lib/api-error';
import { tx } from '@/lib/i18n/tx';

/** Download the deposit receipt PDF (`GET /customer-deposits/:id/pdf`). */
export async function printDepositReceipt(d: { id: string; number: string }) {
  try {
    const res = await api.get(`/customer-deposits/${d.id}/pdf`, { responseType: 'blob' });
    const url = URL.createObjectURL(res.data as Blob);
    const a = document.createElement('a'); a.href = url; a.download = `deposit-${d.number}.pdf`; a.click(); URL.revokeObjectURL(url);
  } catch (e) { toast.error(apiErrorMessage(e, tx('Could not print the receipt'))); }
}
