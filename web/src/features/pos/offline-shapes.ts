/** ADR-S083 O2 — server response shapes rebuilt from pulled scopes (pure). */

type Row = Record<string, unknown>;

const live = (r: Row) => !r.deletedAt;
const byOrder = (a: Row, b: Row) => Number(a.displayOrder ?? 0) - Number(b.displayOrder ?? 0)
  || String(a.name ?? '').localeCompare(String(b.name ?? ''));

/** `GET /pos/menu/items/available` rebuilt from the `menuItems` / `menuCategories` scopes (`PosMenuService.listAvailable`). */
export function menuFromRows(items: Row[], categories: Row[]): { categories: Row[]; items: Row[] } | undefined {
  const available = items.filter((i) => live(i) && i.isAvailable !== false).sort(byOrder);
  if (available.length === 0) return undefined;
  return { categories: categories.filter(live).sort(byOrder), items: available };
}

