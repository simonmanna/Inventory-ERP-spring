import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { useAuthStore } from '@/stores/auth.store';
import type { ProgressStatus, TourProgress } from './types';

const KEY = ['guide-progress'] as const;

/** The signed-in user's progress through every tour (GET /guide/progress). Loaded only while `enabled`. */
export function useGuideProgress(enabled = true) {
  const token = useAuthStore((s) => s.accessToken);
  return useQuery({
    queryKey: KEY,
    enabled: !!token && enabled,
    staleTime: 60_000,
    retry: false,
    queryFn: async () => (await api.get<TourProgress[]>('/guide/progress')).data,
  });
}

/**
 * Save where the user is in a tour. Best effort: a failed save never interrupts the tour, it only means the progress
 * is not remembered on other devices.
 */
export function useSaveTourProgress() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: async (p: { tourKey: string; tourVersion: number; status: ProgressStatus; currentStep: number }) =>
      (await api.put<TourProgress>(`/guide/progress/${encodeURIComponent(p.tourKey)}`, {
        tourVersion: p.tourVersion, status: p.status, currentStep: p.currentStep,
      })).data,
    onSuccess: (saved) => {
      qc.setQueryData<TourProgress[]>(KEY, (list) => [saved, ...(list ?? []).filter((x) => x.tourKey !== saved.tourKey)]);
    },
  });
}
