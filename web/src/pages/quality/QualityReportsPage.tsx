import { useState } from 'react';
import { BarChart3 } from 'lucide-react';
import { Input } from '@/components/ui/input';
import { Card, CardContent } from '@/components/ui/card';
import { DataTable, type Column } from '@/components/data-table';
import { ListCard, ListPageHeader } from '@/components/list';
import { tx } from '@/lib/i18n/tx';
import { STAGE_LABEL, useQualityReport, type ReportRow } from '@/features/quality/api';
import { Field, OptionalSelect } from './quality-ui';
import { num, toOptions, today } from './quality-format';

const GROUPS: Record<string, string> = {
  product: tx('Product'), batch: tx('Batch'), work_center: tx('Line / work centre'), supplier: tx('Supplier'),
};

const monthAgo = () => {
  const d = new Date();
  d.setDate(d.getDate() - 30);
  return new Date(d.getTime() - d.getTimezoneOffset() * 60_000).toISOString().slice(0, 10);
};

/** Quality reports (ADR-S013): pass rate, defects and NCRs by product, batch, line or supplier. */
export function QualityReportsPage() {
  const [groupBy, setGroupBy] = useState('product');
  const [stage, setStage] = useState('');
  const [from, setFrom] = useState(monthAgo());
  const [to, setTo] = useState(today());
  const { data, isLoading, isError } = useQualityReport({ groupBy, stage: stage || undefined, from: from || undefined, to: to || undefined });
  const columns: Column<ReportRow>[] = [
    { key: 'label', header: GROUPS[groupBy], render: (r) => <span className="font-medium">{r.label}</span> },
    { key: 'lots', header: tx('Lots'), className: 'text-right', render: (r) => r.lots },
    { key: 'evaluated', header: tx('Evaluated'), className: 'text-right', render: (r) => r.evaluated },
    { key: 'passed', header: tx('Passed'), className: 'text-right', render: (r) => r.passed },
    { key: 'failed', header: tx('Failed'), className: 'text-right', render: (r) => <span className={r.failed > 0 ? 'font-medium text-rose-600' : ''}>{r.failed}</span> },
    { key: 'rate', header: tx('Pass rate'), className: 'text-right', render: (r) => (r.passRate != null ? `${num(r.passRate, 1)} %` : '—') },
    { key: 'qty', header: tx('Quantity'), className: 'text-right', render: (r) => num(r.quantity, 2) },
    { key: 'defects', header: tx('Rejected + downgraded'), className: 'text-right', render: (r) => num(r.defectQty, 2) },
    { key: 'ncr', header: tx('NCRs'), className: 'text-right', render: (r) => r.ncrCount },
  ];
  const t = data?.totals;
  return (
    <div className="space-y-4">
      <ListPageHeader icon={BarChart3} title={tx('Quality Reports')} description={tx('Inspection lots opened in the period, grouped as chosen.')} />
      <Card>
        <CardContent className="grid grid-cols-2 gap-3 pt-6 sm:grid-cols-4">
          <Field label={tx('Group by')}><OptionalSelect value={groupBy} onChange={(v) => setGroupBy(v || 'product')} placeholder={tx('Group by')} options={toOptions(GROUPS)} /></Field>
          <Field label={tx('Stage')}><OptionalSelect value={stage} onChange={setStage} placeholder={tx('All stages')} noneLabel={tx('All stages')} options={toOptions(STAGE_LABEL)} /></Field>
          <Field label={tx('From')}><Input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></Field>
          <Field label={tx('To')}><Input type="date" value={to} onChange={(e) => setTo(e.target.value)} /></Field>
        </CardContent>
      </Card>
      {t && (
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-5">
          {[
            [tx('Lots'), t.lots], [tx('Passed'), t.passed], [tx('Failed'), t.failed],
            [tx('Pass rate'), t.passRate != null ? `${num(t.passRate, 1)} %` : '—'], [tx('NCRs'), t.ncrCount],
          ].map(([k, v]) => (
            <Card key={String(k)}><CardContent className="pt-4"><p className="text-xs text-muted-foreground">{k}</p><p className="text-2xl font-semibold tabular-nums">{v}</p></CardContent></Card>
          ))}
        </div>
      )}
      <ListCard>
        <DataTable columns={columns} data={data?.rows ?? []} loading={isLoading} getRowId={(r) => r.key || r.label} cellClassName="py-2.5 px-4" headerRowClassName="h-10"
          emptyMessage={isError ? tx('Could not load the report.') : tx('No inspection lots in this period.')} />
      </ListCard>
    </div>
  );
}
