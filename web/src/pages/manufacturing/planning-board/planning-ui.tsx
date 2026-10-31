import { Badge } from '@/components/ui/badge';
import { tx } from '@/lib/i18n/tx';

/** Shared bits of the advanced planning screens (ADR-S017). */
export const ymd = (d: Date) => d.toISOString().slice(0, 10);
export const addDays = (d: Date, days: number) => new Date(d.getTime() + days * 86_400_000);

export const priorityOptions = () => [
  { value: '1', label: tx('Low') }, { value: '2', label: tx('Normal') },
  { value: '3', label: tx('High') }, { value: '4', label: tx('Urgent') },
];

export function PriorityBadge({ priority }: { priority: number }) {
  const label = priorityOptions().find((o) => o.value === String(priority))?.label ?? String(priority);
  return <Badge variant={priority >= 4 ? 'destructive' : priority === 3 ? 'default' : 'secondary'}>{label}</Badge>;
}

export function demandTypeLabel(type: string) {
  switch (type) {
    case 'delivery_order': return tx('Delivery schedule');
    case 'sales_order': return tx('Sales order');
    case 'proforma': return tx('Proforma');
    case 'forecast': return tx('Forecast');
    default: return tx('Stock policy');
  }
}
