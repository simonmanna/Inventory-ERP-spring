import type { OrderLineInput, OrderType, OrderLineSource } from '@/features/orders/types';
import { tx } from '@/lib/i18n/tx';

/** Resolved line-source metadata for the Orders pages. */
export interface LineSourceDef {
  kind: 'menu' | 'products' | 'both';
  label: string;
}

export const LINE_SOURCE_LABELS: Record<OrderLineSource, string> = {
  auto: tx('Auto (follow POS mode)'),
  menu: tx('Menu items'),
  products: tx('Products'),
  both: tx('Both (menu + products)'),
};

export const ORDER_TYPE_LABELS: Record<OrderType, string> = {
  dine_in: tx('Dine-in'),
  takeaway: tx('Takeaway'),
  delivery: tx('Delivery'),
};

/** Server-resolved source ('menu' | 'products' | 'both'). */
export function sourceDef(resolved: 'menu' | 'products' | 'both'): LineSourceDef {
  switch (resolved) {
    case 'products':
      return { kind: 'products', label: tx('Products') };
    case 'both':
      return { kind: 'both', label: tx('Menu + Products') };
    default:
      return { kind: 'menu', label: tx('Menu items') };
  }
}

/** Normalize a picked item into the shared OrderLineInput shape. */
export interface PickableItem {
  id: string;
  name: string;
  price: number;
  categoryName?: string | null;
  sku?: string | null;
}

export function toLineInput(item: PickableItem, kind: 'menu' | 'products'): OrderLineInput {
  if (kind === 'menu') {
    return {
      menuItemId: item.id,
      description: item.name,
      quantity: 1,
      unitPrice: item.price,
    };
  }
  return {
    productId: item.id,
    sku: item.sku ?? undefined,
    description: item.name,
    quantity: 1,
    unitPrice: item.price,
  };
}