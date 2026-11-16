import { useState, type ReactNode } from 'react';
import { Download, Eye, FileText, FolderOpen, Printer, Undo2 } from 'lucide-react';
import { toast } from 'sonner';
import { AttachmentPanel } from '@/components/dms/AttachmentPanel';
import { Button } from '@/components/ui/button';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { api } from '@/lib/api';
import { apiErrorMessage } from '@/lib/api-error';
import { date as fmtDate, money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { cn } from '@/lib/utils';
import { useAuthStore } from '@/stores/auth.store';
import type { OrderReturn, ReturnView } from '@/features/sales/delivery-api';
import { Section, n, qty, qtyUnit } from './delivery-ui';

export type { ReturnView };

/** A customer return (RMA) of a sales order in the shared returns-list shape. */
export function orderReturnView(r: OrderReturn): ReturnView {
  return {
    id: r.id, kind: 'sales_return', number: r.number, date: r.closedAt ?? r.receivedAt ?? r.createdAt, status: r.status, reason: r.reason,
    outcome: r.outcome, lines: r.lines.map((l) => ({ productName: l.description, quantity: l.quantity, receivedQty: l.receivedQty, uomCode: l.uomCode, disposition: l.disposition })),
  };
}

/** Fetch a PDF and either download it or open it in a new tab for printing. */
export async function openPdf(path: string, filename: string, mode: 'download' | 'print') {
  try {
    const r = await api.get(path, { responseType: 'blob' });
    const url = URL.createObjectURL(r.data as Blob);
    if (mode === 'print') {
      const w = window.open(url, '_blank');
      if (!w) toast.error(tx('Allow pop-ups to print the delivery note'));
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
      return;
    }
    const a = document.createElement('a');
    a.href = url; a.download = filename; a.click();
    URL.revokeObjectURL(url);
  } catch (e) {
    toast.error(apiErrorMessage(e, tx('Could not open the delivery note')));
  }
}

export interface NoteLine { id: string; name: string; uom: string | null; ordered: number; delivered: number; returned?: number }

/**
 * On-screen delivery note: company, delivery address, note number, source document, dates and per product the
 * ordered / delivered (and returned) quantities — the same content as the printed PDF.
 */
export function DeliveryNotePreview({
  number, sourceLabel, sourceNumber, customer, address, contact, shippingDate, scheduledDate, deadline, receivedBy, lines,
  onPrint, onDownload, hint, footnote,
}: {
  number: string | null; sourceLabel: string; sourceNumber?: string | null; customer?: string | null; address?: string | null;
  contact?: string | null; shippingDate?: string | null; scheduledDate?: string | null; deadline?: string | null;
  receivedBy?: string | null; lines: NoteLine[]; onPrint?: () => void; onDownload?: () => void; hint?: string;
  /** Extra line under the receipt (Phase A A2: the customer's weighing). */
  footnote?: string | null;
}) {
  const company = useAuthStore((s) => s.organization?.name);
  const anyReturned = lines.some((l) => n(l.returned) > 0);
  const facts: Array<[string, string | null | undefined]> = [
    [sourceLabel, sourceNumber], [tx('Shipping date'), shippingDate ? fmtDate(shippingDate) : null],
    [tx('Scheduled date'), scheduledDate ? fmtDate(scheduledDate) : null], [tx('Deadline'), deadline ? fmtDate(deadline) : null],
  ];
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-xs text-muted-foreground">{hint ?? tx('Print it to travel with the goods; the customer signs on receipt.')}</p>
        <div className="flex gap-2">
          {onPrint && <Button size="sm" variant="outline" onClick={onPrint}><Printer className="mr-1.5 h-4 w-4" />{tx('Print')}</Button>}
          {onDownload && <Button size="sm" variant="outline" onClick={onDownload}><Download className="mr-1.5 h-4 w-4" />{tx('PDF')}</Button>}
        </div>
      </div>
      <article className="rounded-xl border bg-white p-6 text-zinc-900 shadow-sm dark:bg-zinc-950 dark:text-zinc-100" aria-label={tx('Delivery note')}>
        <div className="grid gap-6 sm:grid-cols-2">
          <p className="text-lg font-semibold">{company ?? ''}</p>
          <div className="text-sm sm:justify-self-end">
            <p className="font-semibold">{tx('Delivery address')}</p>
            {[customer, address, contact].filter(Boolean).map((v) => <p key={v} className="whitespace-pre-wrap">{v}</p>)}
            {!customer && !address && <p className="text-zinc-500">—</p>}
          </div>
        </div>
        <h3 className="mt-6 flex items-center gap-2 text-2xl font-light"><FileText className="h-5 w-5 text-zinc-400" />{tx('Delivery Note')} {number ?? ''}</h3>
        <dl className="mt-4 grid gap-4 text-sm sm:grid-cols-4">
          {facts.filter(([, v]) => v).map(([k, v]) => (
            <div key={k}><dt className="font-semibold">{k}</dt><dd>{v}</dd></div>
          ))}
        </dl>
        <table className="mt-5 w-full text-sm">
          <thead className="border-b-2 border-zinc-800 text-left dark:border-zinc-300">
            <tr><th className="py-2">{tx('Product')}</th><th className="py-2 text-right">{tx('Ordered')}</th><th className="py-2 text-right">{tx('Delivered')}</th>
              {anyReturned && <th className="py-2 text-right">{tx('Returned')}</th>}</tr>
          </thead>
          <tbody>
            {lines.map((l) => (
              <tr key={l.id} className="border-b border-zinc-200 dark:border-zinc-800">
                <td className="py-2">{l.name}</td>
                <td className="py-2 text-right tabular-nums">{qtyUnit(l.ordered, l.uom)}</td>
                <td className="py-2 text-right tabular-nums">{qtyUnit(l.delivered, l.uom)}</td>
                {anyReturned && <td className="py-2 text-right tabular-nums">{qtyUnit(n(l.returned), l.uom)}</td>}
              </tr>
            ))}
            {!lines.length && <tr><td colSpan={4} className="py-4 text-center text-zinc-500">{tx('No lines yet.')}</td></tr>}
          </tbody>
        </table>
        <p className="mt-6 text-sm text-zinc-600 dark:text-zinc-400">
          {receivedBy ? `${tx('Received by')} ${receivedBy}` : `${tx('Received in good order by')}: ________________   ${tx('Signature')}: ____________`}
        </p>
        {footnote && <p className="mt-1 text-sm text-zinc-600 dark:text-zinc-400">{footnote}</p>}
      </article>
    </div>
  );
}


/** One printable delivery note in the documents section. {@code preview} is the on-screen note shown in a dialog. */
export interface NoteDoc {
  key: string; number: string; caption?: string | null; preview?: ReactNode; onPrint?: () => void; onDownload?: () => void;
}

/** Where the delivery's uploaded documents are stored (a DMS record type of its own, apart from the record's files). */
export interface DeliveryAttachments { resType: string; resId: string; writePermission?: string }

/**
 * Delivery documents of a document's delivery details: the printable delivery note(s) — preview, print, PDF — and
 * the uploaded delivery documents (signed delivery note, proof of delivery, waybill, photos).
 */
export function DeliveryDocuments({ notes, emptyNote, attachments }: {
  notes: NoteDoc[]; emptyNote?: string; attachments?: DeliveryAttachments;
}) {
  const [open, setOpen] = useState<NoteDoc | null>(null);
  return (
    <Section icon={FolderOpen} title={tx('Delivery documents')}>
      <div className={cn('grid gap-5', attachments && 'lg:grid-cols-2')}>
        <div className="space-y-2">
          <span className="flex items-center gap-1.5 text-sm font-medium"><FileText className="h-4 w-4" />{tx('Delivery note')}</span>
          {notes.length ? (
            <ul className="divide-y rounded-md border">
              {notes.map((d) => (
                <li key={d.key} className="flex flex-wrap items-center gap-2 px-3 py-2 text-sm">
                  <FileText className="h-4 w-4 shrink-0 text-primary" />
                  <span className="min-w-0 flex-1">
                    <span className="font-medium">{d.number}</span>
                    {d.caption && <span className="ml-2 text-xs text-muted-foreground">{d.caption}</span>}
                  </span>
                  {d.preview && <Button size="sm" variant="ghost" className="h-8" onClick={() => setOpen(d)}><Eye className="mr-1 h-4 w-4" />{tx('Preview')}</Button>}
                  {d.onPrint && <Button size="sm" variant="outline" className="h-8" onClick={d.onPrint}><Printer className="mr-1 h-4 w-4" />{tx('Print')}</Button>}
                  {d.onDownload && <Button size="sm" variant="outline" className="h-8" onClick={d.onDownload}><Download className="mr-1 h-4 w-4" />{tx('PDF')}</Button>}
                </li>
              ))}
            </ul>
          ) : (
            <p className="rounded-md border border-dashed px-3 py-4 text-sm text-muted-foreground">{emptyNote ?? tx('No delivery note yet.')}</p>
          )}
          <p className="text-xs text-muted-foreground">{tx('Print it to travel with the goods; the customer signs on receipt.')}</p>
        </div>
        {attachments && (
          <AttachmentPanel resType={attachments.resType} resId={attachments.resId} writePermission={attachments.writePermission}
            title={tx('Delivery attachments')} tags={['Delivery']} bare />
        )}
      </div>
      <Dialog open={!!open} onOpenChange={(o) => !o && setOpen(null)}>
        <DialogContent className="max-h-[90vh] max-w-3xl overflow-y-auto">
          <DialogHeader><DialogTitle>{tx('Delivery note')} {open?.number}</DialogTitle></DialogHeader>
          {open?.preview}
        </DialogContent>
      </Dialog>
    </Section>
  );
}

const KIND_LABEL: Record<ReturnView['kind'], string> = {
  sales_return: tx('Sales return'), credit_note: tx('Credit note'), void: tx('Sale voided — goods back'),
};

const REASON_LABEL: Record<string, string> = {
  wrong_grade: tx('Wrong grade'), damaged: tx('Damaged'), short_quantity: tx('Short quantity'), excess_quantity: tx('Excess quantity'),
  quality_rejection: tx('Quality rejection'), other: tx('Other'),
};

/** Returns raised against the document, each with its products and quantities. */
export function ReturnsList({ returns, currency, empty }: { returns: ReturnView[]; currency?: string; empty: string }) {
  if (!returns.length) {
    return (
      <div className="flex flex-col items-center justify-center gap-2 rounded-xl border border-dashed py-10 text-center text-sm text-muted-foreground">
        <Undo2 className="h-6 w-6" />{empty}
      </div>
    );
  }
  return (
    <ul className="space-y-3">
      {returns.map((r) => {
        const pending = r.counted === false || ['requested', 'approved', 'draft', 'pending_approval'].includes(r.status);
        return (
          <li key={`${r.kind}-${r.id}`} className="rounded-xl border">
            <div className="flex flex-wrap items-center justify-between gap-2 border-b bg-muted/30 px-4 py-2.5">
              <div className="flex flex-wrap items-center gap-2">
                <Undo2 className="h-4 w-4 text-orange-600" />
                <span className="font-semibold">{r.number}</span>
                <span className="text-xs text-muted-foreground">{KIND_LABEL[r.kind]}</span>
                <span className={cn('rounded-full border px-2 py-0.5 text-[11px] font-medium capitalize',
                  pending ? 'border-amber-200 bg-amber-50 text-amber-800 dark:border-amber-900 dark:bg-amber-950/40 dark:text-amber-300'
                    : 'border-orange-200 bg-orange-50 text-orange-700 dark:border-orange-900 dark:bg-orange-950/40 dark:text-orange-300')}>
                  {r.status.replace(/_/g, ' ')}</span>
              </div>
              <span className="text-xs text-muted-foreground">
                {r.date ? fmtDate(r.date) : ''}{r.total != null && currency ? ` · ${money(r.total, currency)}` : ''}
              </span>
            </div>
            {(r.reason || r.outcome) && (
              <p className="px-4 pt-2 text-xs text-muted-foreground">
                {r.reason ? `${tx('Reason')}: ${REASON_LABEL[r.reason] ?? r.reason}` : ''}{r.reason && r.outcome ? ' · ' : ''}
                {r.outcome ? `${tx('Outcome')}: ${r.outcome.replace(/_/g, ' ')}` : ''}
              </p>
            )}
            <table className="w-full text-sm">
              <thead className="text-left text-[11px] uppercase tracking-wide text-muted-foreground">
                <tr><th className="px-4 py-2">{tx('Product')}</th><th className="px-3 py-2 text-right">{tx('Returned')}</th>
                  {r.kind === 'sales_return' && <><th className="px-3 py-2 text-right">{tx('Received')}</th><th className="px-4 py-2">{tx('Disposition')}</th></>}</tr>
              </thead>
              <tbody>
                {r.lines.map((l, i) => (
                  <tr key={i} className="border-t">
                    <td className="px-4 py-2">{l.productName ?? l.description}</td>
                    <td className="px-3 py-2 text-right tabular-nums">{qtyUnit(l.quantity, l.uomCode)}</td>
                    {r.kind === 'sales_return' && <>
                      <td className="px-3 py-2 text-right tabular-nums">{qty(l.receivedQty)}</td>
                      <td className="px-4 py-2 capitalize">{l.disposition ?? '—'}</td>
                    </>}
                  </tr>
                ))}
              </tbody>
            </table>
            {r.counted === false && <p className="px-4 pb-2 text-[11px] text-amber-700 dark:text-amber-400">{tx('Not counted until the credit note is posted.')}</p>}
          </li>
        );
      })}
    </ul>
  );
}
