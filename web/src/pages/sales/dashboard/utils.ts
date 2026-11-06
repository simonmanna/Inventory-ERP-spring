import { useEffect, useRef, useState } from 'react';
import { tx } from '@/lib/i18n/tx';
import { compactNumber } from '../reports/report-format';

/** "UGX 83.2M": whole-company numbers people can read at a glance; the exact value goes in a title or tooltip. */
export function compactMoney(value: unknown, currency?: string): string {
  const n = Number(value ?? 0);
  if (!currency) return compactNumber(n);
  try {
    return new Intl.NumberFormat(undefined, { style: 'currency', currency, notation: 'compact', maximumFractionDigits: 1 }).format(n);
  } catch {
    return `${currency} ${compactNumber(n)}`;
  }
}

/** Animates a number up to its target on change; jumps straight there under prefers-reduced-motion. */
export function useCountUp(target: number, ms = 800): number {
  const [value, setValue] = useState(target);
  const from = useRef(0);
  useEffect(() => {
    if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) {
      setValue(target);
      from.current = target;
      return undefined;
    }
    const start = performance.now();
    const origin = from.current;
    let raf = 0;
    const step = (now: number) => {
      const t = Math.min(1, (now - start) / ms);
      const v = origin + (target - origin) * (1 - (1 - t) ** 3);
      setValue(v);
      from.current = v;
      if (t < 1) raf = requestAnimationFrame(step);
    };
    raf = requestAnimationFrame(step);
    return () => cancelAnimationFrame(raf);
  }, [target, ms]);
  return value;
}

/** The report facts label rows without a branch, salesperson or category "(none)". */
export function displayLabel(label: string | null | undefined): string {
  return !label || label === '(none)' ? tx('Not assigned') : label;
}
