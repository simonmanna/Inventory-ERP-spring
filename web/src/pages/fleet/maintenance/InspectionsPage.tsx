import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, ClipboardCheck, Plus, RefreshCw } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { DataTable, type Column } from '@/components/data-table';
import {
  DataTablePagination, FilterSelect, ListCard, ListPageHeader, ListToolbar, StatusPill, useListState,
} from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { dateTime } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { INSPECTION_KIND_LABEL, useInspection, useInspections, type Inspection } from '@/features/fleet/maintenance-api';
import { num } from '../fleet-format';
import { DefectStatusPill, SeverityPill, unitLabel } from './maint-ui';

const RESULT_LABEL = { pass: tx('Passed'), fail: tx('Failed') };

export const ResultPill = ({ result }: { result: 'pass' | 'fail' }) => (
  <StatusPill tone={result === 'pass' ? 'success' : 'danger'}>{RESULT_LABEL[result]}</StatusPill>
);

export function inspectionColumns(withVehicle: boolean): Column<Inspection>[] {
  return [
    { key: 'code', header: tx('Number'), render: (i) => <span className="font-medium text-primary">{i.code}</span> },
    ...(withVehicle ? [{ key: 'unit', header: tx('Vehicle / equipment'), render: (i: Inspection) => unitLabel(i.vehicle) }] : []),
    { key: 'when', header: tx('When'), render: (i) => <span className="whitespace-nowrap">{dateTime(i.inspectedAt)}</span> },
    { key: 'kind', header: tx('Kind'), render: (i) => INSPECTION_KIND_LABEL[i.kind] },
    { key: 'by', header: tx('Inspector'), render: (i) => i.inspectorName ?? '—' },
    { key: 'result', header: tx('Result'), render: (i) => <ResultPill result={i.result} /> },
    { key: 'defects', header: tx('Defects'), className: 'text-right', render: (i) => i.defectCount },
  ];
}

/** Inspection records (DVIR) with pass / fail and the defects they raised (fleet 1.6). */
export function InspectionsPage() {
  const navigate = useNavigate();
  const canInspect = useAuthStore((s) => s.hasPermission(PERMISSIONS.fleetMaintenance.inspect));
  const list = useListState({ result: '' });
  const { data, isLoading, isError } = useInspections({ page: list.page, pageSize: list.pageSize, result: list.filters.result || undefined });
  return (
    <div className="space-y-4">
      <ListPageHeader icon={ClipboardCheck} title={tx('Inspections')}
        description={tx('Pre-trip and periodic checks by drivers and inspectors.')}
        actions={canInspect && <Button onClick={() => navigate('/fleet/inspections/new')}><Plus className="mr-1.5 h-4 w-4" /> {tx('New inspection')}</Button>} />
      <ListToolbar>
        <FilterSelect value={list.filters.result} onChange={(v) => list.setFilter('result', v)}
          options={[{ value: 'pass', label: RESULT_LABEL.pass }, { value: 'fail', label: RESULT_LABEL.fail }]} allLabel={tx('Any result')} />
      </ListToolbar>
      <ListCard>
        <DataTable columns={inspectionColumns(true)} data={data?.data ?? []} loading={isLoading} getRowId={(i) => i.id}
          onRowClick={(i) => navigate(`/fleet/inspections/${i.id}`)}
          emptyMessage={isError ? tx('Could not load the inspections.') : tx('No inspections recorded yet.')} />
        {data?.meta && (
          <DataTablePagination page={data.meta.page} pageSize={list.pageSize} total={data.meta.total}
            totalPages={data.meta.totalPages} onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="inspection" />
        )}
      </ListCard>
    </div>
  );
}

const ITEM_RESULT_LABEL = { pass: tx('OK'), fail: tx('Fail'), na: tx('N/A') };

/** One inspection: answers per item, defects, photos. */
export function InspectionDetailPage() {
  const { id = '' } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { data: i, isLoading, isError, refetch } = useInspection(id);
  if (isLoading) return <Skeleton className="h-64 w-full" />;
  if (isError || !i) {
    return <div className="p-8 text-center text-muted-foreground"><p>{tx('Inspection not found')}</p><Button variant="link" onClick={() => refetch()}><RefreshCw className="mr-1 h-4 w-4" />{tx('Retry')}</Button></div>;
  }
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center gap-3">
        <Button variant="ghost" size="icon" aria-label={tx('Back')} onClick={() => navigate('/fleet/inspections')}><ArrowLeft className="h-5 w-5" /></Button>
        <h1 className="text-2xl font-bold">{i.code}</h1>
        <ResultPill result={i.result} />
        <span className="text-sm text-muted-foreground">
          <Link className="text-primary hover:underline" to={`/fleet/vehicles/${i.vehicleId}`}>{unitLabel(i.vehicle)}</Link> · {dateTime(i.inspectedAt)}
          {i.odometer ? ` · ${num(i.odometer, 0)} km` : ''}{i.hours ? ` · ${num(i.hours, 1)} h` : ''}
        </span>
      </div>
      <div className="grid grid-cols-1 gap-4 lg:grid-cols-3">
        <Card className="lg:col-span-2">
          <CardHeader><CardTitle className="text-base">{tx('Checklist')}</CardTitle></CardHeader>
          <CardContent className="space-y-1 text-sm">
            {(i.items ?? []).map((it) => (
              <div key={it.code} className="flex flex-wrap items-center justify-between gap-2 border-b py-1.5 last:border-0">
                <span>{it.label}{it.notes && <span className="block text-xs text-muted-foreground">{it.notes}</span>}</span>
                <StatusPill tone={it.result === 'fail' ? 'danger' : it.result === 'pass' ? 'success' : 'neutral'}>{ITEM_RESULT_LABEL[it.result]}</StatusPill>
              </div>
            ))}
            {i.notes && <p className="pt-2 text-muted-foreground">{i.notes}</p>}
          </CardContent>
        </Card>
        <div className="space-y-4">
          <Card>
            <CardHeader><CardTitle className="text-base">{tx('Defects')}</CardTitle></CardHeader>
            <CardContent className="space-y-2 text-sm">
              {(i.defects ?? []).map((d) => (
                <div key={d.id} className="flex flex-wrap items-center gap-2"><span className="font-medium">{d.code}</span>{d.title}<SeverityPill severity={d.severity} /><DefectStatusPill status={d.status} /></div>
              ))}
              {(i.defects ?? []).length === 0 && <p className="text-muted-foreground">{tx('No defects.')}</p>}
            </CardContent>
          </Card>
          {(i.photos ?? []).length > 0 && (
            <Card>
              <CardHeader><CardTitle className="text-base">{tx('Photos')}</CardTitle></CardHeader>
              <CardContent className="grid grid-cols-2 gap-2">
                {(i.photos ?? []).map((p) => <a key={p.attachmentId} href={p.url} target="_blank" rel="noreferrer"><img src={p.url} alt={p.filename} className="h-28 w-full rounded object-cover" /></a>)}
              </CardContent>
            </Card>
          )}
        </div>
      </div>
    </div>
  );
}
