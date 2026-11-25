import { tx } from '@/lib/i18n/tx';
/**
 * Developer-configurable module features.
 *
 * The org's `settings.features` map (edited on /settings/developer) decides
 * which optional modules are switched on. Each entry here binds a feature key
 * to the sidebar section(s) it gates, so turning a module off in Developer
 * Settings removes its parent nav group from the left sidebar.
 *
 * Absent key == enabled: existing organizations were never asked, so an empty
 * map must keep every module visible rather than blanking the navigation.
 */
export interface ModuleFeature {
  key: string;
  label: string;
  description: string;
  /** Titles of the NAV_SECTIONS this feature gates. */
  sections: string[];
  /** Core module: not sold through the licence, so only the local switch applies. */
  core?: boolean;
  /** Licence feature this module is sold under when it is not its own key (an add-on: PLM is sold with manufacturing). */
  licenseFeature?: string;
  /** Features that must be on for this one to be: an add-on is off wherever its base module is off (ADR-S086). */
  requires?: string[];
  /**
   * Server module (`GET /feature-flags/modules/catalog`) behind this feature: hidden when this server did not boot it
   * or the organization switched the add-on off on the Modules screen.
   */
  serverModule?: string;
}

export const MODULE_FEATURES: ModuleFeature[] = [
  { key: 'pos', label: 'POS', description: tx('Point of sale terminal, menus, and tables'), sections: ['POS'], core: true },
  { key: 'crm', label: 'CRM', description: tx('Customers, leads, opportunities, and pipeline tracking'), sections: ['CRM'] },
  { key: 'rental', label: tx('Rentals'), description: tx('Hire-out agreements, rental units, returns, and settlement'), sections: ['Rentals'] },
  { key: 'manufacturing', label: tx('Production (Manufacturing / Bakery)'), description: tx('BOMs, production orders, work orders, and MRP'), sections: ['Production'] },
  { key: 'plm', label: tx('PLM (Product Lifecycle Management)'), description: tx('Engineering change orders, product versions and revisions, lifecycle rules for buying and selling'), sections: ['PLM'], licenseFeature: 'manufacturing', requires: ['manufacturing'], serverModule: 'plm' },
  { key: 'fleet', label: tx('Fleet & Logistics'), description: tx('Vehicles and equipment, drivers, dispatch, weighbridge, fuel and maintenance'), sections: ['Fleet', 'Logistics'] },
  { key: 'quarry', label: tx('Quarry & Extraction'), description: tx('Quarry sites, extraction logs into ROM stock, drilling and blasting, controlled explosives'), sections: ['Quarry'] },
  { key: 'lossPrevention', label: tx('Loss Prevention'), description: tx('Exception intelligence: variances in sales, cash and stock counts raised as cases to review'), sections: [] },
  { key: 'lpVision', label: tx('Loss Prevention: cameras'), description: tx('Edge agents, cameras and zones, video evidence for cases, and the cashier camera rules'), sections: [] },
  { key: 'lpInsights', label: tx('Loss Prevention: insights'), description: tx('Kitchen times, table turnover, sales, void, refund and discount rates, cash accuracy and pour yield'), sections: [] },
  { key: 'repair', label: tx('Repair & Maintenance'), description: tx('Repair jobs, work tickets, and maintenance scheduling'), sections: ['Repair & Maintenance'] },
  { key: 'communication', label: tx('Communication'), description: tx('Unified inbox, channels, and messaging automation'), sections: ['Communication'] },
  { key: 'assets', label: tx('Fixed Assets'), description: tx('Fixed asset register, categories, and depreciation'), sections: ['Fixed Assets'] },
  { key: 'taskBoard', label: tx('Task Management'), description: tx('Kanban-style task management and workflow tracking'), sections: ['Task Management'] },
  { key: 'accounting', label: tx('Accounting'), description: tx('Chart of accounts, journals, and the posting engine'), sections: ['Accounting'] },
  { key: 'expense', label: tx('Expenses'), description: tx('Petty cash, opex tracking, approvals, and reimbursements'), sections: ['Expenses'] },
];

/** Section title -> feature key that gates it. */
const SECTION_FEATURE_KEY: Record<string, string> = Object.fromEntries(
  MODULE_FEATURES.flatMap((f) => f.sections.map((s) => [s, f.key] as const)),
);

const CORE_FEATURE_KEYS = new Set(MODULE_FEATURES.filter((f) => f.core).map((f) => f.key));

const FEATURES_BY_KEY: Record<string, ModuleFeature> = Object.fromEntries(MODULE_FEATURES.map((f) => [f.key, f]));

/** A server module as `GET /feature-flags/modules/catalog` lists it. */
export interface CatalogModule {
  name: string;
  version: string;
  dependencies: string[];
  /** An add-on (ADR-S086): on wherever its dependencies are on, unless the organization switched it off. */
  addOn?: boolean;
  /** Add-ons only: whether it is on for this organization. */
  enabled?: boolean;
}

/**
 * Can this feature's server module be used here? Unknown (`undefined` catalog: still loading or failed) hides
 * nothing, as with the licence.
 */
export const serverModuleAvailable = (catalog: CatalogModule[] | undefined | null, key: string): boolean => {
  const module = FEATURES_BY_KEY[key]?.serverModule;
  if (!module || !catalog) return true;
  const row = catalog.find((m) => m.name === module);
  return !!row && row.enabled !== false;
};

/** Sections that only make sense in some POS modes (none today; items gate themselves via `posModeAllows`). */
const SECTION_POS_MODES: Record<string, string[]> = {};

/**
 * Does the POS mode allow a nav entry limited to `modes`? Menus and tables belong
 * to the cafe/restaurant flow; retail and rental terminals never use them. An
 * unknown mode (settings still loading or unreadable) hides nothing.
 */
export const posModeAllows = (modes: string[] | undefined, posMode?: string): boolean =>
  !modes || !posMode || modes.includes(posMode);

/** A feature is on unless it has been explicitly switched off. */
export const featureEnabled = (
  features: Record<string, boolean> | undefined,
  key: string,
): boolean => features?.[key] !== false;

/**
 * Is this feature included in the licence?
 *
 * `undefined` licensed set means "we do not know yet" — the status request has not landed, or it
 * failed. That must read as "do not hide anything", for the same reason an empty feature map does:
 * navigation blanking out because a request was slow is a worse failure than briefly offering a
 * module the licence will refuse anyway, which the server then blocks with a clear message.
 */
export const featureLicensed = (
  licensed: string[] | undefined | null,
  key: string,
): boolean =>
  !licensed || CORE_FEATURE_KEYS.has(key) || licensed.includes(FEATURES_BY_KEY[key]?.licenseFeature ?? key);

/**
 * The effective state of a feature: **licensed AND enabled**, and so is every feature it requires.
 *
 * The licence is the ceiling and Developer Settings is the switch. An operator may turn a licensed
 * module off; they can never turn an unlicensed one on. An add-on (PLM) goes with its base module.
 */
export const featureAvailable = (
  features: Record<string, boolean> | undefined,
  licensed: string[] | undefined | null,
  key: string,
): boolean =>
  featureLicensed(licensed, key) &&
  featureEnabled(features, key) &&
  (FEATURES_BY_KEY[key]?.requires ?? []).every((base) => featureAvailable(features, licensed, base));

/**
 * Sections with no feature key mapped are always visible. An unknown POS mode
 * (settings still loading or unreadable) hides nothing, as with features.
 */
export const sectionEnabled = (
  features: Record<string, boolean> | undefined,
  title?: string,
  licensed?: string[] | null,
  posMode?: string,
  catalog?: CatalogModule[] | null,
): boolean => {
  if (!title) return true;
  const key = SECTION_FEATURE_KEY[title];
  if (key && !featureAvailable(features, licensed, key)) return false;
  if (key && !serverModuleAvailable(catalog, key)) return false;
  return posModeAllows(SECTION_POS_MODES[title], posMode);
};
