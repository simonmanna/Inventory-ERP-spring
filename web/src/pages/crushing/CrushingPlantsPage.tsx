import { useNavigate } from 'react-router-dom';
import { Cog, Plus } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { DataTable, type Column } from '@/components/data-table';
import { ListCard, ListPageHeader } from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { tx } from '@/lib/i18n/tx';
import { usePlants, type Plant } from '@/features/crushing/api';
import { qty } from './crushing-ui';

/** Crushing plants / crusher lines with their circuit size and active grading profiles. */
export function CrushingPlantsPage() {
  const navigate = useNavigate();
  const has = useAuthStore((s) => s.hasPermission);
  const { data, isLoading, isError } = usePlants(true);
  const open = (p: Plant) => navigate(`/manufacturing/crushing/plants/${p.id}`);
  const columns: Column<Plant>[] = [
    { key: 'code', header: tx('Code'), render: (p) => <span className="font-medium text-primary">{p.code}</span> },
    { key: 'name', header: tx('Name'), render: (p) => p.name },
    { key: 'site', header: tx('Site'), render: (p) => p.siteCode ?? '—' },
    { key: 'feed', header: tx('Feed stockpile'), render: (p) => p.feedStockpileCode ?? '—' },
    { key: 'capacity', header: tx('Design capacity'), className: 'text-right', render: (p) => (p.designCapacityTph ? `${qty(p.designCapacityTph)} t/h` : '—') },
    { key: 'stages', header: tx('Stages'), className: 'text-right', render: (p) => p.stageCount },
    { key: 'units', header: tx('Units'), className: 'text-right', render: (p) => p.unitCount },
    { key: 'profiles', header: tx('Active profiles'), className: 'text-right', render: (p) => p.activeProfileCount },
    { key: 'active', header: tx('Status'), render: (p) => (p.isActive ? tx('Active') : tx('Inactive')) },
  ];
  return (
    <div className="space-y-4">
      <ListPageHeader icon={Cog} title={tx('Crushing Plants')} description={tx('Crusher lines: stages, crushers, screens and conveyors as work centres with capacity and meters.')}
        actions={has(PERMISSIONS.crushing.setup) && <Button onClick={() => navigate('/manufacturing/crushing/plants/new')}><Plus className="mr-1.5 h-4 w-4" /> {tx('New plant')}</Button>} />
      <ListCard>
        <DataTable columns={columns} data={data ?? []} loading={isLoading} getRowId={(p) => p.id} onRowClick={open} cellClassName="py-2.5 px-4"
          emptyMessage={isError ? tx('Could not load the plants. Try again.') : tx('No crushing plant set up yet.')} />
      </ListCard>
    </div>
  );
}
