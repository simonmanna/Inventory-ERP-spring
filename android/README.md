# POS Cafe — Android (offline-first)

Native Kotlin POS terminal that works **fully offline** and syncs with the
Inventory-ERP **Spring Boot** backend (`backend/`, module `com.inventoryerp.sync`)
when it reconnects. It runs standalone: its own encrypted database, its own
receipt printing, its own cash sessions.

Migrated unchanged from `POS-CAFE/apps/android` (which synced with the NestJS API).
The Spring API keeps the Nest wire contract, so the app only needed its defaults
and docs retargeted — same package (`com.poscafe.pos`), so it installs over the
old build. A device enrolled against the Nest server must be **re-enrolled**
against Spring (new server URL, device id and token).

Protocol: [`docs/legacy/sync-protocol.md`](../docs/legacy/sync-protocol.md) ·
contract: [`docs/CONTRACT.md`](../docs/CONTRACT.md) §10.
Setup on a real phone: [`docs/legacy/lan-deployment.md`](../docs/legacy/lan-deployment.md).

## Server URL

Point the setup screen at the API base including `/api/v1`:

| Deployment | URL |
|---|---|
| Full stack (`docker compose up -d --build`, nginx) | `http://<host-ip>:8080/api/v1` |
| API only (`cd backend && ./gradlew bootRun`) | `http://<host-ip>:3001/api/v1` |

Enroll in the web back office (Settings → Offline devices, `POST /sync/devices/register`);
copy the device id, the one-time token and the prefix into the tablet.

## Build (no Android Studio needed)

Requires a JDK 17+ and the Android SDK. If Android Studio is installed you
already have both — you just never have to open it.

```bash
cd android
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"   # or any JDK 17+
./gradlew :app:assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # pricing parity + cart maths
```

`local.properties` must point at your SDK (git-ignored):

```properties
sdk.dir=C:/Users/Simon/AppData/Local/Android/Sdk
```

Install it:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Toolchain: Gradle 8.13 · AGP 8.7.3 · Kotlin 2.0.21 · compileSdk 35 · minSdk 26.

## Stack

| Concern | Choice |
|---|---|
| UI | Jetpack Compose (Material 3) |
| DI | Hilt |
| Local DB | Room over **SQLCipher** (key in Android Keystore) |
| Network | Retrofit + OkHttp + kotlinx.serialization |
| Background sync | WorkManager (periodic 15 min + on demand) |
| Offline login | bcrypt verified on-device against the synced `pinHash` |
| Printing | ESC/POS 48-col over TCP 9100 |

## How it works

1. **Enroll once.** A manager registers the device in the web back office
   (Settings → Offline devices) and gets a token shown *once*. Enter the server
   URL, device id, token and prefix on the tablet's setup screen; the first
   pull seeds the catalog.
2. **Sell offline.** Menu, cart and checkout run entirely from Room. Each sale
   gets a provisional number (`D1-000042`, printed with an OFFLINE marker) and
   is appended to the op queue.
3. **Sync.** `SyncWorker` pushes the queue (`POST /sync/push` — per-op
   idempotency, so a re-push never double-charges), then pulls catalog deltas.
   The response maps provisional → final invoice numbers.
4. **Failures stay visible.** Ops the server rejects are dead-lettered
   server-side and flagged locally on the Sync screen. Money is never silently
   dropped.

## Pricing parity — important

The device prices a cart offline and prints a receipt; the server re-prices the
same cart on sync. **They must agree.** Both sides assert the same golden file,
`app/src/test/resources/cart-vectors.json`:

- Android: `CartVectorParityTest` (Kotlin `CartEngine`)
- Spring: `backend/src/test/java/com/inventoryerp/invoicing/CartVectorParityTest.java`
  (real `TaxCalculationService` + the `PosInvoiceService` discount folding)

```bash
cd backend && ./gradlew test --tests "*CartVectorParityTest"
cd android && ./gradlew :app:testDebugUnitTest
```

After a deliberate server pricing change, update the expected totals so the
backend test passes, then make the Android test pass too.

Note `MenuItem.basePrice` arrives in MINOR units (×100) and is converted once at
pull-apply (`CartEngine.basePriceToMajor`); variants, modifier deltas and
accompaniment upcharges are already MAJOR.

## Networking

`network_security_config.xml` currently permits cleartext HTTP to any host
(LAN servers and the demo VPS run plain HTTP); put the API behind HTTPS before
exposing it on the public internet. The server URL is device config and can be
changed at any time without reinstalling (`DynamicHostInterceptor`).

## Cash & inventory sync

The till pulls the org's tender tiles, ledger accounts (pre-classified by
role), expense categories, stock locations and stock levels, and pushes ops
the server's shift and stock rules accept (see the protocol doc):

- **Shift**: open checks the float against the drawer ledger (extra float needs
  a funding account + reason); cash in/out picks its counterpart account;
  cash-outs need a manager PIN; close counts every shift-tracked wallet, asks
  for a reason on any difference and a manager PIN when the server will.
- **Terminal**: payment tiles come from the server; each tender carries its
  receiving account; cash change is sent as `amountTendered`.
- **Stock** (retail products): purchases, waste and adjustments sync as
  `stock.in` / `stock.out` with an approver; physical counts sync as a server
  spot count. On-hand shows the server figure plus unsynced local movements.
- **Expenses**: from the drawer (a cash-out), from a safe/bank/wallet, or on
  credit (`expense.create`).

Managers holding the permission approve their own stock movements without a
PIN; everyone else needs a manager PIN, verified on-device and again on replay.
Menu-item stock stays device-local (the server tracks ingredients via recipes).

## Not built yet

- Bluetooth SPP printing (TCP 9100 works; the transport seam is in place)
- Branch stock transfers from the device (back office only)
