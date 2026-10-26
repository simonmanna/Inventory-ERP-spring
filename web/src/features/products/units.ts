/**
 * Product units of measure on the client (ADR-S003).
 *
 * A product keeps stock in its base unit (`uomId`). It can also be bought or sold in extra units declared per
 * product (`units`: Box = 24, Pack = 6). Document lines send the chosen unit; the server validates it, converts
 * to the base unit and freezes the conversion on the line.
 */

export type UnitRole = 'purchase' | 'sales';

export interface UomRef {
  id: string;
  code: string;
  name?: string;
  factor?: string | number | null;
  categoryId?: string | null;
  category?: string | null;
}

export interface ProductUnit {
  id?: string;
  uomId: string;
  /** Base units per 1 of this unit. */
  factor: string | number;
  isPurchase: boolean;
  isSales: boolean;
  salePrice?: string | number | null;
  purchasePrice?: string | number | null;
  isActive: boolean;
  uom?: UomRef | null;
}

/** The product fields the unit helpers read (all optional so any product shape fits). */
export interface UnitAwareProduct {
  uomId?: string | null;
  purchaseUomId?: string | null;
  salesUomId?: string | null;
  uom?: UomRef | null;
  purchaseUom?: UomRef | null;
  salesUom?: UomRef | null;
  units?: ProductUnit[] | null;
  salesPrice?: string | number | null;
  costPrice?: string | number | null;
}

export interface UnitOption {
  uomId: string;
  code: string;
  /** Base units per 1 of this unit, or null when only the server can tell (cross-category units). */
  factor: number | null;
  /** The unit's own price for the role, when the product declares one. */
  price: number | null;
}

const num = (v: unknown): number | null => {
  if (v === null || v === undefined || v === '') return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
};

/** Category-factor conversion between two organization units (null when they are not in the same category). */
function categoryFactor(from?: UomRef | null, to?: UomRef | null): number | null {
  if (!from || !to) return null;
  if (from.id === to.id) return 1;
  const sameCategory = from.categoryId && to.categoryId
    ? from.categoryId === to.categoryId
    : (from.category ?? 'unit') === (to.category ?? 'unit');
  const f = num(from.factor);
  const t = num(to.factor);
  if (!sameCategory || f === null || t === null || t <= 0) return null;
  return f / t;
}

/** Base units per 1 `uomId` for this product, or null if unknown on the client. */
export function unitFactor(product: UnitAwareProduct | undefined, uomId: string | null | undefined): number | null {
  if (!product || !uomId || !product.uomId || uomId === product.uomId) return 1;
  const own = product.units?.find((u) => u.uomId === uomId);
  if (own) return num(own.factor);
  const ref = uomId === product.purchaseUom?.id ? product.purchaseUom
    : uomId === product.salesUom?.id ? product.salesUom : null;
  return categoryFactor(ref, product.uom);
}

/** The unit a new line of this role starts in (server default). */
export function defaultUnitId(product: UnitAwareProduct | undefined, role: UnitRole): string | undefined {
  if (!product) return undefined;
  const roleUnit = role === 'purchase' ? product.purchaseUomId : product.salesUomId;
  return roleUnit ?? product.uomId ?? undefined;
}

/** Units a line of this role may use: base unit, the role's default unit, then enabled product units. */
export function unitOptions(product: UnitAwareProduct | undefined, role: UnitRole): UnitOption[] {
  if (!product?.uomId) return [];
  const out: UnitOption[] = [];
  const seen = new Set<string>();
  const add = (uomId: string, code: string | undefined, price: number | null) => {
    if (seen.has(uomId)) return;
    seen.add(uomId);
    out.push({ uomId, code: code ?? '?', factor: unitFactor(product, uomId), price });
  };
  add(product.uomId, product.uom?.code, null);
  const roleRef = role === 'purchase' ? product.purchaseUom : product.salesUom;
  const own = (id: string) => product.units?.find((u) => u.uomId === id);
  if (roleRef) {
    const u = own(roleRef.id);
    add(roleRef.id, roleRef.code, u ? num(role === 'purchase' ? u.purchasePrice : u.salePrice) : null);
  }
  for (const u of product.units ?? []) {
    if (!u.isActive || !(role === 'purchase' ? u.isPurchase : u.isSales)) continue;
    add(u.uomId, u.uom?.code, num(role === 'purchase' ? u.purchasePrice : u.salePrice));
  }
  return out;
}

/**
 * Suggested price of one `uomId`. Purchases scale the per-base cost price; sales scale the sales price, which
 * is quoted per sales unit. A unit's own price wins. Null when the conversion is unknown on the client.
 */
export function unitPrice(product: UnitAwareProduct | undefined, uomId: string | undefined, role: UnitRole): number | null {
  if (!product) return null;
  const opt = unitOptions(product, role).find((o) => o.uomId === uomId);
  if (opt?.price !== null && opt?.price !== undefined) return opt.price;
  const factor = unitFactor(product, uomId ?? defaultUnitId(product, role));
  if (factor === null) return null;
  if (role === 'purchase') {
    const cost = num(product.costPrice);
    return cost === null ? null : cost * factor;
  }
  const price = num(product.salesPrice);
  const salesFactor = unitFactor(product, defaultUnitId(product, 'sales'));
  if (price === null || salesFactor === null || salesFactor <= 0) return null;
  return (price / salesFactor) * factor;
}

/** "= 24 PCS" style hint for a unit option, empty for the base unit. */
export function unitHint(product: UnitAwareProduct | undefined, uomId: string | undefined): string {
  if (!product?.uomId || !uomId || uomId === product.uomId) return '';
  const f = unitFactor(product, uomId);
  return f === null ? '' : `= ${Number(f.toFixed(6))} ${product.uom?.code ?? ''}`.trim();
}
