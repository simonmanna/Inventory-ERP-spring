import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';

/** Who is told when something happens (ADR-S065). */
export interface RoutedEventParty {
  key: string;
  label: string;
}

export interface RoutedEventRoute {
  event: string;
  area: string;
  label: string;
  parties: RoutedEventParty[];
  defaultParties: string[];
  enabled: boolean;
  selectedParties: string[];
  roleIds: string[];
  userIds: string[];
  channels: string[];
  customized: boolean;
}

export interface RouteInput {
  enabled: boolean;
  parties: string[];
  roleIds: string[];
  userIds: string[];
  channels: string[];
}

export interface RoutingActivity {
  eventName: string;
  label: string;
  area: string | null;
  recordId: string | null;
  recipients: number;
  claimedAt: string;
}

const KEY = ['notification-routing'];

export function useRoutedEvents() {
  return useQuery({
    queryKey: [...KEY, 'events'],
    queryFn: async () => (await api.get<RoutedEventRoute[]>('/notification-routing/events')).data,
  });
}

export function useRoutingActivity(enabled: boolean) {
  return useQuery({
    queryKey: [...KEY, 'activity'],
    enabled,
    queryFn: async () => (await api.get<RoutingActivity[]>('/notification-routing/activity', { params: { limit: 100 } }))
      .data,
  });
}

export function useSaveRoute() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async ({ event, body }: { event: string; body: RouteInput }) =>
      (await api.put<RoutedEventRoute>(`/notification-routing/events/${encodeURIComponent(event)}`, body)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: KEY }),
  });
}

export function useResetRoute() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (event: string) =>
      (await api.delete<RoutedEventRoute>(`/notification-routing/events/${encodeURIComponent(event)}`)).data,
    onSuccess: () => qc.invalidateQueries({ queryKey: KEY }),
  });
}
