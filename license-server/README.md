# license-server — the licence authority

The central service that issues, renews and revokes licences for Inventory ERP.
See [ADR-S002](../docs/adr/ADR-S002-licensing.md) for why it exists and
[docs/spec/licensing.md](../docs/spec/licensing.md) for the full design.

**This runs on your infrastructure, never a customer's.** It is the only place a signing private key
exists; an ERP install holds public keys only and so cannot mint a licence for itself.

---

## Run it

```bash
# From the repo root. Postgres on 5435, the service on 3100.
docker compose --profile license up -d --build

# Or locally, against that database:
cd license-server && ./gradlew bootRun
```

Two secrets have no sane default and the service refuses to start under the `prod` profile without
them (`StartupChecks` says which is missing):

| Variable | What it protects |
|---|---|
| `LICENSE_SIGNING_PASSPHRASE` | Wraps every signing private key at rest (AES-256-GCM, PBKDF2-SHA256 ×600k). **Back it up somewhere other than this repo.** Lose it and you cannot sign new licences. |
| `LICENSE_ADMIN_JWT_SECRET` | Signs admin sessions. Without it, every restart logs admins out. |

On first boot with no admin accounts, set `LICENSE_ADMIN_EMAIL` and `LICENSE_ADMIN_PASSWORD` (12+
characters) once; remove them from the environment afterwards.

---

## Selling a licence

```bash
BASE=http://localhost:3100/api/v1
TOKEN=$(curl -s $BASE/admin/auth/login -H 'Content-Type: application/json' \
  -d '{"email":"owner@example.com","password":"..."}' | jq -r .accessToken)

# 1. The customer
CUSTOMER=$(curl -s $BASE/admin/customers -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Acme Cafe Ltd","email":"acme@example.com"}' | jq -r .id)

# 2. The licence, issued immediately. `GET /admin/plans` lists what is on the price list.
curl -s $BASE/admin/licenses -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d "{\"customerId\":\"$CUSTOMER\",\"planCode\":\"pro-monthly\",\"issueNow\":true}" | jq '.key, .expiresAt'
```

Send the customer the `key`. They enter it under **Settings → Licence** in the ERP and the install
activates itself.

### A customer with no internet

1. They read their **installation code** off Settings → Licence (or `GET /license/fingerprint`).
2. You mint a file bound to it:
   ```bash
   curl -s $BASE/admin/licenses/<id>/offline-token -H "Authorization: Bearer $TOKEN" \
     -H 'Content-Type: application/json' \
     -d '{"fingerprint":"<their 64-hex code>","instanceName":"Acme main till"}' -o acme.lic
   ```
3. They upload `acme.lic` on the same page. Same token format, same verification — only the
   transport differs. An offline licence cannot be refreshed remotely, so renewing one means
   sending another file.

---

## Before cutting an ERP release

Pin this authority's public keys into the ERP build, or an air-gapped install cannot verify an
offline licence:

```bash
curl -s $BASE/admin/keys/pinned -H "Authorization: Bearer $TOKEN" \
  -o ../backend/src/main/resources/license-keys.json
```

A connected install fetches and caches the keys on first contact, so this only matters for
deployments that will never reach the server — but that is exactly the case where it is hard to fix
later.

Rotating a key (`POST /admin/keys/rotate`) is safe at any time: the retired key stays published in
the JWKS until every token it signed has expired, so nothing in the field stops verifying.

---

## Day to day

| Route | For |
|---|---|
| `GET /admin/dashboard` | What is live, what lapses in 7/30 days, recurring revenue per currency, installs gone quiet, installs over their limits |
| `GET /admin/attention/silent` | Customers whose installs stopped checking in — the early warning that something is wrong |
| `GET /admin/attention/over-limit` | Customers running more devices, users or organizations than they pay for: the upsell list |
| `GET /admin/licenses/{id}/events` | One licence's whole history. This is what a disputed lockout is settled from |
| `GET /admin/audit` | Who did what, from where |

Renewal reminders go out automatically on the days in `app.reminders.days-before` (default 30, 14,
7, 3, 1), once per licence per milestone. `POST /admin/licenses/{id}/remind` sends one now.

### Roles

`owner` decides — issuing, renewing, suspending, revoking, editing plans, minting offline files,
rotating keys. `support` can read everything and move an activation onto new hardware, which is the
day-to-day of helping a customer, but cannot change what is sold or who holds the keys.

---

## What this service deliberately does not do

- **It is not in the path of a sale.** Licences verify offline on the install; this server is needed
  to issue, renew and revoke, not to keep a shop trading. An outage here is invisible to customers.
- **It takes no payments.** It records what was agreed (`priceMinor`, in integer minor units) so the
  dashboard can estimate recurring revenue. Billing lives elsewhere.
- **It never receives business data.** A check-in reports counts, a version and an enforcement mode.
  No customer, product or sales data ever leaves a customer's premises.
