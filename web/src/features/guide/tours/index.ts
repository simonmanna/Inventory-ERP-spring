import type { TourDef } from '../types';
import { salesTours } from './sales';

/** Every tour, built in the current language. */
export function allTours(): TourDef[] {
  return [...salesTours()];
}

export function findTour(key: string | null | undefined): TourDef | undefined {
  return key ? allTours().find((t) => t.key === key) : undefined;
}
