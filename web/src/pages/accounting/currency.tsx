import { useState } from 'react';
import { AlertTriangle, CloudDownload, DollarSign, Plus, RefreshCw } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import {
  Table, TableBody, TableCell, TableHead, TableHeader, TableRow,
} from '@/components/ui/table';
import {
  Dialog, DialogContent, DialogHeader, DialogTitle, DialogTrigger,
} from '@/components/ui/dialog';
import { Skeleton } from '@/components/ui/skeleton';
import {
  useCurrencies, useCurrencyRates, useCreateCurrencyRate, usePendingRates, useDecideRate,
  RATE_TYPES, type RateType,
} from '@/features/accounting/api';
import { PERMISSIONS } from '@erp/shared';
import { FxRatePoliciesCard } from './fx-rate-policies-card';
import { useFetchRates, useMissingRates, useRateFetchStatus } from '@/features/accounting/fx-report-api';
import { apiMessage, notify } from '@/lib/notify';
import { useAuthStore } from '@/stores/auth.store';
import { format } from 'date-fns';
import { tx } from '@/lib/i18n/tx';

export function CurrencyPage() {
  const { data: currencies, isLoading: currLoading } = useCurrencies();
  // Rates are entered as 1 FROM = rate TO; the default pair is USD → the organisation's functional currency.
  const baseCode = useAuthStore((s) => s.organization?.currencyCode) || 'USD';
  const [fromCode, setFromCode] = useState(baseCode === 'USD' ? 'SSP' : 'USD');
  const [toCode, setToCode] = useState(baseCode);
  const [rate, setRate] = useState('');
  const [asOf, setAsOf] = useState(() => format(new Date(), 'yyyy-MM-dd'));
  const [rateType, setRateType] = useState<RateType>('official');
  const [dialogOpen, setDialogOpen] = useState(false);
  const { data: rates, isLoading: ratesLoading, refetch } = useCurrencyRates({ fromCode, toCode, rateType });
  const createRate = useCreateCurrencyRate();
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const canApprove = hasPermission(PERMISSIONS.currency.approve);
  const { data: pending } = usePendingRates();
  const decide = useDecideRate();
  // FX-8: used currencies without a recent approved rate, and the optional automatic fetch.
  const canRead = hasPermission(PERMISSIONS.currency.read);
  const canUpdate = hasPermission(PERMISSIONS.currency.update);
  const { data: missing } = useMissingRates(3, canRead);
  const { data: fetchStatus } = useRateFetchStatus(canRead);
  const fetchRates = useFetchRates();

  const handleFetch = () => {
    fetchRates.mutate(undefined, {
      onSuccess: (r) => {
        const parts = [tx('{{v1}} stored', { v1: r.stored.length })];
        if (r.skipped.length) parts.push(tx('{{v1}} kept (manual rate on file)', { v1: r.skipped.join(', ') }));
        if (r.unavailable.length) parts.push(tx('not offered: {{v1}}', { v1: r.unavailable.join(', ') }));
        notify.success(tx('Rates fetched ({{v1}}, {{v2}})', { v1: r.functionalCurrency, v2: r.rateType }), parts.join(' · '));
        void refetch();
      },
      onError: (e) => notify.error(apiMessage(e, tx('Could not fetch rates'))),
    });
  };

  const handleAddRate = async () => {
    if (!fromCode || !toCode || !rate || !asOf || Number(rate) <= 0) return;
    await createRate.mutateAsync({ fromCode, toCode, rate: Number(rate), asOf, rateType });
    setRate('');
    setDialogOpen(false);
  };

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div className="border-l-4 border-[#3b82f6] pl-4 space-y-1">
          <h1 className="text-3xl font-bold tracking-tight text-gray-900">{tx('Currency Management')}</h1>
          <p className="text-sm text-gray-500">{tx('Global currency catalog and exchange rate management.')}</p>
        </div>
        <div className="flex items-center gap-2">
          <Dialog open={dialogOpen} onOpenChange={setDialogOpen}>
            <DialogTrigger asChild>
              <Button size="sm" className="gap-1.5">
                <Plus className="h-3.5 w-3.5" /> {tx('Add Rate')}
              </Button>
            </DialogTrigger>
            <DialogContent>
              <DialogHeader>
                <DialogTitle className="text-sm font-bold uppercase tracking-wider">{tx('Add Exchange Rate')}</DialogTitle>
              </DialogHeader>
              <div className="space-y-3 pt-2">
                <div className="grid grid-cols-2 gap-3">
                  <div className="space-y-1">
                    <Label className="text-[10px] font-bold text-muted-foreground uppercase">{tx('From')}</Label>
                    <select
                      value={fromCode}
                      onChange={(e) => setFromCode(e.target.value)}
                      className="w-full h-9 rounded-md border border-input bg-background px-3 text-xs"
                    >
                      {currencies?.map((c) => (
                        <option key={c.code} value={c.code}>{c.code} — {c.name}</option>
                      ))}
                    </select>
                  </div>
                  <div className="space-y-1">
                    <Label className="text-[10px] font-bold text-muted-foreground uppercase">{tx('To')}</Label>
                    <select
                      value={toCode}
                      onChange={(e) => setToCode(e.target.value)}
                      className="w-full h-9 rounded-md border border-input bg-background px-3 text-xs"
                    >
                      {currencies?.map((c) => (
                        <option key={c.code} value={c.code}>{c.code} — {c.name}</option>
                      ))}
                    </select>
                  </div>
                </div>
                <div className="grid grid-cols-2 gap-3">
                  <div className="space-y-1">
                    <Label className="text-[10px] font-bold text-muted-foreground uppercase">{tx('Rate')}</Label>
                    <Input type="number" step="0.00000001" min="0" value={rate}
                      onChange={(e) => setRate(e.target.value)} placeholder="e.g. 4650.50" className="h-9 text-xs" />
                  </div>
                  <div className="space-y-1">
                    <Label className="text-[10px] font-bold text-muted-foreground uppercase">{tx('Effective date')}</Label>
                    <Input type="date" value={asOf} onChange={(e) => setAsOf(e.target.value)} className="h-9 text-xs" />
                  </div>
                </div>
                <div className="space-y-1">
                  <Label className="text-[10px] font-bold text-muted-foreground uppercase">{tx('Rate type')}</Label>
                  <select value={rateType} onChange={(e) => setRateType(e.target.value as RateType)}
                    className="w-full h-9 rounded-md border border-input bg-background px-3 text-xs">
                    {RATE_TYPES.map((t) => <option key={t} value={t}>{tx(t)}</option>)}
                  </select>
                </div>
                {rate && Number(rate) > 0 && (
                  <p className="text-xs text-muted-foreground">1 {fromCode} = {rate} {toCode}</p>
                )}
                <div className="flex justify-end gap-2 pt-2">
                  <Button variant="outline" size="sm" onClick={() => setDialogOpen(false)}>{tx('Cancel')}</Button>
                  <Button size="sm" onClick={handleAddRate} disabled={!rate || Number(rate) <= 0 || !asOf || createRate.isPending}>
                    {createRate.isPending ? tx('Adding...') : tx('Add Rate')}
                  </Button>
                </div>
              </div>
            </DialogContent>
          </Dialog>
          {fetchStatus?.configured && canUpdate && (
            <Button variant="outline" size="sm" onClick={handleFetch} disabled={fetchRates.isPending} className="gap-1.5">
              <CloudDownload className="h-3.5 w-3.5" /> {fetchRates.isPending ? tx('Fetching…') : tx("Fetch today's rates")}
            </Button>
          )}
          <Button variant="outline" size="sm" onClick={() => refetch()} className="gap-1.5">
            <RefreshCw className="h-3.5 w-3.5" /> {tx('Refresh')}
          </Button>
        </div>
      </div>

      {missing && missing.length > 0 && (
        <div className="flex items-start gap-2 rounded-md border border-amber-300 bg-amber-50 p-3 text-sm text-amber-900 dark:bg-amber-950/30 dark:text-amber-200">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
          <div>
            <p className="font-medium">{tx('No approved exchange rate in the last 3 days for:')}</p>
            <p>
              {missing.map((m) => `${m.currency} (${m.latestRateDate ? tx('last {{v1}}', { v1: format(new Date(m.latestRateDate), 'MMM d, yyyy') }) : tx('never')})`).join(' · ')}
            </p>
          </div>
        </div>
      )}

      <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
        {/* Currency Catalog */}
        <Card>
          <CardHeader className="pb-2 pt-4 px-5 bg-muted/30 border-b rounded-t-lg">
            <CardTitle className="text-xs font-bold text-muted-foreground uppercase tracking-wider">
              {tx('Currency Catalog')}
            </CardTitle>
          </CardHeader>
          <CardContent className="p-0">
            <Table>
              <TableHeader>
                <TableRow className="bg-muted/30">
                  <TableHead className="text-[10px] font-bold text-muted-foreground uppercase">{tx('Code')}</TableHead>
                  <TableHead className="text-[10px] font-bold text-muted-foreground uppercase">{tx('Name')}</TableHead>
                  <TableHead className="text-[10px] font-bold text-muted-foreground uppercase">{tx('Symbol')}</TableHead>
                  <TableHead className="text-[10px] font-bold text-muted-foreground uppercase text-right pr-4">{tx('Decimals')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {currLoading ? (
                  Array.from({ length: 5 }).map((_, i) => (
                    <TableRow key={i}><TableCell colSpan={4}><Skeleton className="h-4 w-full" /></TableCell></TableRow>
                  ))
                ) : !currencies || currencies.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={4} className="text-center py-8 text-muted-foreground">{tx('No currencies')}</TableCell>
                  </TableRow>
                ) : (
                  currencies.map((c) => (
                    <TableRow key={c.code}>
                      <TableCell className="text-xs font-bold font-mono">{c.code}</TableCell>
                      <TableCell className="text-xs">{c.name}</TableCell>
                      <TableCell className="text-xs">{c.symbol}</TableCell>
                      <TableCell className="text-right text-xs pr-4">{c.decimalPlaces}</TableCell>
                    </TableRow>
                  ))
                )}
              </TableBody>
            </Table>
          </CardContent>
        </Card>

        {/* Exchange Rates */}
        <Card>
          <CardHeader className="pb-2 pt-4 px-5 bg-muted/30 border-b rounded-t-lg">
            <CardTitle className="text-xs font-bold text-muted-foreground uppercase tracking-wider">
              {tx('Exchange Rates')} · {fromCode} → {toCode} · {tx(rateType)}
            </CardTitle>
          </CardHeader>
          <CardContent className="p-0">
            <Table>
              <TableHeader>
                <TableRow className="bg-muted/30">
                  <TableHead className="text-[10px] font-bold text-muted-foreground uppercase">{tx('From')}</TableHead>
                  <TableHead className="text-[10px] font-bold text-muted-foreground uppercase">{tx('To')}</TableHead>
                  <TableHead className="text-[10px] font-bold text-muted-foreground uppercase text-right">{tx('Rate')}</TableHead>
                  <TableHead className="text-[10px] font-bold text-muted-foreground uppercase text-right pr-4">{tx('As Of')}</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {ratesLoading ? (
                  Array.from({ length: 4 }).map((_, i) => (
                    <TableRow key={i}><TableCell colSpan={4}><Skeleton className="h-4 w-full" /></TableCell></TableRow>
                  ))
                ) : !rates || rates.length === 0 ? (
                  <TableRow>
                    <TableCell colSpan={4} className="text-center py-8 text-muted-foreground">
                      <DollarSign className="h-6 w-6 mx-auto mb-1 opacity-30" />
                      <p className="text-sm font-medium">{tx('No rates configured')}</p>
                      <p className="text-xs">{tx('Add exchange rates to enable multi-currency transactions.')}</p>
                    </TableCell>
                  </TableRow>
                ) : (
                  rates.map((r) => (
                    <TableRow key={`${r.asOf}-${r.source}`}>
                      <TableCell className="text-xs font-bold font-mono">{fromCode}</TableCell>
                      <TableCell className="text-xs font-bold font-mono">{toCode}</TableCell>
                      <TableCell className="text-right text-xs font-semibold">
                        {Number(r.rate).toLocaleString(undefined, { maximumFractionDigits: 8 })}
                      </TableCell>
                      <TableCell className="text-right text-xs text-muted-foreground pr-4">
                        {format(new Date(r.asOf), 'MMM d, yyyy')}
                      </TableCell>
                    </TableRow>
                  ))
                )}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      </div>

      {pending && pending.length > 0 && (
        <Card>
          <CardHeader className="pb-2 pt-4 px-5 bg-muted/30 border-b rounded-t-lg">
            <CardTitle className="text-xs font-bold text-muted-foreground uppercase tracking-wider">
              {tx('Rates waiting for approval')}
            </CardTitle>
          </CardHeader>
          <CardContent className="p-0">
            <Table>
              <TableBody>
                {pending.map((r) => (
                  <TableRow key={r.id}>
                    <TableCell className="text-xs font-mono">{r.rateType}</TableCell>
                    <TableCell className="text-xs font-semibold">
                      {Number(r.rate).toLocaleString(undefined, { maximumFractionDigits: 8 })}
                    </TableCell>
                    <TableCell className="text-xs text-muted-foreground">{format(new Date(r.asOf), 'MMM d, yyyy')}</TableCell>
                    <TableCell className="text-right pr-4">
                      {canApprove && (
                        <div className="flex justify-end gap-2">
                          <Button size="sm" variant="outline" disabled={decide.isPending}
                            onClick={() => decide.mutate({ id: r.id, approve: false })}>{tx('Reject')}</Button>
                          <Button size="sm" disabled={decide.isPending}
                            onClick={() => decide.mutate({ id: r.id, approve: true })}>{tx('Approve')}</Button>
                        </div>
                      )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </CardContent>
        </Card>
      )}

      <FxRatePoliciesCard />
    </div>
  );
}
