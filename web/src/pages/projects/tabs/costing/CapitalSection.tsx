import { useState } from 'react';
import { Building, Plus, Trash2, Undo2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Skeleton } from '@/components/ui/skeleton';
import { money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { useAllAssetCategories } from '@/features/fixed-asset/api';
import { selectClass, shortDate, todayIso } from '@/features/projects/ui';
import { constructionApi, opt, useCip, useConstructionMutation } from '@/features/projects/construction-api';
import { FormDialog, isAmount } from '../contract/shared';
import { Field, Section, StatusChip, num, numCell, tdClass, thClass } from './shared';

interface Line { name: string; categoryId: string; amount: string; usefulLife: string; salvageValue: string; inServiceDate: string }
const emptyLine = (): Line => ({ name: '', categoryId: '', amount: '', usefulLife: '', salvageValue: '', inServiceDate: todayIso() });

/**
 * Own-use (capital) project (ADR-S074 §3): the capital flag, the CIP balance reconciled to the GL, capitalisation
 * into fixed assets and its reversal. The flag needs `project:manage`; capitalising and reversing need `project:post`.
 */
export function CapitalSection({ projectId, canManage, canPost }: { projectId: string; canManage: boolean; canPost: boolean }) {
  const { data: cip, isLoading } = useCip(projectId);
  const { data: categories = [] } = useAllAssetCategories();
  const [open, setOpen] = useState(false);
  const [date, setDate] = useState(todayIso());
  const [lines, setLines] = useState<Line[]>([emptyLine()]);

  const flag = useConstructionMutation<boolean>((v) => constructionApi.setCapital(projectId, v), { success: tx('Project updated') });
  const capitalise = useConstructionMutation<void>(() => constructionApi.capitalise(projectId, {
    date: opt(date),
    assets: lines.map((l) => ({
      name: l.name.trim(), categoryId: l.categoryId, amount: l.amount.trim(), inServiceDate: l.inServiceDate,
      usefulLife: l.usefulLife.trim() ? Number(l.usefulLife) : undefined, salvageValue: opt(l.salvageValue),
    })),
  }), { success: tx('Capitalised into fixed assets') });
  const reverse = useConstructionMutation<string>((id) => constructionApi.reverseCapitalisation(id), { success: tx('Capitalisation reversed') });

  if (isLoading || !cip) return <Skeleton className="h-40 w-full" />;
  const total = lines.reduce((s, l) => s + num(l.amount), 0);
  const valid = lines.every((l) => l.name.trim() && l.categoryId && isAmount(l.amount) && num(l.amount) > 0 && l.inServiceDate)
    && total <= num(cip.balance) + 0.005;
  const set = (i: number, patch: Partial<Line>) => setLines(lines.map((l, n) => (n === i ? { ...l, ...patch } : l)));

  return (
    <div className="space-y-4">
      <Section
        title={tx('Capital project (own use)')}
        actions={canManage && (
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={cip.isCapital} disabled={flag.isPending} onChange={(e) => flag.mutate(e.target.checked)} />
            {tx('Capitalise this project into fixed assets')}
          </label>
        )}
      >
        {!cip.isCapital ? (
          <p className="text-sm text-muted-foreground">
            {tx('Cost is held in work in progress (1460) and released by revenue runs. Flag the project as capital, before any cost is captured, to hold its cost in Construction in Progress (1690) instead.')}
          </p>
        ) : (
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
            {[
              [tx('Cost captured'), cip.costCaptured],
              [tx('Capitalised'), cip.capitalised],
              [tx('CIP balance'), cip.balance],
              [tx('GL (1690)'), cip.glBalance],
              [tx('Difference'), cip.difference],
            ].map(([label, value]) => (
              <div key={label as string} className="rounded-lg border p-3">
                <p className="text-xs text-muted-foreground">{label as string}</p>
                <p className={`mt-1 font-semibold tabular-nums ${label === tx('Difference') && num(value) !== 0 ? 'text-rose-600' : ''}`}>{money(num(value))}</p>
              </div>
            ))}
          </div>
        )}
      </Section>

      {cip.isCapital && (
        <Section
          title={tx('Capitalisations')}
          actions={canPost && (
            <Button size="sm" disabled={num(cip.balance) <= 0} onClick={() => { setLines([emptyLine()]); setDate(todayIso()); setOpen(true); }}>
              <Building className="mr-1.5 h-4 w-4" />{tx('Capitalise')}
            </Button>
          )}
        >
          {cip.capitalisations.length === 0 ? (
            <p className="text-sm text-muted-foreground">{tx('Nothing capitalised yet.')}</p>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead><tr className="border-b">
                  <th className={thClass}>{tx('Number')}</th><th className={thClass}>{tx('Date')}</th><th className={thClass}>{tx('Assets')}</th>
                  <th className={`${thClass} text-right`}>{tx('Amount')}</th><th className={thClass}>{tx('Status')}</th><th className={thClass} />
                </tr></thead>
                <tbody>
                  {cip.capitalisations.map((c) => (
                    <tr key={c.id} className="border-b last:border-0 align-top">
                      <td className={`${tdClass} font-mono text-xs`}>{c.number}</td>
                      <td className={tdClass}>{shortDate(c.capitalisationDate)}</td>
                      <td className={tdClass}>
                        {c.assets.map((a) => (
                          <div key={a.assetId}><span className="font-mono text-xs">{a.assetCode}</span> {a.name} · {money(num(a.amount))} · {tx('in service')} {shortDate(a.inServiceDate)}</div>
                        ))}
                      </td>
                      <td className={numCell}>{money(num(c.amount))}</td>
                      <td className={tdClass}><StatusChip status={c.status} /></td>
                      <td className={`${tdClass} text-right`}>
                        {canPost && c.status === 'posted' && (
                          <Button size="sm" variant="ghost" disabled={reverse.isPending}
                            onClick={() => window.confirm(tx('Reverse capitalisation {{number}}? Its assets are removed; refused once depreciation is posted.', { number: c.number })) && reverse.mutate(c.id)}>
                            <Undo2 className="mr-1 h-3.5 w-3.5" />{tx('Reverse')}
                          </Button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </Section>
      )}

      <FormDialog open={open} onClose={() => setOpen(false)} title={tx('Capitalise into fixed assets')} submitLabel={tx('Capitalise')}
        description={tx('Available CIP balance: {{balance}}. Posts Dr fixed assets / Cr Construction in Progress.', { balance: money(num(cip.balance)) })}
        valid={valid} pending={capitalise.isPending} onSubmit={() => capitalise.mutate(undefined, { onSuccess: () => setOpen(false) })}>
        <Field label={tx('Capitalisation date')}><Input type="date" value={date} onChange={(e) => setDate(e.target.value)} /></Field>
        <div className="flex items-end justify-end text-sm">{tx('Total')}: <span className="ml-1 font-semibold tabular-nums">{money(total)}</span></div>
        {lines.map((l, i) => (
          <div key={i} className="grid gap-2 rounded-lg border p-3 sm:col-span-2 sm:grid-cols-2">
            <Field label={tx('Asset name')}><Input value={l.name} onChange={(e) => set(i, { name: e.target.value })} /></Field>
            <Field label={tx('Category')}>
              <select className={selectClass} value={l.categoryId} onChange={(e) => set(i, { categoryId: e.target.value })}>
                <option value="">{tx('Select…')}</option>
                {categories.filter((c) => c.isActive).map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
              </select>
            </Field>
            <Field label={tx('Amount')}><Input type="number" step="0.01" min={0} value={l.amount} onChange={(e) => set(i, { amount: e.target.value })} /></Field>
            <Field label={tx('In service from')}><Input type="date" value={l.inServiceDate} onChange={(e) => set(i, { inServiceDate: e.target.value })} /></Field>
            <Field label={tx('Useful life (months)')} hint={tx('Defaults to the category')}>
              <Input type="number" min={1} step={1} value={l.usefulLife} onChange={(e) => set(i, { usefulLife: e.target.value })} />
            </Field>
            <Field label={tx('Salvage value')}><Input type="number" step="0.01" min={0} value={l.salvageValue} onChange={(e) => set(i, { salvageValue: e.target.value })} /></Field>
            {lines.length > 1 && (
              <div className="sm:col-span-2 text-right">
                <Button type="button" size="sm" variant="ghost" className="text-rose-600" onClick={() => setLines(lines.filter((_, n) => n !== i))}>
                  <Trash2 className="mr-1 h-3.5 w-3.5" />{tx('Remove')}
                </Button>
              </div>
            )}
          </div>
        ))}
        <div className="sm:col-span-2">
          <Button type="button" size="sm" variant="outline" onClick={() => setLines([...lines, emptyLine()])}><Plus className="mr-1 h-3.5 w-3.5" />{tx('Another asset')}</Button>
        </div>
      </FormDialog>
    </div>
  );
}
