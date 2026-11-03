import { useState } from 'react';
import { Link } from 'react-router-dom';
import { Download } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { Skeleton } from '@/components/ui/skeleton';
import { money } from '@/lib/format';
import { exportCSV } from '@/lib/export-csv';
import { useTrialBalance } from '@/features/accounting/api';
import { tx } from '@/lib/i18n/tx';

type View = 'post_closing' | 'pre_closing';

export function TrialBalancePage() {
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [view, setView] = useState<View>('post_closing');
  const { data, isLoading, isError, error } = useTrialBalance({
    from: from || undefined,
    to: to || undefined,
    view,
  });

  const onExport = () => {
    if (!data) return;
    exportCSV(
      `trial-balance-${to || new Date().toISOString().slice(0, 10)}-${view}.csv`,
      [tx('Code'), tx('Account'), tx('Debit'), tx('Credit'), tx('Balance')],
      [
        ...data.rows.map((r) => [r.code, r.name, r.debit, r.credit, r.balance]),
        ['', tx('Total'), data.totals.debit, data.totals.credit, ''],
      ],
    );
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold">{tx('Trial Balance')}</h1>
          <p className="text-sm text-muted-foreground">{tx('Net debit/credit per account (posted entries).')}</p>
        </div>
        <div className="flex items-center gap-2">
          {data && (
            <Badge variant={data.balanced ? 'default' : 'destructive'}>
              {data.balanced ? tx('Balanced') : tx('Out of balance')}
            </Badge>
          )}
          <Button variant="outline" size="sm" onClick={onExport} disabled={!data || data.rows.length === 0}>
            <Download className="mr-2 h-4 w-4" />
            {tx('Export CSV')}
          </Button>
        </div>
      </div>

      <div className="flex flex-wrap items-end gap-3 rounded-lg border bg-card p-3">
        <div className="space-y-1">
          <Label htmlFor="tb-from" className="text-xs text-muted-foreground">{tx('From')}</Label>
          <Input id="tb-from" type="date" value={from} onChange={(e) => setFrom(e.target.value)} className="h-9" />
        </div>
        <div className="space-y-1">
          <Label htmlFor="tb-to" className="text-xs text-muted-foreground">{tx('To')}</Label>
          <Input id="tb-to" type="date" value={to} onChange={(e) => setTo(e.target.value)} className="h-9" />
        </div>
        <div className="space-y-1">
          <Label htmlFor="tb-view" className="text-xs text-muted-foreground">{tx('View')}</Label>
          <select
            id="tb-view"
            value={view}
            onChange={(e) => setView(e.target.value as View)}
            className="h-9 rounded-md border border-input bg-background px-3 text-sm"
          >
            <option value="post_closing">{tx('After closing entries')}</option>
            <option value="pre_closing">{tx('Before closing entries')}</option>
          </select>
        </div>
        {(from || to) && (
          <Button variant="ghost" size="sm" onClick={() => { setFrom(''); setTo(''); }}>
            {tx('Clear')}
          </Button>
        )}
      </div>

      <div className="rounded-md border">
        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>{tx('Code')}</TableHead>
              <TableHead>{tx('Account')}</TableHead>
              <TableHead className="text-right">{tx('Debit')}</TableHead>
              <TableHead className="text-right">{tx('Credit')}</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {isLoading ? (
              Array.from({ length: 6 }).map((_, i) => (
                <TableRow key={i}>
                  <TableCell colSpan={4}>
                    <Skeleton className="h-4 w-full" />
                  </TableCell>
                </TableRow>
              ))
            ) : isError ? (
              <TableRow>
                <TableCell colSpan={4} className="h-24 text-center text-destructive">
                  {tx('Could not load the trial balance: {{v1}}', { v1: (error as Error)?.message ?? '' })}
                </TableCell>
              </TableRow>
            ) : !data || data.rows.length === 0 ? (
              <TableRow>
                <TableCell colSpan={4} className="h-24 text-center text-muted-foreground">
                  {tx('No posted entries yet.')}
                </TableCell>
              </TableRow>
            ) : (
              <>
                {data.rows.map((r) => (
                  <TableRow key={r.accountId}>
                    <TableCell>{r.code}</TableCell>
                    <TableCell>
                      <Link to={`/accounts/ledger/${r.accountId}`} className="hover:underline">
                        {r.name}
                      </Link>
                    </TableCell>
                    <TableCell className="text-right">{money(r.debit)}</TableCell>
                    <TableCell className="text-right">{money(r.credit)}</TableCell>
                  </TableRow>
                ))}
                <TableRow className="font-semibold">
                  <TableCell colSpan={2}>{tx('Total')}</TableCell>
                  <TableCell className="text-right">{money(data.totals.debit)}</TableCell>
                  <TableCell className="text-right">{money(data.totals.credit)}</TableCell>
                </TableRow>
              </>
            )}
          </TableBody>
        </Table>
      </div>
    </div>
  );
}
