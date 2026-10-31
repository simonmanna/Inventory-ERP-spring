import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, FileDown } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { date, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { openPdf } from '@/features/finance/api';
import { num } from '@/pages/fleet/fleet-format';
import { usePortalInvoice, usePortalInvoices, usePortalStatement } from '@/features/portal/api';
import { Empty, InvoiceStatus, LoadError, Loading, Pager } from './portal-ui';

/** My issued invoices. */
export function PortalInvoicesPage() {
  const [open, setOpen] = useState(false);
  const [page, setPage] = useState(1);
  const { data, isLoading, isError, refetch } = usePortalInvoices({ open: open || undefined, page, pageSize: 20 });
  const choose = (v: boolean) => { setOpen(v); setPage(1); };
  return (
    <div className="space-y-3">
      <h1 className="text-xl font-bold">{tx('Invoices')}</h1>
      <div className="flex gap-2">
        <Button variant={!open ? 'default' : 'outline'} onClick={() => choose(false)}>{tx('All')}</Button>
        <Button variant={open ? 'default' : 'outline'} onClick={() => choose(true)}>{tx('Balance due')}</Button>
      </div>
      {isLoading && <Loading />}
      {isError && <LoadError onRetry={() => refetch()} />}
      {data?.data.length === 0 && <Empty>{tx('No invoices here.')}</Empty>}
      {data?.data.map((i) => (
        <Link key={i.id} to={`/portal/invoices/${i.id}`} className="block rounded-xl border bg-card p-3 hover:bg-accent">
          <div className="flex items-center justify-between gap-2"><span className="font-medium">{i.number}</span><InvoiceStatus residual={i.amountResidual} /></div>
          <div className="flex justify-between gap-2 text-sm text-muted-foreground">
            <span>{date(i.issueDate)}{i.dueDate ? ` · ${tx('Due')} ${date(i.dueDate)}` : ''}</span>
            <span className="tabular-nums">{money(i.totalAmount)}</span>
          </div>
        </Link>
      ))}
      {data && <Pager page={data.meta.page} totalPages={data.meta.totalPages} onPage={setPage} />}
    </div>
  );
}

/** One invoice with its lines and PDF. */
export function PortalInvoiceDetailPage() {
  const { id = '' } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { data, isLoading, isError, refetch } = usePortalInvoice(id);
  if (isLoading) return <Loading />;
  if (isError || !data) return <LoadError onRetry={() => refetch()} />;
  return (
    <div className="space-y-4">
      <div className="flex items-center gap-2">
        <Button variant="ghost" size="icon" aria-label={tx('Back')} onClick={() => navigate('/portal/invoices')}><ArrowLeft className="h-5 w-5" /></Button>
        <div className="min-w-0 flex-1">
          <h1 className="text-xl font-bold">{data.number}</h1>
          <p className="text-sm text-muted-foreground">{date(data.issueDate)}{data.dueDate ? ` · ${tx('Due')} ${date(data.dueDate)}` : ''}</p>
        </div>
        <Button variant="outline" onClick={() => openPdf(`/portal/invoices/${data.id}/pdf`)}><FileDown className="mr-1.5 h-4 w-4" /> {tx('PDF')}</Button>
      </div>
      <div className="rounded-xl border bg-card p-3 text-sm">
        {data.lines.map((l, n) => (
          <div key={n} className="flex justify-between gap-2 py-1">
            <span>{l.description}</span>
            <span className="tabular-nums whitespace-nowrap">{num(l.quantity, 3)} × {money(l.unitPrice)}</span>
          </div>
        ))}
        <div className="mt-2 space-y-1 border-t pt-2">
          <div className="flex justify-between font-medium"><span>{tx('Total')}</span><span className="tabular-nums">{money(data.totalAmount)}</span></div>
          <div className="flex justify-between"><span>{tx('Paid')}</span><span className="tabular-nums">{money(data.amountPaid)}</span></div>
          <div className="flex justify-between font-medium"><span>{tx('Balance due')}</span><span className="tabular-nums">{money(data.amountResidual)}</span></div>
        </div>
      </div>
    </div>
  );
}

const iso = (d: Date) => d.toISOString().slice(0, 10);

/** My statement of account. */
export function PortalStatementPage() {
  const [from, setFrom] = useState(iso(new Date(Date.now() - 89 * 86_400_000)));
  const [to, setTo] = useState(iso(new Date()));
  const { data, isLoading, isError, refetch } = usePortalStatement({ from, to });
  return (
    <div className="space-y-3">
      <div className="flex items-center justify-between gap-2">
        <h1 className="text-xl font-bold">{tx('Statement')}</h1>
        <Button variant="outline" onClick={() => openPdf('/portal/statement.pdf', { from, to })}><FileDown className="mr-1.5 h-4 w-4" /> {tx('PDF')}</Button>
      </div>
      <div className="flex flex-wrap gap-2">
        <Input type="date" className="w-40" value={from} onChange={(e) => setFrom(e.target.value)} aria-label={tx('From')} />
        <Input type="date" className="w-40" value={to} onChange={(e) => setTo(e.target.value)} aria-label={tx('To')} />
      </div>
      {isLoading && <Loading />}
      {isError && <LoadError onRetry={() => refetch()} />}
      {data && (
        <>
          <div className="grid grid-cols-2 gap-3 rounded-xl border bg-card p-3 text-sm">
            <div><div className="text-muted-foreground">{tx('Opening balance')}</div><div className="font-medium tabular-nums">{money(data.openingBalance)}</div></div>
            <div><div className="text-muted-foreground">{tx('Closing balance')}</div><div className="font-medium tabular-nums">{money(data.closingBalance)}</div></div>
          </div>
          {data.lines.length === 0 && <Empty>{tx('No movements in this period.')}</Empty>}
          {data.lines.map((l, n) => (
            <div key={`${l.entryId}-${n}`} className="rounded-xl border bg-card p-3 text-sm">
              <div className="flex justify-between gap-2"><span className="font-medium">{l.reference ?? '—'}</span><span className="tabular-nums">{money(l.balance)}</span></div>
              <div className="flex justify-between gap-2 text-muted-foreground">
                <span>{date(l.date)}{l.description ? ` · ${l.description}` : ''}</span>
                <span className="tabular-nums whitespace-nowrap">{Number(l.debit) > 0 ? money(l.debit) : `− ${money(l.credit)}`}</span>
              </div>
            </div>
          ))}
        </>
      )}
    </div>
  );
}
