# ADR-S044: Centralized document management (DMS)

- Status: Accepted (owner approved the plan and asked for DM-0..DM-7, 2026-11-02)
- Spec: [`docs/spec/dms.md`](../spec/dms.md)
- Migration: `V238__dms_core.sql`
- Related: [ADR-S045](ADR-S045-helpdesk-correspondence.md) (helpdesk), [ADR-S046](ADR-S046-dms-text-extraction.md)
  (search / OCR)

## Context

Files were scattered: a kernel file vault (`File`, owner type / id), plus one attachment table per module (HR,
assets, fleet, repair, business documents, chat). There was no way to attach a file to a CRM opportunity, a sales or
purchase order, an expense or an invoice, no central place to find a file, no folders, tags, versions, search,
expiry, approval or external sharing. The owner asked for Odoo-style document management across the ERP.

The name `documents` is taken: it is the business-document engine (invoices, credit notes, lifecycles, templates).

## Decision

1. **New module `dms`** (`com.inventoryerp.dms`), permissions `dms:*`, events `dms.*`, web routes `/dms/*`.
   Spring-native: no Nest reference, so there is no parity to keep; existing routes are untouched.
2. **Bytes stay in the kernel vault.** A `DmsDocument` references `File.id`; each version is a new `File`
   (`ownerType = dms_document`). No second storage, so local / S3, backups and signed URLs stay one mechanism.
3. **Workspaces → folders → documents**, with tags (optional categories), versions, an activity log, links to records
   (many links per document), lock, archive, soft delete / restore, bulk move / tag / archive / delete.
   System workspaces per ERP area are created on first use: general, crm, sales, purchase, expenses, finance, hr,
   helpdesk, correspondence, shared_uploads.
4. **Record types through an SPI** (`dms.spi.DmsResourceProvider`): type key, label, read / write permission, default
   workspace, display name, web path. Built-ins for the core records (CRM opportunity / case, sales quotation /
   order, purchase order, RFQ, expense, vendor bill, invoice, partner, product, employee, fixed asset) read only the
   record's label by SQL, so the DMS does not depend on those modules. Other modules register beans
   (`helpdesk_ticket`). Modules attach files through `dms.spi.DmsAttachments`.
5. **Access = DMS permission + workspace rule, or record permission.** A workspace may require a permission
   (`sales` needs `sales:order:read`) and may carry access rules (role / user → read / write / manage); without rules
   it is open to DMS users. A document linked to a record is also readable by whoever may read that record, and
   attaching to a record needs the record's write permission. Hidden documents answer 404.
6. **Module-owned attachments are surfaced, not migrated.** A job mirrors `File` rows of known owner types as
   read-only `linked` documents (one per file) and follows their soft deletes. The owning module stays the writer;
   no client contract changes.
7. **Expiry, requests, approvals.** Documents may expire (reminder lead days); a daily job notifies the owner once
   before and once after and emits `dms.document.expiring|expired`; a new version re-arms the reminders. Document
   requests ask a user for a file with a due date (overdue reminder). A workspace may require approval: uploads wait
   as `pending_approval`; the kernel approval engine is asked first (`entityType = dms_document`), otherwise an
   approver with `dms:document:approve` decides.
8. **Share links.** 256-bit random token, only its SHA-256 stored; expiry (≤ 365 days), optional PBKDF2 password
   (`X-Share-Password` header, never in the URL), download limit, optional upload into a folder (uploads land
   pending approval). Public routes are `@Public` + rate limited and run inside the link's organization.

## DM-0 hardening of the file vault (deliberate deviations from Nest, owner-approved)

- `/files` routes enforce `files:read|write|delete` (Nest declared the permissions but never checked them).
- Signed download tokens are HMAC over `id:expires:organizationId`; a URL is only signed for a file of the caller's
  organization; token comparison is constant-time.
- Executable / script / HTML uploads are refused (`File type not allowed`); downloads are `attachment` +
  `nosniff` + a locked-down CSP; `inline=true` is honoured only for PDF, images, plain text, CSV, MP3 / MP4.
- `X-Frame-Options` is `SAMEORIGIN` (was `DENY`) in Spring and nginx so the web app can frame its own previews.
- The S3 driver streams downloads instead of buffering whole objects; `.env.example` uses the variable names the
  code reads (`S3_ACCESS_KEY_ID`, `S3_SECRET_ACCESS_KEY`) and the real 25 MB default.

## Consequences

- One attachment panel (`web/src/components/dms/AttachmentPanel.tsx`) serves every record page.
- Roles that uploaded through `/files` without `files:write` (e.g. product images by a role with only
  `product:update`) now need `files:write`; Administrator gets every new permission at boot.
- Legacy documents stay editable only in their module; the Documents app shows them read-only.
