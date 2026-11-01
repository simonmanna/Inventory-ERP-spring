# Intercompany & multi-company (`com.inventoryerp.intercompany`)

Extension module (no NestJS counterpart), roadmap 2.7, design: `docs/adr/ADR-S028-intercompany-multi-company.md`,
migration `V220__intercompany.sql`. Every company stays its own organization (tenant); the module works company by
company and never widens a tenant.

## Routes (`/api/v1`)

| Verb | Path | Permission | Notes |
|---|---|---|---|
| GET | `/intercompany/group` | `intercompany:read` | the caller's group, empty body when none |
| POST | `/intercompany/group` | `intercompany:manage` | `{name}`; caller becomes the parent; 409 when already in a group |
| PATCH | `/intercompany/group` | `intercompany:manage` | rename; parent only |
| DELETE | `/intercompany/group` | `intercompany:manage` | parent only, after every subsidiary is removed |
| POST | `/intercompany/group/members` | `intercompany:manage` | `{organizationCode, ownershipPercent?}`; caller needs a same-e-mail account with `intercompany:manage` there |
| PATCH | `/intercompany/group/members/:organizationId` | `intercompany:manage` | `{ownershipPercent}` (0 < % ≤ 100) |
| DELETE | `/intercompany/group/members/:organizationId` | `intercompany:manage` | not the parent |
| GET | `/intercompany/companies` | `intercompany:switch` | current company first, then members where the e-mail has an active account |
| POST | `/intercompany/companies/:organizationId/switch` | `intercompany:switch` | login-shaped token response for the twin account; MFA rule |
| GET | `/intercompany/partners` | `intercompany:read` | each other member with its linked partner |
| PUT | `/intercompany/partners/:counterpartOrganizationId` | `intercompany:manage` | `{partnerId?}`; creates an `IC-<code>` partner when omitted |
| GET / PUT | `/intercompany/settings` | read / manage | `{mirrorMode: off \| draft \| post, billExpenseAccountId}` |
| GET | `/intercompany/transactions?status&page&pageSize` | `intercompany:read` | mirrored documents sent and received (`direction`) |
| POST | `/intercompany/transactions/:id/retry` | `intercompany:manage` | source company; failed / skipped rows |
| GET | `/intercompany/journals?page&pageSize` | `intercompany:read` | sent and received |
| POST | `/intercompany/journals` | `intercompany:post` | `Idempotency-Key` required; `{targetOrganizationId, date, description, amount, sourceAccountId, targetAccountCode \| targetAccountId}` |
| GET | `/intercompany/reconciliation?asOf` | `intercompany:read` | parent only; per pair positions and difference |
| GET | `/intercompany/consolidation?from&to&statement=pl\|tb` | `intercompany:read` | parent only; per company, `eliminations`, `consolidated`; NCI |

## Messages (verbatim)

`This company is not in a company group`, `Only the parent company manages the company group`,
`The company is not another member of your company group`, `This company already belongs to a company group`,
`The company is already in this group`, `The company already belongs to another company group`,
`You need an active account with intercompany:manage in that company to add it to the group`,
`The parent company is always owned 100%`, `The parent company cannot leave its group; delete the group instead`,
`Remove every subsidiary before deleting the company group`, `You are already signed in to this company`,
`The company is not in your company group`, `You have no active account in this company`,
`Sign in to this company directly: it requires MFA`, `The partner already stands for another company of the group`,
`billExpenseAccountId must be a postable account`, `The document is already mirrored`,
`The source document is no longer posted`, `Set the expense account for intercompany bills in the receiving company`,
`You need an active account with intercompany:post in the other company`,
`Give exactly one of targetAccountId or targetAccountCode`, `Account <code> not found in the other company`.

## Events

Consumes `invoice.posted` (mirroring). Posting publishes the usual `journal.posted` in each company.

## Tests

`IntercompanyIT` (6): group + consent + isolation, switching, mirroring (idempotent), journal (atomic, idempotent,
books-lock rollback), reconciliation + consolidation (eliminations, NCI), authorization matrix (19 routes).

## Deviations

None (extension).
