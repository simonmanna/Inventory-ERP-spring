# Customer portal (`portal`)

Status: built, unverified (nothing compiled or run). Design: [ADR-S050](../adr/ADR-S050-customer-portal.md). Migration
`V80__customer_portal.sql`. Flag: `ENABLE_PORTAL`. Permissions: `portal:customer` (a customer's only permission),
`portal:manage` (staff; Administrator).

## Customer routes (`portal:customer`, read-only, scoped to the signed-in customer)

| Route | Result |
|---|---|
| `GET /portal/home` | customer, organization, open balance, live deliveries, open orders |
| `GET /portal/orders` (`status, page, pageSize`), `GET /portal/orders/{id}` | orders (no drafts); detail with lines and deliveries |
| `GET /portal/deliveries` (`scope` = active, done, all), `GET /portal/deliveries/{id}` | trips; detail with lines, proof of delivery and, while dispatched or in transit, the truck's last position since dispatch |
| `GET /portal/invoices` (`open, page, pageSize`), `GET /portal/invoices/{id}`, `GET /portal/invoices/{id}/pdf` | posted and paid invoices, manual and POS |
| `GET /portal/statement` (`from, to`), `GET /portal/statement.pdf` | statement of account from the GL |

## Staff routes (`portal:manage`)

`GET /portal-accounts` (`partnerId, search, page, pageSize`), `GET /portal-accounts/customers` (`search`),
`POST /portal-accounts` (`partnerId, email, password, firstName, lastName`; 201), `POST /portal-accounts/{id}/disable`,
`POST /portal-accounts/{id}/enable`, `POST /portal-accounts/{id}/password` (204; ends all sessions).

## Screens

Customer: `/portal`, `/portal/orders`, `/portal/deliveries`, `/portal/invoices`, `/portal/statement`, each with a detail
page, in `PortalShell` (no staff sidebar, phone-friendly). Delivery detail refreshes every 30 seconds. `ProtectedRoute`
sends a portal login from any other path to `/portal`. Staff: `/settings/portal-accounts`.

## Known gaps

Customers cannot place an order from the portal: see `docs/DECISIONS_FOR_OWNER.md`. No map view: the last position links
to a map. POD signature and photo are not shown to the customer.
