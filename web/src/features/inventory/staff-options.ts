import { useQuery } from '@tanstack/react-query';
import { PERMISSIONS } from '@erp/shared';
import { api } from '@/lib/api';
import { useAuthStore } from '@/stores/auth.store';

export interface StaffOption {
  value: string;
  label: string;
}

interface StaffRow { id: string; firstName: string; lastName: string | null; isActive?: boolean }

const toOption = (u: StaffRow): StaffOption => ({
  value: u.id,
  label: [u.firstName, u.lastName].filter(Boolean).join(' '),
});

/**
 * All active staff in the current org, flattened to options for a
 * SearchableSelect. Used by the inventory dialogs that need a "Responsible
 * Person" / "Approved By" dropdown (Stock In, Stock Out, Damages, Adjustment,
 * Transfer) and by the quality screens.
 *
 * UB1 (2026-11-17): stock users read the least-privilege
 * `/inventory/staff-options` (`inventory:read`, id + name only) instead of
 * `/users`, which needs `user:read`. Without either permission the list is
 * empty rather than a 403 toast.
 */
export function useStaffOptions() {
  const hasPermission = useAuthStore((s) => s.hasPermission);
  const viaInventory = hasPermission(PERMISSIONS.inventory.read);
  const viaUsers = !viaInventory && hasPermission(PERMISSIONS.user.read);
  return useQuery<StaffOption[]>({
    queryKey: ['staff', 'options', viaInventory ? 'inventory' : viaUsers ? 'users' : 'none'],
    queryFn: async () => {
      if (viaInventory) {
        return (await api.get<StaffRow[]>('/inventory/staff-options')).data.map(toOption);
      }
      const res = await api.get<{ data: StaffRow[] }>('/users', { params: { page: 1, pageSize: 1000 } });
      return (res.data.data ?? []).filter((u) => u.isActive !== false).map(toOption);
    },
    enabled: viaInventory || viaUsers,
    staleTime: 5 * 60_000,
  });
}
