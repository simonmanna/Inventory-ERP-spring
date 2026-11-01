import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { PaginatedResult } from '@erp/shared';
import { api } from '@/lib/api';
import { notify } from '@/lib/notify';
import { tx } from '@/lib/i18n/tx';

/** Customization platform (roadmap 2.5, ADR-S029). */
export type FieldType = 'text' | 'textarea' | 'number' | 'decimal' | 'date' | 'boolean' | 'select' | 'multiselect' | 'reference';

export interface CustomFieldDefinition {
  id: string;
  entity: string;
  key: string;
  label: string;
  type: FieldType;
  options: { values?: string[]; entity?: string };
  required: boolean;
  defaultValue: unknown;
  helpText: string | null;
  sortOrder: number;
  showInList: boolean;
  isActive: boolean;
}

export interface CustomEntity {
  entity: string;
  label: string;
  custom: boolean;
  fieldCount: number;
}

export interface CustomObject {
  id: string;
  key: string;
  label: string;
  pluralLabel: string;
  displayField: string | null;
  description: string | null;
  isActive: boolean;
  recordCount?: number;
  fields?: CustomFieldDefinition[];
}

export interface CustomRecord {
  id: string;
  title: string | null;
  values: Record<string, unknown>;
  createdAt: string;
  updatedAt: string;
}

export interface PrintTemplate {
  id: string;
  documentKind: string;
  name: string;
  content?: string;
  css?: string;
  paperSize: 'A4' | 'A5' | 'Letter';
  orientation: 'portrait' | 'landscape';
  isDefault: boolean;
  isActive: boolean;
  version: number;
  updatedAt: string;
}

export interface TemplateField {
  path: string;
  section: boolean;
  sample: string | null;
}

export interface AutomationRule {
  id: string;
  name: string;
  description: string | null;
  trigger: string;
  conditions: { field: string; op: string; value?: unknown }[];
  actions: Record<string, unknown>[];
  isActive: boolean;
  runCount: number;
  lastRunAt: string | null;
}

export interface AutomationRun {
  id: string;
  recordId: string;
  status: 'succeeded' | 'partial' | 'failed' | 'skipped';
  matched: boolean;
  results: { type: string; ok: boolean; message: string }[];
  createdAt: string;
}

export interface ApiClient {
  id: string;
  name: string;
  clientId: string;
  secretHint: string;
  scopes: string[];
  isActive: boolean;
  expiresAt: string | null;
  lastUsedAt: string | null;
  revokedAt: string | null;
  createdAt: string;
  clientSecret?: string;
}

export const DOCUMENT_KINDS = ['sales_invoice', 'credit_note', 'vendor_bill', 'purchase_order', 'sales_order', 'sales_quotation'] as const;

function useInvalidating<T, R = unknown>(fn: (input: T) => Promise<R>, keys: string[][], success?: string) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => {
      keys.forEach((k) => qc.invalidateQueries({ queryKey: k }));
      if (success) notify.success(success);
    },
  });
}

// ---- custom fields -------------------------------------------------------------------------------------------------

export function useCustomEntities() {
  return useQuery({
    queryKey: ['custom-fields', 'entities'],
    queryFn: async () => (await api.get<CustomEntity[]>('/custom-fields/entities')).data,
  });
}

export function useFieldDefinitions(entity: string | undefined, includeInactive = false) {
  return useQuery({
    queryKey: ['custom-fields', 'definitions', entity, includeInactive],
    queryFn: async () =>
      (await api.get<CustomFieldDefinition[]>('/custom-fields/definitions', {
        params: { entity, ...(includeInactive ? { includeInactive: 'true' } : {}) },
      })).data,
    enabled: !!entity,
  });
}

export function useCreateFieldDefinition() {
  return useInvalidating(
    async (input: Partial<CustomFieldDefinition>) => (await api.post('/custom-fields/definitions', input)).data,
    [['custom-fields'], ['custom-objects']],
    tx('Custom field created'),
  );
}

export function useUpdateFieldDefinition() {
  return useInvalidating(
    async ({ id, ...input }: Partial<CustomFieldDefinition> & { id: string }) =>
      (await api.patch(`/custom-fields/definitions/${id}`, input)).data,
    [['custom-fields'], ['custom-objects']],
    tx('Custom field updated'),
  );
}

export function useCustomValues(entity: string, recordId: string | undefined, enabled = true) {
  return useQuery({
    queryKey: ['custom-fields', 'values', entity, recordId],
    queryFn: async () =>
      (await api.get<{ values: Record<string, unknown>; fields: CustomFieldDefinition[] }>(
        `/custom-fields/values/${entity}/${recordId}`,
      )).data,
    enabled: enabled && !!recordId,
  });
}

export function useSaveCustomValues(entity: string, recordId: string) {
  return useInvalidating(
    async (values: Record<string, unknown>) =>
      (await api.put(`/custom-fields/values/${entity}/${recordId}`, { values })).data,
    [['custom-fields', 'values', entity, recordId]],
    tx('Custom fields saved'),
  );
}

// ---- custom objects ------------------------------------------------------------------------------------------------

export function useCustomObjects(enabled = true) {
  return useQuery({
    queryKey: ['custom-objects'],
    queryFn: async () => (await api.get<CustomObject[]>('/custom-objects')).data,
    enabled,
  });
}

export function useCustomObject(key: string | undefined) {
  return useQuery({
    queryKey: ['custom-objects', key],
    queryFn: async () => (await api.get<CustomObject>(`/custom-objects/${key}`)).data,
    enabled: !!key,
  });
}

export function useCreateCustomObject() {
  return useInvalidating(
    async (input: { key: string; label: string; pluralLabel?: string; description?: string }) =>
      (await api.post('/custom-objects', input)).data,
    [['custom-objects'], ['custom-fields', 'entities']],
    tx('Custom object created'),
  );
}

export function useUpdateCustomObject() {
  return useInvalidating(
    async ({ key, ...input }: Partial<CustomObject> & { key: string }) => (await api.patch(`/custom-objects/${key}`, input)).data,
    [['custom-objects']],
    tx('Custom object updated'),
  );
}

export function useCustomRecords(key: string | undefined, search: string, page: number) {
  return useQuery({
    queryKey: ['custom-objects', key, 'records', search, page],
    queryFn: async () =>
      (await api.get<PaginatedResult<CustomRecord>>(`/custom-objects/${key}/records`, {
        params: { page, pageSize: 25, ...(search ? { search } : {}) },
      })).data,
    enabled: !!key,
  });
}

export function useSaveCustomRecord(key: string) {
  return useInvalidating(
    async ({ id, values }: { id?: string; values: Record<string, unknown> }) =>
      id
        ? (await api.patch(`/custom-objects/${key}/records/${id}`, { values })).data
        : (await api.post(`/custom-objects/${key}/records`, { values })).data,
    [['custom-objects', key]],
    tx('Record saved'),
  );
}

export function useDeleteCustomRecord(key: string) {
  return useInvalidating(
    async (id: string) => (await api.delete(`/custom-objects/${key}/records/${id}`)).data,
    [['custom-objects', key]],
    tx('Record deleted'),
  );
}

// ---- print templates -----------------------------------------------------------------------------------------------

export function usePrintTemplates(kind?: string) {
  return useQuery({
    queryKey: ['print-templates', kind],
    queryFn: async () => (await api.get<PrintTemplate[]>('/print-templates', { params: kind ? { documentKind: kind } : {} })).data,
  });
}

export function usePrintTemplate(id: string | undefined) {
  return useQuery({
    queryKey: ['print-templates', 'one', id],
    queryFn: async () => (await api.get<PrintTemplate>(`/print-templates/${id}`)).data,
    enabled: !!id,
  });
}

export function useTemplateFields(kind: string) {
  return useQuery({
    queryKey: ['print-templates', 'fields', kind],
    queryFn: async () => (await api.get<TemplateField[]>(`/print-templates/fields/${kind}`)).data,
  });
}

export function useTemplateVersions(id: string | undefined) {
  return useQuery({
    queryKey: ['print-templates', 'versions', id],
    queryFn: async () => (await api.get<{ id: string; version: number; createdAt: string }[]>(`/print-templates/${id}/versions`)).data,
    enabled: !!id,
  });
}

export function useSavePrintTemplate() {
  return useInvalidating(
    async ({ id, ...input }: Partial<PrintTemplate> & { id?: string }) =>
      (id ? await api.patch<PrintTemplate>(`/print-templates/${id}`, input) : await api.post<PrintTemplate>('/print-templates', input)).data,
    [['print-templates']],
    tx('Template saved'),
  );
}

export function useRestoreTemplateVersion() {
  return useInvalidating(
    async ({ id, version }: { id: string; version: number }) =>
      (await api.post<PrintTemplate>(`/print-templates/${id}/versions/${version}/restore`)).data,
    [['print-templates']],
    tx('Version restored'),
  );
}

export function useDeletePrintTemplate() {
  return useInvalidating(async (id: string) => (await api.delete(`/print-templates/${id}`)).data, [['print-templates']], tx('Template deleted'));
}

export async function previewTemplate(input: {
  documentKind: string;
  content: string;
  css?: string;
  recordId?: string;
  paperSize?: string;
  orientation?: string;
}) {
  return (await api.post<{ html: string; sample: boolean }>('/print-templates/preview', input)).data;
}

// ---- automation ----------------------------------------------------------------------------------------------------

export function useAutomationCatalog() {
  return useQuery({
    queryKey: ['automation', 'catalog'],
    queryFn: async () =>
      (await api.get<{ triggers: { event: string; label: string; entity: string }[]; operators: string[]; actions: string[] }>(
        '/automation-rules/catalog',
      )).data,
    staleTime: 60 * 60_000,
  });
}

export function useAutomationRules() {
  return useQuery({
    queryKey: ['automation', 'rules'],
    queryFn: async () => (await api.get<AutomationRule[]>('/automation-rules')).data,
  });
}

export function useAutomationRuns(id: string | undefined) {
  return useQuery({
    queryKey: ['automation', 'runs', id],
    queryFn: async () => (await api.get<PaginatedResult<AutomationRun>>(`/automation-rules/${id}/runs`, { params: { pageSize: 50 } })).data,
    enabled: !!id,
  });
}

export function useSaveAutomationRule() {
  return useInvalidating(
    async ({ id, ...input }: Partial<AutomationRule> & { id?: string }) =>
      (id ? await api.patch(`/automation-rules/${id}`, input) : await api.post('/automation-rules', input)).data,
    [['automation']],
    tx('Rule saved'),
  );
}

export function useDeleteAutomationRule() {
  return useInvalidating(async (id: string) => (await api.delete(`/automation-rules/${id}`)).data, [['automation']], tx('Rule deleted'));
}

export function useTestAutomationRule() {
  return useMutation({
    mutationFn: async ({ id, recordId }: { id: string; recordId: string }) =>
      (await api.post<{ matched: boolean; conditions: { field: string; op: string; actual: unknown; holds: boolean }[]; actions: Record<string, unknown>[] }>(
        `/automation-rules/${id}/test`,
        { recordId },
      )).data,
  });
}

// ---- API clients ---------------------------------------------------------------------------------------------------

export function useApiClients() {
  return useQuery({
    queryKey: ['api-clients'],
    queryFn: async () => (await api.get<ApiClient[]>('/api-clients')).data,
  });
}

export function useApiScopes() {
  return useQuery({
    queryKey: ['api-clients', 'scopes'],
    queryFn: async () => (await api.get<{ scopes: string[]; grantable: string[] }>('/api-clients/scopes')).data,
  });
}

export function useCreateApiClient() {
  return useInvalidating(
    async (input: { name: string; scopes: string[]; expiresAt?: string }) => (await api.post<ApiClient>('/api-clients', input)).data,
    [['api-clients']],
  );
}

export function useUpdateApiClient() {
  return useInvalidating(
    async ({ id, ...input }: { id: string; name?: string; scopes?: string[]; isActive?: boolean }) =>
      (await api.patch<ApiClient>(`/api-clients/${id}`, input)).data,
    [['api-clients']],
    tx('API client updated'),
  );
}

export function useRotateApiClient() {
  return useInvalidating(async (id: string) => (await api.post<ApiClient>(`/api-clients/${id}/rotate-secret`)).data, [['api-clients']]);
}

export function useRevokeApiClient() {
  return useInvalidating(async (id: string) => (await api.delete(`/api-clients/${id}`)).data, [['api-clients']], tx('API client revoked'));
}
