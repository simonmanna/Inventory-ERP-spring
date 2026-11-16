import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';
import { idempotentPost } from '@/lib/idempotent-request';
import { notify, apiMessage } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';
import type { Num } from './api';

/**
 * Construction controls (ADR-S074): bonds and guarantees, subcontractor compliance (documents, lien waivers, gating,
 * overrides) and capital projects (CIP, capitalisation into fixed assets). Bond lifecycle steps and capitalisations
 * post to the ledger and go through {@link idempotentPost}.
 */

export type BondType = 'performance' | 'bank_guarantee' | 'advance_payment' | 'retention' | 'bid';
export type BondDirection = 'issued' | 'received';
export type BondStatus = 'draft' | 'active' | 'extended' | 'reduced' | 'released' | 'called' | 'expired';
export type BondAction = 'activate' | 'extend' | 'reduce' | 'release' | 'call';
export const BOND_TYPES: BondType[] = ['performance', 'bank_guarantee', 'advance_payment', 'retention', 'bid'];
export const LIVE_BOND: BondStatus[] = ['active', 'extended', 'reduced'];

export interface BondEvent {
  id: string;
  action: string;
  eventDate: string;
  previousAmount?: Num | null;
  amount?: Num | null;
  previousExpiry?: string | null;
  expiryDate?: string | null;
  margin: Num;
  commission: Num;
  calledAmount: Num;
  recoveryTo?: string | null;
  journalEntryId?: string | null;
  reason?: string | null;
  createdAt: string;
}

export interface Bond {
  id: string;
  number: string;
  bondType: BondType;
  direction: BondDirection;
  projectId?: string | null;
  projectCode?: string | null;
  projectName?: string | null;
  subcontractId?: string | null;
  subcontractNumber?: string | null;
  counterpartyId: string;
  counterpartyName?: string;
  issuer: string;
  issuerType: 'bank' | 'insurer';
  bondNumber?: string | null;
  amount: Num;
  originalAmount: Num;
  currencyCode: string;
  issueDate: string;
  expiryDate: string;
  status: BondStatus;
  marginHeld: Num;
  commissionPaid: Num;
  calledAmount: Num;
  documentId?: string | null;
  notes?: string | null;
  daysToExpiry?: number | null;
  events?: BondEvent[];
}

export interface ExposureRow {
  issuer?: string;
  issuerType?: string;
  projectId?: string | null;
  projectCode?: string | null;
  projectName?: string | null;
  direction: BondDirection;
  currencyCode: string;
  bonds: number;
  amount: Num;
  expiring30: Num;
  marginHeld: Num;
  nextExpiry?: string | null;
}

export interface Exposure { asOf: string; byBank: ExposureRow[]; byProject: ExposureRow[]; totals: ExposureRow[] }

export interface BondBody {
  bondType?: BondType;
  direction?: BondDirection;
  projectId?: string;
  subcontractId?: string;
  counterpartyId?: string;
  issuer?: string;
  issuerType?: 'bank' | 'insurer';
  bondNumber?: string;
  amount?: string;
  currencyCode?: string;
  issueDate?: string;
  expiryDate?: string;
  notes?: string;
}

export interface BondActionBody {
  date?: string;
  amount?: string;
  expiryDate?: string;
  margin?: string;
  commission?: string;
  bankAccountId?: string;
  recoveryTo?: 'project_cost' | 'claim_income';
  wbsId?: string;
  reason?: string;
}

// ---- compliance -------------------------------------------------------------------------------------------------

export type DocumentType = 'insurance_certificate' | 'tax_clearance' | 'social_security_clearance' | 'trade_licence' | 'lien_waiver';
export type GateMode = 'off' | 'warn' | 'block';
export const DOCUMENT_TYPES: DocumentType[] = ['insurance_certificate', 'tax_clearance', 'social_security_clearance', 'trade_licence', 'lien_waiver'];

export interface ComplianceDocument {
  id: string;
  partnerId: string;
  partnerName?: string;
  subcontractId?: string | null;
  subcontractNumber?: string | null;
  documentType: DocumentType;
  documentNumber?: string | null;
  issuer?: string | null;
  validFrom?: string | null;
  validTo?: string | null;
  amount?: Num | null;
  waiverType?: 'conditional' | 'unconditional' | null;
  waiverScope?: 'valuation' | 'payment' | null;
  valuationId?: string | null;
  valuationNumber?: string | null;
  paymentId?: string | null;
  paymentNumber?: string | null;
  throughDate?: string | null;
  status: 'pending' | 'verified' | 'rejected';
  verifiedBy?: string | null;
  verifiedAt?: string | null;
  notes?: string | null;
}

export interface Requirement { documentType: DocumentType; label: string; mode: GateMode }
export interface GateIssue { documentType: DocumentType; mode: GateMode; message: string }

export interface SubcontractCompliance {
  subcontract: { id: string; number: string; supplierId: string; status: string };
  supplierName?: string;
  requirements: Requirement[];
  documents: ComplianceDocument[];
  lienWaivers: ComplianceDocument[];
  valuations: Array<{ id: string; number: string; valuationDate: string; netAmount: Num; billId?: string | null; billNumber?: string | null; billResidual?: Num | null }>;
  payments: Array<{ id: string; paymentNumber: string; paymentDate: string; amount: Num }>;
  valuationGate: GateIssue[];
  overrides: Array<{ id: string; gate: string; billId?: string | null; reason: string; consumedAt?: string | null; createdAt: string }>;
  log: Array<{ id: string; gate: string; outcome: string; issues: GateIssue[]; createdAt: string }>;
}

export interface SupplierCompliance {
  partner: { id: string; code?: string; name: string };
  requirements: Requirement[];
  summary: Array<{ documentType: DocumentType; label: string; state: 'missing' | 'expired' | 'valid'; validTo?: string | null }>;
  documents: ComplianceDocument[];
  subcontracts: Array<{ id: string; number: string; status: string; value: Num; projectCode: string; projectName: string }>;
}

export interface DocumentBody {
  partnerId: string;
  subcontractId?: string;
  documentType: DocumentType;
  documentNumber?: string;
  issuer?: string;
  validFrom?: string;
  validTo?: string;
  amount?: string;
  waiverType?: 'conditional' | 'unconditional';
  waiverScope?: 'valuation' | 'payment';
  valuationId?: string;
  paymentId?: string;
  throughDate?: string;
  notes?: string;
}

// ---- CIP --------------------------------------------------------------------------------------------------------

export interface CapitalisationAsset { assetId: string; amount: Num; inServiceDate: string; assetCode: string; name: string; status: string; deletedAt?: string | null }
export interface Capitalisation {
  id: string;
  number: string;
  capitalisationDate: string;
  amount: Num;
  status: 'posted' | 'reversed';
  journalEntryId?: string | null;
  reversalEntryId?: string | null;
  assets: CapitalisationAsset[];
}
export interface Cip {
  projectId: string;
  isCapital: boolean;
  costCaptured: Num;
  capitalised: Num;
  balance: Num;
  glBalance: Num;
  difference: Num;
  capitalisations: Capitalisation[];
}
export interface CapitaliseBody {
  date?: string;
  notes?: string;
  assets: Array<{ name: string; categoryId: string; amount: string; usefulLife?: number; salvageValue?: string; inServiceDate: string; location?: string }>;
}

// ---- queries ----------------------------------------------------------------------------------------------------

const KEY = ['construction'] as const;

export const useBonds = (filter: { projectId?: string; status?: string; direction?: string } = {}, enabled = true) =>
  useQuery({
    queryKey: [...KEY, 'bonds', filter],
    queryFn: async () => (await api.get<Bond[]>('/project-bonds', { params: filter })).data,
    enabled,
  });

export const useBond = (id?: string | null) =>
  useQuery({
    queryKey: [...KEY, 'bond', id],
    queryFn: async () => (await api.get<Bond>(`/project-bonds/${id}`)).data,
    enabled: !!id,
  });

export const useExposure = (enabled = true) =>
  useQuery({
    queryKey: [...KEY, 'exposure'],
    queryFn: async () => (await api.get<Exposure>('/project-bonds/exposure')).data,
    enabled,
  });

export const useRequirements = (enabled = true) =>
  useQuery({
    queryKey: [...KEY, 'requirements'],
    queryFn: async () => (await api.get<Requirement[]>('/subcontract-compliance/requirements')).data,
    enabled,
  });

export const useSubcontractCompliance = (subcontractId?: string | null) =>
  useQuery({
    queryKey: [...KEY, 'subcontract', subcontractId],
    queryFn: async () => (await api.get<SubcontractCompliance>(`/subcontract-compliance/subcontracts/${subcontractId}`)).data,
    enabled: !!subcontractId,
  });

export const useSupplierCompliance = (partnerId?: string | null, enabled = true) =>
  useQuery({
    queryKey: [...KEY, 'supplier', partnerId],
    queryFn: async () => (await api.get<SupplierCompliance>(`/subcontract-compliance/suppliers/${partnerId}`)).data,
    enabled: !!partnerId && enabled,
  });

export const useCip = (projectId: string, enabled = true) =>
  useQuery({
    queryKey: [...KEY, 'cip', projectId],
    queryFn: async () => (await api.get<Cip>(`/projects/${projectId}/cip`)).data,
    enabled: !!projectId && enabled,
  });

// ---- writes -----------------------------------------------------------------------------------------------------

type Write = { url: string; body?: unknown; money?: boolean; method?: 'post' | 'patch' | 'put' };

/** One mutation hook for every write here: errors (gate refusals included) are toasted verbatim. */
export function useConstructionMutation<V = void, R = unknown>(build: (v: V) => Write, opts: { success?: string } = {}) {
  const qc = useQueryClient();
  return useMutation<R, unknown, V>({
    mutationFn: async (v: V) => {
      const { url, body, money, method } = build(v);
      if (money) return idempotentPost<R>(url, body ?? {});
      if (method === 'patch') return (await api.patch<R>(url, body ?? {})).data;
      if (method === 'put') return (await api.put<R>(url, body ?? {})).data;
      return (await api.post<R>(url, body ?? {})).data;
    },
    onSuccess: () => {
      if (opts.success) notify.success(opts.success);
      void qc.invalidateQueries({ queryKey: KEY });
      void qc.invalidateQueries({ queryKey: ['contract'] });
      void qc.invalidateQueries({ queryKey: ['pm'] });
      void qc.invalidateQueries({ queryKey: ['job-costing'] });
    },
    onError: (err) => notify.error(apiMessage(err, tx('Something went wrong'))),
  });
}

export const constructionApi = {
  createBond: (body: BondBody): Write => ({ url: '/project-bonds', body }),
  updateBond: (id: string, body: BondBody): Write => ({ url: `/project-bonds/${id}`, body, method: 'patch' }),
  bondAction: (id: string, action: BondAction, body: BondActionBody): Write => ({ url: `/project-bonds/${id}/${action}`, body, money: true }),

  setRequirements: (requirements: Array<{ documentType: DocumentType; mode: GateMode }>): Write => ({
    url: '/subcontract-compliance/requirements', body: { requirements }, method: 'put',
  }),
  createDocument: (body: DocumentBody): Write => ({ url: '/subcontract-compliance/documents', body }),
  verifyDocument: (id: string): Write => ({ url: `/subcontract-compliance/documents/${id}/verify` }),
  rejectDocument: (id: string, reason: string): Write => ({ url: `/subcontract-compliance/documents/${id}/reject`, body: { reason } }),
  override: (body: { subcontractId: string; gate: 'valuation' | 'payment'; billId?: string; reason: string }): Write => ({
    url: '/subcontract-compliance/overrides', body,
  }),

  setCapital: (projectId: string, isCapital: boolean): Write => ({ url: `/projects/${projectId}/capital`, body: { isCapital }, method: 'put' }),
  capitalise: (projectId: string, body: CapitaliseBody): Write => ({ url: `/projects/${projectId}/capitalisations`, body, money: true }),
  reverseCapitalisation: (id: string): Write => ({ url: `/projects/capitalisations/${id}/reverse`, money: true }),
};

// ---- labels -----------------------------------------------------------------------------------------------------

export const bondTypeLabel = (t: string) => ({
  performance: tx('Performance bond'),
  bank_guarantee: tx('Bank guarantee'),
  advance_payment: tx('Advance-payment guarantee'),
  retention: tx('Retention bond'),
  bid: tx('Bid bond'),
}[t] ?? t);

export const bondStatusLabel = (s: string) => ({
  draft: tx('Draft'), active: tx('Active'), extended: tx('Extended'), reduced: tx('Reduced'), released: tx('Released'),
  called: tx('Called'), expired: tx('Expired'),
}[s] ?? s);

export const directionLabel = (d: string) => (d === 'issued' ? tx('Issued by us') : tx('Received by us'));

export const documentTypeLabel = (t: string) => ({
  insurance_certificate: tx('Insurance certificate'),
  tax_clearance: tx('Tax clearance certificate'),
  social_security_clearance: tx('Social security (NSSF) clearance'),
  trade_licence: tx('Trade licence'),
  lien_waiver: tx('Lien waiver'),
}[t] ?? t);

export const modeLabel = (m: string) => ({ off: tx('Off'), warn: tx('Warn'), block: tx('Block') }[m] ?? m);

export const opt = (v: string | null | undefined) => (v == null || v.trim() === '' ? undefined : v.trim());
