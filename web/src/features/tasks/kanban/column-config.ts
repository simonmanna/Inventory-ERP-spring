import { TaskStatus } from '../types';
import { tx } from '@/lib/i18n/tx';

export interface ColumnDef {
  id: string;
  title: string;
  color: string;
  dotColor: string;
  statuses: TaskStatus[];
  icon?: string;
}

export const COLUMNS: ColumnDef[] = [
  {
    id: 'todo',
    title: tx('To Do'),
    color: 'bg-slate-400',
    dotColor: '#94a3b8',
    statuses: [TaskStatus.DRAFT, TaskStatus.PENDING],
  },
  {
    id: 'assigned',
    title: tx('Assigned'),
    color: 'bg-blue-500',
    dotColor: '#3b82f6',
    statuses: [TaskStatus.ASSIGNED],
  },
  {
    id: 'in_progress',
    title: tx('In Progress'),
    color: 'bg-amber-500',
    dotColor: '#f59e0b',
    statuses: [TaskStatus.IN_PROGRESS],
  },
  {
    id: 'waiting',
    title: tx('Waiting'),
    color: 'bg-purple-500',
    dotColor: '#a855f7',
    statuses: [TaskStatus.WAITING],
  },
  {
    id: 'review',
    title: tx('Review'),
    color: 'bg-orange-500',
    dotColor: '#f97316',
    statuses: [TaskStatus.REVIEW],
  },
  {
    id: 'verified',
    title: tx('Verified'),
    color: 'bg-teal-500',
    dotColor: '#14b8a6',
    statuses: [TaskStatus.VERIFIED],
  },
  {
    id: 'completed',
    title: tx('Completed'),
    color: 'bg-emerald-500',
    dotColor: '#10b981',
    statuses: [TaskStatus.COMPLETED],
  },
  {
    id: 'cancelled',
    title: tx('Cancelled'),
    color: 'bg-red-400',
    dotColor: '#f87171',
    statuses: [TaskStatus.CANCELLED, TaskStatus.SKIPPED],
  },
  {
    id: 'overdue',
    title: tx('Overdue'),
    color: 'bg-red-600',
    dotColor: '#dc2626',
    statuses: [], // computed — tasks with dueDate < now and not completed/cancelled
  },
];

export function getColumnForStatus(status: TaskStatus): ColumnDef {
  return COLUMNS.find((c) => c.statuses.includes(status)) ?? COLUMNS[0];
}

export function getStatusesForColumn(columnId: string): TaskStatus[] {
  const col = COLUMNS.find((c) => c.id === columnId);
  return col?.statuses ?? [];
}

export function getColumnColor(status: TaskStatus): string {
  return getColumnForStatus(status).dotColor;
}

export const PRIORITY_COLORS: Record<string, string> = {
  CRITICAL: '#dc2626',
  HIGH: '#f97316',
  MEDIUM: '#eab308',
  LOW: '#22c55e',
  OPTIONAL: '#94a3b8',
};

export const PRIORITY_LABELS: Record<string, string> = {
  CRITICAL: tx('Critical'),
  HIGH: tx('High'),
  MEDIUM: tx('Medium'),
  LOW: tx('Low'),
  OPTIONAL: tx('Optional'),
};

export const TASK_TYPE_ICONS: Record<string, string> = {
  OPENING: '🔓',
  CLOSING: '🔒',
  CLEANING: '🧹',
  MAINTENANCE: '🔧',
  INVENTORY: '📦',
  AUDIT: '📋',
  PURCHASE: '🛒',
  INCIDENT: '⚠️',
  COMPLIANCE: '✓',
  FOOD_SAFETY: '🥗',
  EQUIPMENT_INSPECTION: '🔍',
  SHIFT: '🔄',
  RECURRING: '🔁',
  ONE_TIME: '📌',
  CUSTOM: '⚡',
};

export const CATEGORY_LABELS: Record<string, string> = {
  OPENING: tx('Opening'),
  CLOSING: tx('Closing'),
  CLEANING: tx('Cleaning'),
  KITCHEN: tx('Kitchen'),
  COFFEE_BAR: tx('Coffee Bar'),
  DINING_AREA: tx('Dining Area'),
  RETAIL_FLOOR: tx('Retail Floor'),
  WAREHOUSE: tx('Warehouse'),
  INVENTORY: tx('Inventory'),
  PURCHASING: tx('Purchasing'),
  ACCOUNTING: tx('Accounting'),
  CASH_MANAGEMENT: tx('Cash'),
  SECURITY: tx('Security'),
  MAINTENANCE: tx('Maintenance'),
  EQUIPMENT: tx('Equipment'),
  MARKETING: tx('Marketing'),
  COMPLIANCE: tx('Compliance'),
  AUDIT: tx('Audit'),
  CUSTOMER_SERVICE: tx('Customer Service'),
  CUSTOM: tx('Custom'),
};
