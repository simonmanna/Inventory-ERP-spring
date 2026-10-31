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
}

export const MODULE_FEATURES: ModuleFeature[] = [
  { key: 'pos', label: 'POS', description: tx('Point of sale terminal, menus, and tables'), sections: ['POS'], core: true },
  { key: 'crm', label: 'CRM', description: tx('Customers, leads, opportunities, and pipeline tracking'), sections: ['CRM'] },
  { key: 'rental', label: tx('Rentals'), description: tx('Hire-out agreements, rental units, returns, and settlement'), sections: ['Rentals'] },
  { key: 'manufacturing', label: tx('Production (Manufacturing / Bakery)'), description: tx('BOMs, production orders, work orders, and MRP'), sections: ['Production'] },
  { key: 'fleet', label: tx('Fleet & Logistics'), description: tx('Vehicles and equipment, drivers, dispatch, weighbridge, fuel and maintenance'), sections: ['Fleet', 'Logistics'] },
  { key: 'quarry', label: tx('Quarry & Extraction'), description: tx('Quarry sites, extraction logs into ROM stock, drilling and blasting, controlled explosives'), sections: ['Quarry'] },
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
): boolean => !licensed || CORE_FEATURE_KEYS.has(key) || licensed.includes(key);

/**
 * The effective state of a feature: **licensed AND enabled**.
 *
 * The licence is the ceiling and Developer Settings is the switch. An operator may turn a licensed
 * module off; they can never turn an unlicensed one on.
 */
export const featureAvailable = (
  features: Record<string, boolean> | undefined,
  licensed: string[] | undefined | null,
  key: string,
): boolean => featureLicensed(licensed, key) && featureEnabled(features, key);

/**
 * Sections with no feature key mapped are always visible. An unknown POS mode
 * (settings still loading or unreadable) hides nothing, as with features.
 */
export const sectionEnabled = (
  features: Record<string, boolean> | undefined,
  title?: string,
  licensed?: string[] | null,
  posMode?: string,
): boolean => {
  if (!title) return true;
  const key = SECTION_FEATURE_KEY[title];
  if (key && !featureAvailable(features, licensed, key)) return false;
  return posModeAllows(SECTION_POS_MODES[title], posMode);
};
