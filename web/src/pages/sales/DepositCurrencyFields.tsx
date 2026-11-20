import { Input } from '@/components/ui/input';
import { useCashAccounts } from '@/features/accounting/api';
import { tx } from '@/lib/i18n/tx';
import { Field } from '@/pages/crm/crm-ui';
import { useBaseCurrency, type DepositCurrency } from '@/features/sales/deposits';

const selectCls = 'h-9 w-full rounded-md border bg-background px-2 text-sm';

/**
 * ADR-S080 foreign-currency deposits: the currency (functional, or one held in a designated FX bank / cash / mobile-money
 * account), the account the money lands in or leaves from, and an optional rate (else the rate table). With
 * `lockCurrency` the currency is fixed (a refund of an existing deposit).
 */
export function DepositCurrencyFields({ value, onChange, lockCurrency }: { value: DepositCurrency; onChange: (v: DepositCurrency) => void; lockCurrency?: boolean }) {
  const base = useBaseCurrency();
  const { data: accounts = [] } = useCashAccounts();
  const foreignAccounts = accounts.filter((a) => a.currencyState === 'designated' && a.currencyCode && a.currencyCode !== base);
  const currencies = [...new Set(foreignAccounts.map((a) => a.currencyCode as string))].sort();
  const cur = value.currency || base;
  const foreign = cur !== base;
  const inCurrency = foreignAccounts.filter((a) => a.currencyCode === cur);
  if (!foreign && !currencies.length) return null;
  return (
    <>
      {!lockCurrency && (
        <Field label={tx('Currency')}>
          <select className={selectCls} value={cur} onChange={(e) => onChange({ currency: e.target.value === base ? '' : e.target.value, accountId: '', rate: '' })}>
            <option value={base}>{base}</option>
            {currencies.map((c) => <option key={c} value={c}>{c}</option>)}
          </select>
        </Field>
      )}
      {foreign && (
        <>
          <Field label={`${tx('Account held in')} ${cur}`}>
            <select className={selectCls} value={value.accountId} onChange={(e) => onChange({ ...value, accountId: e.target.value })}>
              <option value="">{tx('Choose…')}</option>
              {inCurrency.map((a) => <option key={a.id} value={a.id}>{a.code} · {a.name}</option>)}
            </select>
          </Field>
          {!inCurrency.length && <p className="text-xs text-muted-foreground">{tx('No designated account is held in this currency. Add one under Money → Accounts.')}</p>}
          <Field label={`${tx('Exchange rate')} (1 ${cur} = ? ${base})`}>
            <Input type="number" min={0} step="any" value={value.rate} placeholder={tx('From the rate table')} onChange={(e) => onChange({ ...value, rate: e.target.value })} />
          </Field>
          <p className="text-xs text-muted-foreground">{tx('The deposit is held in this currency at this rate and is not revalued; it settles invoices in the same currency only.')}</p>
        </>
      )}
    </>
  );
}
