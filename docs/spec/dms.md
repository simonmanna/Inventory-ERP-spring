# DMS — centralized document management

Extension module (not in the Nest reference). Design: [ADR-S044](../adr/ADR-S044-document-management.md); search /
OCR: [ADR-S046](../adr/ADR-S046-dms-text-extraction.md). Tests: `DmsIT` (DM-0..DM-7), `SecurityChecklistIT`.

## Routes

| Verb | Path | Permission | Purpose |
|---|---|---|---|
| GET | `/dms/workspaces` | `dms:document:read` | Workspaces the caller may see, with `access` and `documentCount` |
| POST / PATCH / DELETE | `/dms/workspaces[/{id}]` | `dms:workspace:manage` | Create, edit, archive (system workspaces cannot be archived) |
| GET / PUT | `/dms/workspaces/{id}/access` | `dms:workspace:manage` | Access rules (`{rules:[{roleId|userId, level: read|write|manage}]}`) |
| GET | `/dms/workspaces/{id}/folders` | `dms:document:read` | Folders of a workspace |
| POST / PATCH / DELETE | `/dms/folders[/{id}]` | `dms:document:write` | Create, rename / move (no cycles), delete when empty |
| GET | `/dms/tags`, `/dms/tag-categories` | `dms:document:read` | Tags (with category, count) |
| POST / PATCH / DELETE | `/dms/tags[/{id}]`, POST / DELETE `/dms/tag-categories[/{id}]` | `dms:tag:manage` | Manage tags |
| GET | `/dms/documents` | `dms:document:read` | `?workspaceId&folderId&root&tagId&resType&resId&q&status&expiringBefore&deleted&mine&sort&page&pageSize`; `{data, meta}`; with `q`, a `snippet` («highlight») |
| POST | `/dms/documents` | `dms:document:write` | Multipart `file` (repeat for many, ≤ 20) + `workspaceId, folderId, resType, resId, tags, description, expiresOn, reminderLeadDays, partnerId` |
| GET / PATCH / DELETE | `/dms/documents/{id}` | read / write / `dms:document:delete` | Detail (+ versions); patch `name, description, folderId, moveToRoot, workspaceId, expiresOn, clearExpiry, reminderLeadDays, partnerId, tagIds, version`; soft delete |
| POST | `/dms/documents/{id}/restore` | `dms:document:delete` | Undelete |
| GET / POST | `/dms/documents/{id}/versions` | read / write | List; upload a new version (`file`, `note`) |
| POST | `/dms/documents/{id}/versions/{n}/restore` | write | Make version *n* current (as a new version) |
| POST / DELETE | `/dms/documents/{id}/links[/{linkId}]` | write + record write permission | Link / unlink a record |
| POST | `/dms/documents/{id}/lock`, `/unlock`, `/archive`, `/unarchive`, `/submit` | write | Lock, archive, submit for approval |
| POST | `/dms/documents/{id}/approve`, `/reject` | `dms:document:approve` | Decide (`{comment}`) |
| POST | `/dms/documents/{id}/signed-url?version=` | read | `{url, previewUrl, contentType, filename, expiresAt}` (1 h) |
| GET | `/dms/documents/{id}/timeline` | read | Activity |
| POST | `/dms/documents/bulk` | write (`delete` also needs `dms:document:delete`) | `{ids, action: move|tag|archive|unarchive|delete, workspaceId, folderId, tagIds}` |
| GET | `/dms/documents/resource-types` | read | Record types documents can be linked to |
| GET / POST | `/dms/records/{resType}/{resId}/attachments` | read / write + the record's permission | Attachment panel of a record |
| GET | `/dms/records/{resType}/{resId}/count` | read + record read | Badge count |
| GET / POST | `/dms/requests` | read / `dms:request:create` | `?scope=assigned|sent|all`; create `{title, requestedFromUserId, workspaceId, resType, resId, dueOn, note}` |
| POST | `/dms/requests/{id}/fulfil`, `/cancel` | read | Assignee uploads (multipart); requester cancels |
| GET / POST | `/dms/share-links` | `dms:document:share` | List (`?documentId`); create `{documentIds | workspaceId+folderId, allowDownload, allowUpload, password, expiresInDays, maxDownloads}` → `token` + `url` once |
| POST | `/dms/share-links/{id}/revoke` | `dms:document:share` | Revoke |
| GET | `/public/dms/share/{token}` | public (token, `X-Share-Password`) | Shared files |
| GET | `/public/dms/share/{token}/documents/{id}/download?inline=` | public | Download (counts against the limit) |
| POST | `/public/dms/share/{token}/upload` | public | Upload into a folder share (`file`, `uploaderName`) |

Record types (`resType`): `crm_deal`, `crm_case`, `sales_quotation`, `sales_order`, `purchase_order`, `rfq`, `expense`,
`vendor_bill`, `invoice`, `partner`, `product`, `employee`, `asset`, `helpdesk_ticket`.

## Rules

- Hidden or foreign documents answer **404** (`Document not found`), never 403, so ids cannot be probed.
- Locked documents: only the locker or a workspace manager may change them (`409 Document is locked by another user`).
- `linked` documents (module-owned attachments surfaced by `DmsJobs.indexLegacy`) are read-only:
  `400 This file is managed by its module and is read-only here`.
- Optimistic concurrency: PATCH with `version` → `409` when stale.
- Upload limits: `UPLOAD_MAX_BYTES` per file; blocked extensions / active content → `400 File type not allowed`.

## Jobs

| Job | Schedule | What |
|---|---|---|
| `dms-text-extraction` | every 5 min (`app.dms.extract-interval-ms`) | Extract text of pending documents (also right after each upload) |
| `dms-expiry` | daily 06:15 (`app.dms.expiry-cron`) | Expiry reminders / expired facts, overdue request reminders |
| `dms-legacy-index` | every 10 min | Surface module attachments, follow their deletes |

## Events

`dms.document.uploaded|versioned|moved|linked|deleted|approved|rejected|expiring|expired`,
`dms.request.created|fulfilled`, `dms.share.accessed`.

## Web

`/dms` (Documents app: workspaces, folders, tags, quick filters, search with highlights, grid / list, drag-to-move,
drag-and-drop upload, inspector with versions, links, activity, lock / archive / approval / share), `/dms/requests`,
`/dms/settings` (workspaces + access rules, tags, share links), `/share/:token` (public). The shared
`AttachmentPanel` is on CRM opportunities, sales orders, purchase orders, invoices (tab), vendor bills, expenses
(receipts) and helpdesk tickets.

## Deviations

DM-0 hardening of `/files` (permissions, organization-bound signed URLs, blocked file types, `SAMEORIGIN` framing) —
see ADR-S044. Owner-approved.
