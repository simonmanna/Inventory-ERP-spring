import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';

/**
 * Pour meters (ADR-S077, LP3 bar intelligence): spouts, flow meters and dispensers whose readings reach the ledger as
 * `pour.measured`. They need the loss-prevention module only, not the camera licence. A meter read by an edge agent is
 * listed in that agent's configuration; an outside system may also post readings itself with `lp:ingest`.
 */

export type MeterKind = 'pour_spout' | 'flow_meter' | 'dispenser';
export const METER_KINDS: MeterKind[] = ['pour_spout', 'flow_meter', 'dispenser'];

export interface MeasurementDevice {
  id: string;
  branchId: string;
  branchName?: string | null;
  agentId: string | null;
  agentName?: string | null;
  name: string;
  kind: MeterKind;
  vendor: string | null;
  externalId: string;
  productId: string | null;
  productName?: string | null;
  station: string | null;
  standardPourMl: number | string | null;
  enabled: boolean;
  retiredAt: string | null;
  createdAt: string;
  lastReadingAt: string | null;
  readings7d: number;
}

export interface MeterInput {
  branchId?: string;
  agentId?: string | null;
  name: string;
  kind?: MeterKind;
  vendor?: string | null;
  externalId?: string;
  productId?: string | null;
  station?: string | null;
  standardPourMl?: number | null;
  enabled?: boolean;
}

const KEY = ['loss-prevention', 'meters'] as const;
const BASE = '/loss-prevention/measurement-devices';

export const useLpMeters = (filters: { branchId?: string; includeRetired?: boolean }, enabled = true) => useQuery({
  queryKey: [...KEY, filters],
  enabled,
  refetchInterval: 60_000,
  queryFn: async () => (await api.get<MeasurementDevice[]>(BASE, {
    params: { branchId: filters.branchId || undefined, includeRetired: filters.includeRetired || undefined },
  })).data,
});

function useMeterChange<V>(fn: (v: V) => Promise<MeasurementDevice>) {
  const qc = useQueryClient();
  return useMutation({ mutationFn: fn, onSuccess: () => qc.invalidateQueries({ queryKey: KEY }) });
}

export const useCreateLpMeter = () =>
  useMeterChange((body: MeterInput) => api.post<MeasurementDevice>(BASE, body).then((r) => r.data));
export const useUpdateLpMeter = () =>
  useMeterChange(({ id, ...body }: MeterInput & { id: string }) =>
    api.put<MeasurementDevice>(`${BASE}/${id}`, body).then((r) => r.data));
export const useRetireLpMeter = () =>
  useMeterChange((id: string) => api.post<MeasurementDevice>(`${BASE}/${id}/retire`).then((r) => r.data));
