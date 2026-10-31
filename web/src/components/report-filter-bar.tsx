import { useQuery } from '@tanstack/react-query';
import { Input } from '@/components/ui/input';
import { useBranchOptions } from '@/features/finance/api';
import { locationsApi } from '@/lib/api/locations';
import { tx } from '@/lib/i18n/tx';

export interface ReportFilters {
  from: string;
  to: string;
  branchId?: string;
  locationId?: string;
  departmentId?: string;
}

interface DepartmentOption {
  id: string;
  name: string;
}

interface ReportFilterBarProps {
  value: ReportFilters;
  onChange: (next: ReportFilters) => void;
  /** Which optional filters this report honours (BR-2). Dates are always shown. */
  show?: { branch?: boolean; location?: boolean; department?: boolean };
  departments?: DepartmentOption[];
}

const selectClass = 'rounded border bg-background px-2 py-1.5 text-sm';

/** Common filters for reports: dates, branch, warehouse and department. */
export function ReportFilterBar({ value, onChange, show, departments = [] }: ReportFilterBarProps) {
  const { data: branches = [] } = useBranchOptions();
  const { data: locations } = useQuery({
    queryKey: ['report-filter', 'locations'],
    queryFn: () => locationsApi.list({ pageSize: 200 }),
    enabled: !!show?.location,
  });
  const set = (patch: Partial<ReportFilters>) => onChange({ ...value, ...patch });
  return (
    <div className="flex flex-wrap items-center gap-2">
      <Input
        type="date"
        aria-label={tx('From')}
        className="w-40"
        value={value.from}
        onChange={(e) => set({ from: e.target.value })}
      />
      <Input
        type="date"
        aria-label={tx('To')}
        className="w-40"
        value={value.to}
        onChange={(e) => set({ to: e.target.value })}
      />
      {show?.branch && (
        <select
          aria-label={tx('Branch')}
          className={selectClass}
          value={value.branchId ?? ''}
          onChange={(e) => set({ branchId: e.target.value || undefined })}
        >
          <option value="">{tx('All branches')}</option>
          {branches.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
        </select>
      )}
      {show?.location && (
        <select
          aria-label={tx('Warehouse')}
          className={selectClass}
          value={value.locationId ?? ''}
          onChange={(e) => set({ locationId: e.target.value || undefined })}
        >
          <option value="">{tx('All warehouses')}</option>
          {(locations?.data ?? []).map((l) => <option key={l.id} value={l.id}>{l.name}</option>)}
        </select>
      )}
      {show?.department && (
        <select
          aria-label={tx('Department')}
          className={selectClass}
          value={value.departmentId ?? ''}
          onChange={(e) => set({ departmentId: e.target.value || undefined })}
        >
          <option value="">{tx('All departments')}</option>
          {departments.map((d) => <option key={d.id} value={d.id}>{d.name}</option>)}
        </select>
      )}
    </div>
  );
}
