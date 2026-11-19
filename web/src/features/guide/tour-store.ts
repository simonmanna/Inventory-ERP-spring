import { create } from 'zustand';
import { createJSONStorage, persist } from 'zustand/middleware';

/**
 * The running tour (ADR-S079). Kept in sessionStorage so a tour survives page changes and a reload, but not a new
 * browser session; the server keeps the long-term progress.
 */
interface TourState {
  activeKey: string | null;
  stepIndex: number;
  /** The Learn panel is open. */
  panelOpen: boolean;
  start: (key: string, step?: number) => void;
  go: (step: number) => void;
  exit: () => void;
  setPanelOpen: (open: boolean) => void;
}

export const useTourStore = create<TourState>()(
  persist(
    (set) => ({
      activeKey: null,
      stepIndex: 0,
      panelOpen: false,
      start: (key, step = 0) => set({ activeKey: key, stepIndex: step, panelOpen: false }),
      go: (step) => set({ stepIndex: Math.max(0, step) }),
      exit: () => set({ activeKey: null, stepIndex: 0 }),
      setPanelOpen: (panelOpen) => set({ panelOpen }),
    }),
    {
      name: 'erp-guide-tour',
      storage: createJSONStorage(() => sessionStorage),
      partialize: (s) => ({ activeKey: s.activeKey, stepIndex: s.stepIndex }),
    },
  ),
);
