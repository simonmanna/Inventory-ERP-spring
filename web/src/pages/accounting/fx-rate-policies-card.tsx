import { useEffect, useState } from 'react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import {
  RATE_TYPES, useFxRatePolicies, useSaveFxRatePolicy, type FxRatePolicy, type RateType,
} from '@/features/accounting/api';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';

/** FX-2 B: which rate type each kind of transaction uses, whether rates need approval, and stated-rate tolerance. */
export function FxRatePoliciesCard() {
  const { data } = useFxRatePolicies();
  const save = useSaveFxRatePolicy();
  const canEdit = useAuthStore((s) => s.hasPermission(PERMISSIONS.currency.policy));
  const [rows, setRows] = useState<FxRatePolicy[]>([]);
  useEffect(() => { if (data) setRows(data); }, [data]);

  const patch = (i: number, p: Partial<FxRatePolicy>) =>
    setRows((rs) => rs.map((r, j) => (j === i ? { ...r, ...p } : r)));

  return (
    <Card>
      <CardHeader className="pb-2 pt-4 px-5 bg-muted/30 border-b rounded-t-lg">
        <CardTitle className="text-xs font-bold text-muted-foreground uppercase tracking-wider">
          {tx('Exchange-rate policies')}
        </CardTitle>
      </CardHeader>
      <CardContent className="p-0 overflow-x-auto">
        <Table>
          <TableHeader>
            <TableRow className="bg-muted/30">
              <TableHead className="text-[10px] uppercase">{tx('Transaction')}</TableHead>
              <TableHead className="text-[10px] uppercase">{tx('Rate type')}</TableHead>
              <TableHead className="text-[10px] uppercase">{tx('Approved rates only')}</TableHead>
              <TableHead className="text-[10px] uppercase">{tx('Stated rate allowed')}</TableHead>
              <TableHead className="text-[10px] uppercase">{tx('Tolerance %')}</TableHead>
              <TableHead />
            </TableRow>
          </TableHeader>
          <TableBody>
            {rows.map((r, i) => (
              <TableRow key={r.context}>
                <TableCell className="text-xs font-medium">{tx(r.context.replace(/_/g, ' '))}</TableCell>
                <TableCell>
                  <select disabled={!canEdit} value={r.rateType}
                    onChange={(e) => patch(i, { rateType: e.target.value as RateType })}
                    className="h-8 rounded-md border border-input bg-background px-2 text-xs">
                    {RATE_TYPES.map((t) => <option key={t} value={t}>{tx(t)}</option>)}
                  </select>
                </TableCell>
                <TableCell>
                  <input type="checkbox" disabled={!canEdit} checked={r.requireApprovedRate}
                    onChange={(e) => patch(i, { requireApprovedRate: e.target.checked })} />
                </TableCell>
                <TableCell>
                  <input type="checkbox" disabled={!canEdit} checked={r.allowStatedRate}
                    onChange={(e) => patch(i, { allowStatedRate: e.target.checked })} />
                </TableCell>
                <TableCell>
                  <Input type="number" min="0" step="0.1" disabled={!canEdit} className="h-8 w-24 text-xs"
                    value={r.statedRateTolerancePct ?? ''} placeholder={tx('none')}
                    onChange={(e) => patch(i, { statedRateTolerancePct: e.target.value })} />
                </TableCell>
                <TableCell className="text-right pr-4">
                  {canEdit && (
                    <Button size="sm" variant="outline" disabled={save.isPending} onClick={() => save.mutate(r)}>
                      {tx('Save')}
                    </Button>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </CardContent>
    </Card>
  );
}
