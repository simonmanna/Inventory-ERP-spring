import { useMemo } from 'react';
import { useAuthStore } from '@/stores/auth.store';
import { allTours } from './tours';
import { visibleTours } from './tour-logic';
import type { TourDef } from './types';

/** Tours this user may take, in the current language. */
export function useVisibleTours(): TourDef[] {
  const permissions = useAuthStore((s) => s.permissions);
  return useMemo(() => visibleTours(allTours(), (p) => permissions.includes(p)), [permissions]);
}
