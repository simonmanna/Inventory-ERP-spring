import { useState } from 'react';
import { ChevronRight, Plus } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { PERMISSIONS } from '@erp/shared';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Card, CardContent } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { useCreateFiscalYear, useFiscalYears, type FiscalYear } from '@/features/accounting/api';
import { date } from '@/lib/format';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';

const PERIODICITY: Record<FiscalYear['periodicity'], string> = {
  monthly: tx('Monthly'),
  quarterly: tx('Quarterly'),
  yearly: tx('One period'),
};

/** Fiscal years with their periods; each opens its year-end close (ADR-S023). */
export default function FiscalYearsPage() {
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const { data, isLoading } = useFiscalYears();
  const create = useCreateFiscalYear();
  const [open, setOpen] = useState(false);
  const [name, setName] = useState('');
  const [startDate, setStartDate] = useState('');
  const [periodicity, setPeriodicity] = useState<FiscalYear['periodicity']>('monthly');

  const submit = async () => {
    await create.mutateAsync({ name: name || undefined, startDate, periodicity });
    setOpen(false);
    setName('');
    setStartDate('');
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-semibold">{tx('Fiscal years')}</h1>
          <p className="text-sm text-muted-foreground">
            {tx('Each year groups its periods. Close the periods, then close the year to carry the balances forward.')}
          </p>
        </div>
        {has(PERMISSIONS.fiscalPeriod.create) && (
          <Button onClick={() => setOpen(true)}>
            <Plus className="mr-2 h-4 w-4" aria-hidden />
            {tx('New fiscal year')}
          </Button>
        )}
      </div>

      <Card>
        <CardContent className="p-0">
          {isLoading ? (
            <div className="space-y-2 p-4">
              <Skeleton className="h-8 w-full" />
              <Skeleton className="h-8 w-full" />
            </div>
          ) : !data?.length ? (
            <p className="p-6 text-sm text-muted-foreground">{tx('No fiscal years yet.')}</p>
          ) : (
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>{tx('Name')}</TableHead>
                  <TableHead>{tx('Dates')}</TableHead>
                  <TableHead>{tx('Periods')}</TableHead>
                  <TableHead>{tx('Status')}</TableHead>
                  <TableHead className="text-right">{tx('Net income')}</TableHead>
                  <TableHead />
                </TableRow>
              </TableHeader>
              <TableBody>
                {data.map((y) => (
                  <TableRow key={y.id} className="cursor-pointer" onClick={() => navigate(`/fiscal-years/${y.id}`)}>
                    <TableCell className="font-medium">{y.name}</TableCell>
                    <TableCell>{date(y.startDate)} – {date(y.endDate)}</TableCell>
                    <TableCell>
                      {y.periods.length} · {PERIODICITY[y.periodicity]}
                      {y.openPeriods > 0 && <span className="text-muted-foreground"> · {y.openPeriods} {tx('open')}</span>}
                    </TableCell>
                    <TableCell>
                      <Badge variant={y.status === 'open' ? 'default' : 'secondary'}>
                        {y.status === 'open' ? tx('Open') : y.lockedPeriods ? tx('Closed and locked') : tx('Closed')}
                      </Badge>
                    </TableCell>
                    <TableCell className="text-right tabular-nums">{y.netIncome ?? '—'}</TableCell>
                    <TableCell className="w-8"><ChevronRight className="h-4 w-4 text-muted-foreground" aria-hidden /></TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          )}
        </CardContent>
      </Card>

      <Dialog open={open} onOpenChange={setOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{tx('New fiscal year')}</DialogTitle>
            <DialogDescription>
              {tx('The year runs twelve months from its start. Periods already inside it join the year; the gaps get new periods.')}
            </DialogDescription>
          </DialogHeader>
          <div className="space-y-3">
            <div className="space-y-1">
              <Label htmlFor="fy-start">{tx('Start date')}</Label>
              <Input id="fy-start" type="date" value={startDate} onChange={(e) => setStartDate(e.target.value)} />
            </div>
            <div className="space-y-1">
              <Label htmlFor="fy-name">{tx('Name (optional)')}</Label>
              <Input id="fy-name" placeholder="FY2027" value={name} onChange={(e) => setName(e.target.value)} />
            </div>
            <div className="space-y-1">
              <Label htmlFor="fy-periodicity">{tx('Periods')}</Label>
              <select
                id="fy-periodicity"
                className="h-9 w-full rounded-md border bg-background px-2 text-sm"
                value={periodicity}
                onChange={(e) => setPeriodicity(e.target.value as FiscalYear['periodicity'])}
              >
                {Object.entries(PERIODICITY).map(([k, v]) => <option key={k} value={k}>{v}</option>)}
              </select>
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setOpen(false)}>{tx('Cancel')}</Button>
            <Button onClick={() => void submit()} disabled={!startDate || create.isPending}>{tx('Create')}</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
