import { useAuthStore } from '@/stores/auth.store';
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

/** The organization's functional currency code. */
export function useBaseCurrency(): string {
  return useAuthStore((s) => s.organization?.currencyCode ?? '');
}

/** Value of the currency fields: `currency` '' = functional; foreign needs an account held in that currency. */
export interface DepositCurrency { currency: string; accountId: string; rate: string }

/** Body fields for a deposit receipt / refund (nothing extra for a functional-currency deposit). */
export function depositCurrencyBody(v: DepositCurrency, base: string): Record<string, unknown> {
  if (!v.currency || v.currency === base) return {};
  return { currencyCode: v.currency, accountId: v.accountId || undefined, exchangeRate: Number(v.rate) > 0 ? Number(v.rate) : undefined };
}

/** True when a foreign deposit is missing its account (the server would refuse it). */
export function depositCurrencyIncomplete(v: DepositCurrency, base: string): boolean {
  return !!v.currency && v.currency !== base && !v.accountId;
}
