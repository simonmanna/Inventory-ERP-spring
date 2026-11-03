import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api';

/** Document management (DMS, ADR-S044) client. */

export type DmsAccess = 'none' | 'read' | 'write' | 'manage';
export type DmsStatus = 'active' | 'pending_approval' | 'approved' | 'rejected' | 'archived';

export interface DmsWorkspace {
  id: string;
  code: string;
  name: string;
  description: string | null;
  parentId: string | null;
  icon: string | null;
  color: string | null;
  requiredPermission: string | null;
  requiresApproval: boolean;
  isSystem: boolean;
  sortOrder: number;
  documentCount: number;
  access: DmsAccess;
}

export interface DmsFolder {
  id: string;
  workspaceId: string;
  parentId: string | null;
  name: string;
  documentCount: number;
}

export interface DmsTag {
  id: string;
  name: string;
  color: string | null;
  categoryId: string | null;
  categoryName?: string | null;
  documentCount?: number;
}

export interface DmsTagCategory {
  id: string;
  name: string;
  sortOrder: number;
}

export interface DmsLink {
  id: string;
  resType: string;
  resId: string;
  typeLabel?: string;
  label?: string | null;
  webPath?: string | null;
}

export interface DmsVersion {
  id: string;
  versionNo: number;
  fileId: string;
  filename: string;
  contentType: string | null;
  byteSize: number;
  note: string | null;
  uploadedByName: string | null;
  createdAt: string;
}

export interface DmsDocument {
  id: string;
  workspaceId: string;
  folderId: string | null;
  name: string;
  description: string | null;
  fileId: string;
  contentType: string | null;
  byteSize: number;
  currentVersion: number;
  ownerUserId: string | null;
  ownerName: string | null;
  partnerId: string | null;
  status: DmsStatus;
  expiresOn: string | null;
  reminderLeadDays: number;
  lockedById: string | null;
  lockedByName: string | null;
  lockedAt: string | null;
  sourceKind: 'native' | 'linked';
  legacyOwnerType: string | null;
  textStatus: string;
  version: number;
  createdById: string | null;
  createdByName: string | null;
  createdAt: string;
  updatedAt: string;
  deletedAt: string | null;
  tags: DmsTag[];
  links: DmsLink[];
  access: DmsAccess;
  readOnly: boolean;
  snippet?: string | null;
  versions?: DmsVersion[];
}

export interface DmsPage {
  data: DmsDocument[];
  meta: { page: number; pageSize: number; total: number; totalPages: number };
}

export interface DmsListFilter {
  workspaceId?: string;
  folderId?: string;
  root?: boolean;
  tagId?: string;
  resType?: string;
  resId?: string;
  q?: string;
  status?: string;
  expiringBefore?: string;
  deleted?: boolean;
  mine?: boolean;
  sort?: string;
  page?: number;
  pageSize?: number;
}

export interface DmsActivity {
  id: string;
  action: string;
  detail: Record<string, unknown> | null;
  actorName: string | null;
  createdAt: string;
}

export interface DmsSigned {
  url: string;
  previewUrl: string | null;
  expiresAt: string;
  contentType: string | null;
  filename: string;
}

export interface DmsRequest {
  id: string;
  title: string;
  note: string | null;
  workspaceId: string;
  workspaceName: string | null;
  requestedFromUserId: string;
  requestedFromName: string | null;
  requestedById: string | null;
  requestedByName: string | null;
  resType: string | null;
  resId: string | null;
  dueOn: string | null;
  status: 'open' | 'fulfilled' | 'cancelled';
  fulfilledDocumentId: string | null;
  overdue?: boolean;
  createdAt: string;
}

export interface DmsShareLink {
  id: string;
  name: string | null;
  scope: 'documents' | 'folder';
  documentIds: string[];
  workspaceId: string | null;
  folderId: string | null;
  allowDownload: boolean;
  allowUpload: boolean;
  hasPassword: boolean;
  expiresAt: string;
  maxDownloads: number | null;
  downloadCount: number;
  uploadCount: number;
  lastAccessedAt: string | null;
  revokedAt: string | null;
  active: boolean;
  createdAt: string;
  token?: string;
  url?: string;
}

export interface DmsAccessRule {
  id: string;
  userId: string | null;
  roleId: string | null;
  roleName?: string | null;
  userName?: string | null;
  userEmail?: string | null;
  level: 'read' | 'write' | 'manage';
}

const KEY = ['dms'] as const;
const multipart = { headers: { 'Content-Type': 'multipart/form-data' } };

function useDmsMutation<TVars, TResult>(fn: (vars: TVars) => Promise<TResult>) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: KEY });
    },
  });
}

// ---- workspaces / folders / tags ----

export const useWorkspaces = (enabled = true) =>
  useQuery({
    queryKey: [...KEY, 'workspaces'],
    enabled,
    queryFn: async () => (await api.get<DmsWorkspace[]>('/dms/workspaces')).data,
  });

export const useFolders = (workspaceId?: string) =>
  useQuery({
    queryKey: [...KEY, 'folders', workspaceId],
    enabled: !!workspaceId,
    queryFn: async () => (await api.get<DmsFolder[]>(`/dms/workspaces/${workspaceId}/folders`)).data,
  });

export const useTags = () =>
  useQuery({ queryKey: [...KEY, 'tags'], queryFn: async () => (await api.get<DmsTag[]>('/dms/tags')).data });

export const useTagCategories = () =>
  useQuery({
    queryKey: [...KEY, 'tag-categories'],
    queryFn: async () => (await api.get<DmsTagCategory[]>('/dms/tag-categories')).data,
  });

export const useCreateWorkspace = () =>
  useDmsMutation(async (body: Record<string, unknown>) => (await api.post<DmsWorkspace>('/dms/workspaces', body)).data);

export const useUpdateWorkspace = () =>
  useDmsMutation(async ({ id, body }: { id: string; body: Record<string, unknown> }) =>
    (await api.patch<DmsWorkspace>(`/dms/workspaces/${id}`, body)).data);

export const useArchiveWorkspace = () =>
  useDmsMutation(async (id: string) => (await api.delete(`/dms/workspaces/${id}`)).data);

export const useAccessRules = (workspaceId?: string) =>
  useQuery({
    queryKey: [...KEY, 'access', workspaceId],
    enabled: !!workspaceId,
    queryFn: async () => (await api.get<DmsAccessRule[]>(`/dms/workspaces/${workspaceId}/access`)).data,
  });

export const useReplaceAccessRules = () =>
  useDmsMutation(async ({ id, rules }: { id: string; rules: Array<Pick<DmsAccessRule, 'userId' | 'roleId' | 'level'>> }) =>
    (await api.put<DmsAccessRule[]>(`/dms/workspaces/${id}/access`, { rules })).data);

export const useCreateFolder = () =>
  useDmsMutation(async (body: { workspaceId: string; parentId?: string | null; name: string }) =>
    (await api.post<DmsFolder>('/dms/folders', body)).data);

export const useUpdateFolder = () =>
  useDmsMutation(async ({ id, body }: { id: string; body: Record<string, unknown> }) =>
    (await api.patch<DmsFolder>(`/dms/folders/${id}`, body)).data);

export const useDeleteFolder = () => useDmsMutation(async (id: string) => (await api.delete(`/dms/folders/${id}`)).data);

export const useCreateTag = () =>
  useDmsMutation(async (body: { name: string; categoryId?: string | null; color?: string | null }) =>
    (await api.post<DmsTag>('/dms/tags', body)).data);

export const useDeleteTag = () => useDmsMutation(async (id: string) => (await api.delete(`/dms/tags/${id}`)).data);

export const useCreateTagCategory = () =>
  useDmsMutation(async (body: { name: string }) => (await api.post<DmsTagCategory>('/dms/tag-categories', body)).data);

export const useDeleteTagCategory = () =>
  useDmsMutation(async (id: string) => (await api.delete(`/dms/tag-categories/${id}`)).data);

// ---- documents ----

export const useDocuments = (filter: DmsListFilter, enabled = true) =>
  useQuery({
    queryKey: [...KEY, 'documents', filter],
    enabled,
    queryFn: async () => (await api.get<DmsPage>('/dms/documents', { params: filter })).data,
  });

export const useDocument = (id?: string | null) =>
  useQuery({
    queryKey: [...KEY, 'document', id],
    enabled: !!id,
    queryFn: async () => (await api.get<DmsDocument>(`/dms/documents/${id}`)).data,
  });

export const useTimeline = (id?: string | null) =>
  useQuery({
    queryKey: [...KEY, 'timeline', id],
    enabled: !!id,
    queryFn: async () => (await api.get<DmsActivity[]>(`/dms/documents/${id}/timeline`)).data,
  });

export interface UploadOptions {
  workspaceId?: string;
  folderId?: string | null;
  tags?: string[];
  description?: string;
  expiresOn?: string;
}

function formData(files: File[], fields: Record<string, string | undefined | null>) {
  const fd = new FormData();
  files.forEach((f) => fd.append('file', f));
  Object.entries(fields).forEach(([k, v]) => {
    if (v != null && v !== '') fd.append(k, v);
  });
  return fd;
}

export const useUploadDocuments = () =>
  useDmsMutation(async ({ files, options, onProgress }: {
    files: File[]; options: UploadOptions; onProgress?: (pct: number) => void;
  }) =>
    (await api.post<DmsDocument[]>('/dms/documents', formData(files, {
      workspaceId: options.workspaceId, folderId: options.folderId ?? undefined,
      tags: options.tags?.join(','), description: options.description, expiresOn: options.expiresOn,
    }), {
      ...multipart,
      timeout: 300_000,
      onUploadProgress: (e) => onProgress?.(e.total ? Math.round((e.loaded / e.total) * 100) : 0),
    })).data);

export const useUpdateDocument = () =>
  useDmsMutation(async ({ id, body }: { id: string; body: Record<string, unknown> }) =>
    (await api.patch<DmsDocument>(`/dms/documents/${id}`, body)).data);

export const useDeleteDocument = () =>
  useDmsMutation(async (id: string) => (await api.delete(`/dms/documents/${id}`)).data);

export const useRestoreDocument = () =>
  useDmsMutation(async (id: string) => (await api.post<DmsDocument>(`/dms/documents/${id}/restore`)).data);

export type DocAction = 'lock' | 'unlock' | 'archive' | 'unarchive' | 'submit' | 'approve' | 'reject';

export const useDocumentAction = () =>
  useDmsMutation(async ({ id, action, comment }: { id: string; action: DocAction; comment?: string }) =>
    (await api.post<DmsDocument>(`/dms/documents/${id}/${action}`,
      action === 'approve' || action === 'reject' ? { comment } : undefined)).data);

export const useAddVersion = () =>
  useDmsMutation(async ({ id, file, note }: { id: string; file: File; note?: string }) =>
    (await api.post<DmsDocument>(`/dms/documents/${id}/versions`, formData([file], { note }), {
      ...multipart, timeout: 300_000,
    })).data);

export const useRestoreVersion = () =>
  useDmsMutation(async ({ id, versionNo }: { id: string; versionNo: number }) =>
    (await api.post<DmsDocument>(`/dms/documents/${id}/versions/${versionNo}/restore`)).data);

export const useLinkDocument = () =>
  useDmsMutation(async ({ id, resType, resId }: { id: string; resType: string; resId: string }) =>
    (await api.post<DmsDocument>(`/dms/documents/${id}/links`, { resType, resId })).data);

export const useUnlinkDocument = () =>
  useDmsMutation(async ({ id, linkId }: { id: string; linkId: string }) =>
    (await api.delete<DmsDocument>(`/dms/documents/${id}/links/${linkId}`)).data);

export const useBulk = () =>
  useDmsMutation(async (body: { ids: string[]; action: string; workspaceId?: string; folderId?: string | null; tagIds?: string[] }) =>
    (await api.post<{ updated: number }>('/dms/documents/bulk', body)).data);

/** A short-lived signed URL (download, and inline preview for safe types). */
export async function signedUrl(id: string, version?: number): Promise<DmsSigned> {
  return (await api.post<DmsSigned>(`/dms/documents/${id}/signed-url`, undefined, {
    params: version ? { version } : undefined,
  })).data;
}

export const useResourceTypes = () =>
  useQuery({
    queryKey: [...KEY, 'resource-types'],
    staleTime: 10 * 60_000,
    queryFn: async () =>
      (await api.get<Array<{ resType: string; label: string; workspaceCode: string; readable: boolean }>>(
        '/dms/documents/resource-types')).data,
  });

// ---- record attachments ----

export const useRecordAttachments = (resType: string, resId?: string | null, enabled = true) =>
  useQuery({
    queryKey: [...KEY, 'record', resType, resId],
    enabled: enabled && !!resId,
    queryFn: async () => (await api.get<DmsDocument[]>(`/dms/records/${resType}/${resId}/attachments`)).data,
  });

export const useUploadRecordAttachments = () =>
  useDmsMutation(async ({ resType, resId, files, tags, onProgress }: {
    resType: string; resId: string; files: File[]; tags?: string[]; onProgress?: (pct: number) => void;
  }) =>
    (await api.post<DmsDocument[]>(`/dms/records/${resType}/${resId}/attachments`,
      formData(files, { tags: tags?.join(',') }), {
        ...multipart,
        timeout: 300_000,
        onUploadProgress: (e) => onProgress?.(e.total ? Math.round((e.loaded / e.total) * 100) : 0),
      })).data);

// ---- requests ----

export const useRequests = (scope: 'assigned' | 'sent' | 'all', status?: string) =>
  useQuery({
    queryKey: [...KEY, 'requests', scope, status],
    queryFn: async () => (await api.get<DmsRequest[]>('/dms/requests', { params: { scope, status } })).data,
  });

export const useCreateRequest = () =>
  useDmsMutation(async (body: Record<string, unknown>) => (await api.post<DmsRequest>('/dms/requests', body)).data);

export const useFulfilRequest = () =>
  useDmsMutation(async ({ id, files }: { id: string; files: File[] }) =>
    (await api.post<DmsRequest>(`/dms/requests/${id}/fulfil`, formData(files, {}), multipart)).data);

export const useCancelRequest = () =>
  useDmsMutation(async (id: string) => (await api.post<DmsRequest>(`/dms/requests/${id}/cancel`)).data);

// ---- share links ----

export const useShareLinks = (documentId?: string, enabled = true) =>
  useQuery({
    queryKey: [...KEY, 'share-links', documentId],
    enabled,
    queryFn: async () => (await api.get<DmsShareLink[]>('/dms/share-links', { params: { documentId } })).data,
  });

export const useCreateShareLink = () =>
  useDmsMutation(async (body: Record<string, unknown>) => (await api.post<DmsShareLink>('/dms/share-links', body)).data);

export const useRevokeShareLink = () =>
  useDmsMutation(async (id: string) => (await api.post<DmsShareLink>(`/dms/share-links/${id}/revoke`)).data);
