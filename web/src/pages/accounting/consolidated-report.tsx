import { useState } from 'react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Skeleton } from '@/components/ui/skeleton';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { useConsolidated, localDay } from '@/features/finance/api';
import { money } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';

export function ConsolidatedReportPage() {
  const now = new Date();
  const [from, setFrom] = useState(localDay(new Date(now.getFullYear(), 0, 1)));
  const [to, setTo] = useState(localDay(now));
  const [statement, setStatement] = useState<'pl' | 'tb'>('pl');
  const { data, isLoading } = useConsolidated({ from, to, statement });

  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-2xl font-semibold">{tx('Branch Consolidation')}</h1>
        <p className="text-sm text-muted-foreground">
          {tx('Each branch side by side with the consolidated total. Lines without a branch appear as Unassigned.')}
        </p>
      </div>
      <div className="flex flex-wrap items-end gap-3 rounded-lg border p-3">
        <div className="space-y-1">
          <Label>{tx('From')}</Label>
          <Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} className="h-9" />
        </div>
        <div className="space-y-1">
          <Label>{tx('To')}</Label>
          <Input type="date" value={to} onChange={(e) => setTo(e.target.value)} className="h-9" />
        </div>
        <div className="flex gap-1">
          <Button variant={statement === 'pl' ? 'default' : 'outline'} onClick={() => setStatement('pl')}>
            {tx('Profit & Loss')}
          </Button>
          <Button variant={statement === 'tb' ? 'default' : 'outline'} onClick={() => setStatement('tb')}>
            {tx('Trial Balance')}
          </Button>
        </div>
      </div>

      {isLoading || !data ? (
        <Skeleton className="h-48 w-full" />
      ) : (
        <div className="overflow-x-auto rounded-md border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="min-w-[220px]">{tx('Account')}</TableHead>
                {data.branches.map((b) => (
                  <TableHead key={b.id} className="text-right">
                    {b.name}
                  </TableHead>
                ))}
                <TableHead className="text-right">{tx('Consolidated')}</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {data.rows.map((r) => (
                <TableRow key={r.accountId}>
                  <TableCell>
                    {r.code} · {r.name}
                  </TableCell>
                  {data.branches.map((b) => (
                    <TableCell key={b.id} className="text-right">
                      {money(r.byBranch[b.id])}
                    </TableCell>
                  ))}
                  <TableCell className="text-right font-medium">{money(r.total)}</TableCell>
                </TableRow>
              ))}
              {Object.entries(data.totals).map(([k, t]) => (
                <TableRow key={k} className="font-semibold">
                  <TableCell>{tx(({ netProfit: 'Net profit', net: 'Net (debit − credit)', income: 'Income', expense: 'Expenses' } as Record<string, string>)[k] ?? k)}</TableCell>
                  {data.branches.map((b) => (
                    <TableCell key={b.id} className="text-right">
                      {money(t.byBranch[b.id])}
                    </TableCell>
                  ))}
                  <TableCell className="text-right">{money(t.total)}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
    </div>
  );
}
