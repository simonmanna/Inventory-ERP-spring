import { useEffect } from 'react';
import { tx } from '@/lib/i18n/tx';
import { useVerifiedBankAccounts } from './api';

/**
 * Purchase readiness H7: pick the supplier's verified bank account a bank transfer goes to. Only verified accounts are
 * offered (registered on the supplier and verified by a second person); the default one is preselected.
 */
export function SupplierBankAccountSelect({
  id,
  partnerId,
  value,
  onChange,
}: {
  id: string;
  partnerId?: string;
  value: string;
  onChange: (value: string) => void;
}) {
  const accounts = useVerifiedBankAccounts(partnerId);
  const list = accounts.data ?? [];

  useEffect(() => {
    if (!value && list.length > 0) {
      onChange((list.find((a) => a.isDefault) ?? list[0]).id);
    }
    if (value && accounts.isSuccess && !list.some((a) => a.id === value)) {
      onChange('');
    }
  }, [accounts.isSuccess, list, value, onChange]);

  return (
    <div>
      <label className="text-sm font-medium" htmlFor={id}>{tx('Supplier bank account')}</label>
      <select
        id={id}
        className="mt-1 h-9 w-full rounded-md border bg-background px-3 text-sm"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        disabled={!partnerId}
      >
        <option value="">{tx('Select a verified account')}</option>
        {list.map((a) => (
          <option key={a.id} value={a.id}>
            {`${a.bankName} — ${a.accountName} ${a.accountNumberMasked}${a.currencyCode ? ` (${a.currencyCode})` : ''}`}
          </option>
        ))}
      </select>
      {accounts.isSuccess && list.length === 0 && (
        <p className="mt-1 text-xs text-amber-700">
          {tx('This supplier has no verified bank account. Register one on the supplier and have someone else verify it, or pay by another method.')}
        </p>
      )}
    </div>
  );
}
