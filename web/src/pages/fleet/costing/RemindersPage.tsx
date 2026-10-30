import { Link } from 'react-router-dom';
import { BellRing, Play } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { Button } from '@/components/ui/button';
import { DataTable, type Column } from '@/components/data-table';
import {
  DataTablePagination, DateRangeFilter, FilterSelect, ListCard, ListPageHeader, ListToolbar, StatusPill, useListState,
} from '@/components/list';
import { useAuthStore } from '@/stores/auth.store';
import { notify, apiMessage } from '@/lib/notify';
import { date } from '@/lib/format';
import { tx } from '@/lib/i18n/tx';
import { REMINDER_TYPE_LABEL, useReminderLog, useRunReminders, type ReminderLog } from '@/features/fleet/costing-api';
import { toOptions } from '../fleet-format';

const FILTERS = { itemType: '', from: '', to: '' };

const link = (r: ReminderLog) => (r.itemType === 'driver_document' ? `/fleet/drivers/${r.driverId}`
  : r.itemType === 'vehicle_tare' ? '/fleet/weighbridge/tares'
    : r.itemType === 'service_due' ? '/fleet/maintenance' : `/fleet/vehicles/${r.vehicleId}`);

/**
 * Fleet reminders (fleet 1.9): what the daily reminder job sent — vehicle documents, driver licences / medicals /
 * permits, stored tares and services due — one per item per day. Fleet settings holders can run it now.
 */
export function RemindersPage() {
  const has = useAuthStore((s) => s.hasPermission);
  const list = useListState(FILTERS);
  const { itemType, from, to } = list.filters;
  const { data, isLoading, isError } = useReminderLog({
    page: list.page, pageSize: list.pageSize, itemType: itemType || undefined, from: from || undefined, to: to || undefined,
  });
  const run = useRunReminders();
  const rows = data?.data ?? [];
  const meta = data?.meta;

  const runNow = async () => {
    try {
      const out = await run.mutateAsync();
      const sent = Object.values(out.sent).reduce((a, b) => a + b, 0);
      notify.success(tx('{{n}} reminder(s) sent, {{s}} already sent today', { n: sent, s: out.skipped }));
    } catch (e) {
      notify.error(apiMessage(e, tx('Could not run the reminders')));
    }
  };

  const columns: Column<ReminderLog>[] = [
    { key: 'day', header: tx('Reminded'), render: (r) => <span className="whitespace-nowrap">{date(r.reminderDate)}</span> },
    { key: 'type', header: tx('Kind'), render: (r) => REMINDER_TYPE_LABEL[r.itemType] },
    { key: 'title', header: tx('Reminder'), render: (r) => <Link className="text-primary hover:underline" to={link(r)}>{r.title}</Link> },
    { key: 'due', header: tx('Due'), render: (r) => (r.dueOn ? date(r.dueOn) : '—') },
    {
      key: 'state', header: tx('State'),
      render: (r) => <StatusPill tone={r.state === 'expired' || r.state === 'overdue' ? 'danger' : 'warning'}>{r.state}</StatusPill>,
    },
  ];

  return (
    <div className="space-y-4">
      <ListPageHeader icon={BellRing} title={tx('Fleet Reminders')}
        description={tx('Expiry and service-due reminders sent to the fleet team (in-app notifications; communication rules can forward them by email or WhatsApp).')}
        actions={has(PERMISSIONS.fleet.settings) && (
          <Button onClick={runNow} disabled={run.isPending}><Play className="mr-1.5 h-4 w-4" />{tx('Run now')}</Button>
        )} />
      <ListToolbar>
        <FilterSelect value={itemType} onChange={(v) => list.setFilter('itemType', v)} options={toOptions(REMINDER_TYPE_LABEL)} allLabel={tx('All kinds')} />
        <DateRangeFilter from={from} to={to} onChange={(a, b) => list.setFilters({ from: a, to: b })} />
        <Link to="/fleet/reports?report=renewals" className="ml-auto text-sm text-primary hover:underline">{tx('Upcoming renewals & services')} →</Link>
      </ListToolbar>
      <ListCard>
        <div className="overflow-x-auto">
          <DataTable columns={columns} data={rows} loading={isLoading} loadingRows={6} getRowId={(r) => r.id} cellClassName="py-2.5 px-4" headerRowClassName="h-10"
            emptyMessage={isError ? tx('Could not load the reminders. Try again.') : tx('No reminders sent yet.')} />
        </div>
      </ListCard>
      {meta && (
        <DataTablePagination page={meta.page} pageSize={list.pageSize} total={meta.total} totalPages={meta.totalPages}
          onPageChange={list.setPage} onPageSizeChange={list.setPageSize} noun="reminder" />
      )}
    </div>
  );
}
