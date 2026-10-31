import { BarChart3, Boxes, Factory, Landmark, Layers, Mountain, Network, ReceiptText, ShoppingCart, Truck, Wrench, type LucideIcon } from 'lucide-react';
import { PERMISSIONS } from '@erp/shared';
import { tx } from '@/lib/i18n/tx';

export interface Domain { path: string; label: string; icon: LucideIcon; permission: string }

const P = PERMISSIONS.analytics;

/** The dashboards, in menu order; `path` is both the screen and the API segment. */
export const analyticsDomains = (): Domain[] => [
  { path: 'quarry', label: tx('Quarry Operations'), icon: Mountain, permission: P.quarry },
  { path: 'aggregates', label: tx('Aggregate Processing'), icon: Factory, permission: P.aggregates },
  { path: 'blocks', label: tx('Block Manufacturing'), icon: Layers, permission: P.blocks },
  { path: 'inventory', label: tx('Inventory'), icon: Boxes, permission: P.inventory },
  { path: 'procurement', label: tx('Procurement'), icon: ShoppingCart, permission: P.procurement },
  { path: 'sales', label: tx('Sales'), icon: ReceiptText, permission: P.sales },
  { path: 'fleet', label: tx('Fleet & Delivery'), icon: Truck, permission: P.fleet },
  { path: 'maintenance', label: tx('Maintenance'), icon: Wrench, permission: P.maintenance },
  { path: 'finance', label: tx('Finance'), icon: Landmark, permission: P.finance },
  { path: 'branches', label: tx('Branch Performance'), icon: Network, permission: P.branch },
];

export const AnalyticsIcon = BarChart3;
