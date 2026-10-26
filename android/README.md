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
| Hosted VPS (TLS, private CA) | `https://169.58.5.88:8443/api/v1` |

Enroll in the web back office (Settings → Offline devices, `POST /sync/devices/register`);
copy the device id, the one-time token and the prefix into the tablet.

## Build (no Android Studio needed)

Requires a JDK 17+ and the Android SDK. If Android Studio is installed you
already have both — you just never have to open it.

```bash
cd android
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"   # or any JDK 17+
./gradlew :app:assembleDebug          # → app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:testDebugUnitTest      # pricing parity, cart maths, sync, PIN lockout (Robolectric)
./gradlew :app:lintDebug              # new lint issues fail; old ones live in app/lint-baseline.xml
```

### On-device tests (real phone, real server)

`androidTest` holds the Room `MigrationTest` and `e2e/PosDeviceE2ETest`, which
drives the installed app through its UI with UiAutomator: server change,
re-enrolment, cash sale, PIN lockout across a restart, revocation. Server
details are instrumentation arguments; a test without them is skipped.

Run them with `am instrument`, **not** `connectedDebugAndroidTest` — Gradle
uninstalls the app afterwards, which deletes the till's data:

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class com.poscafe.pos.MigrationTest   com.poscafe.pos.test/androidx.test.runner.AndroidJUnitRunner
adb shell am instrument -w -e class com.poscafe.pos.e2e.PosDeviceE2ETest#cashSale   -e cashierName "'Sarah Cashier'" -e cashierPin 1111 -e item Eclair   com.poscafe.pos.test/androidx.test.runner.AndroidJUnitRunner
```

Failure screenshots land in `/sdcard/Android/data/com.poscafe.pos/files/e2e/`.
On MIUI the system `uiautomator dump` never reports idle; the suite sets the
idle wait to 0 and launches the app through the shell for that reason.

### Release builds

To run the device E2E suite against the signed, minified release, build both
APKs with `-Ppos.testBuildType=release` (it adds `proguard-release-under-test.pro`,
which keeps the libraries the test APK shares with the app) and run
`am instrument` as above. Rebuild without the flag before shipping.

`assembleRelease` refuses to run unsigned. Signing comes from the environment or
`~/.gradle/gradle.properties`, never the repo:

```properties
POS_KEYSTORE_FILE=C:/keys/pos-release.jks
POS_KEYSTORE_PASSWORD=...
POS_KEY_ALIAS=pos
POS_KEY_PASSWORD=...
```

`versionCode` is the git commit count (override with `-Ppos.versionCode=N`),
`versionName` comes from `-Ppos.versionName=x.y.z`. CI
(`.github/workflows/android.yml`) runs tests + lint on every change under
`android/` and builds a signed APK for tags `android-vX.Y.Z` from the secrets
`POS_KEYSTORE_B64`, `POS_KEYSTORE_PASSWORD`, `POS_KEY_ALIAS`, `POS_KEY_PASSWORD`.
Keep the keystore backed up: a different key cannot update installed tills.

`local.properties` must point at your SDK (git-ignored):

```properties
sdk.dir=C:/Users/Simon/AppData/Local/Android/Sdk
```

Install it:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Toolchain: Gradle 8.13 · AGP 8.13 · Kotlin 2.0.21 · compileSdk/targetSdk 36 · minSdk 26.

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

All prices on the wire are MAJOR units, `MenuItem.basePrice` included (the
server's checkout and the web terminal use it as the unit price as is; the seed
stores 7000 for a UGX 7,000 item). Builds before catalog-units v1 wrongly
treated basePrice as MINOR (÷100 on pull, ×100 on push) and sold menu items at
1/100 of their price. On upgrade, an enrolled device rescales its queued
`menuItem.upsert` ops once and does one full catalog re-pull
(`SyncRepository.ensureCatalogUnits`, `CATALOG_UNITS_VERSION`). Sales rung up
by an old build are not rewritten — that is what was charged; void them in the
back office if needed.

## Networking

Plain HTTP is accepted only for local-network servers (private, loopback and
Tailscale IPs, `localhost`, `*.local`, `*.lan`, `*.internal`, `*.home.arpa`);
anything public must be `https://` (`ServerUrlPolicy`, checked at setup and on
every request so the device token never crosses the internet in clear).
Debug builds, and release builds made with `-Ppos.allowPublicCleartext=true`,
also accept public HTTP — a stopgap for a server not yet behind TLS.
`network_security_config.xml` still permits cleartext because it cannot express
IP ranges. The server URL is device config and can be changed at any time
without reinstalling (`DynamicHostInterceptor`).

### Hosted server TLS

The VPS's ports 80/443 belong to another site, so the POS stack is served over
TLS on `:8443` by its own nginx (`/opt/ierp/tls`, outside the source tree so
re-deploys never touch it). The certificate comes from a private CA:

- `/opt/ierp/tls/certs/ca.key` — CA key, never leaves the VPS. Back it up.
- The CA is name-constrained to `IP:169.58.5.88`; it cannot sign for anything else.
- `res/raw/ierp_vps_ca.pem` is that CA's certificate. `network_security_config.xml`
  trusts it **only** for `169.58.5.88`, and forbids cleartext to that address.
- Server cert expires **2029-01-27** (CA: 2036). Renew before then on the VPS by
  re-running the `openssl x509 -req … -days 825` step with the same CA, then
  `docker restart ierp-tls`; tills need no update. A new CA (or a domain with a
  public certificate) needs an app release with the new `ierp_vps_ca.pem`.

Browsers show a warning on `https://169.58.5.88:8443` because they don't know the
CA; staff use the web back office on `http://169.58.5.88:8093` until the server
has a domain name.

## Device security

- **Revocation.** When a device that has synced before gets a 401 on
  `/sync/*`, it deletes every staff row (PIN hashes), forgets its token and
  returns to setup. The rest of the database is cleared only if nothing is left
  to sync; otherwise unsynced sales stay encrypted and upload after re-enrolment.
- **PIN lockout** is stored on the device, so restarting the app does not reset
  it: after 3 wrong PINs each attempt waits 15 s, 30 s, 60 s, …; after 10 the
  account is locked until a manager resets that PIN in the back office (the
  new PIN hash arrives with the next sync). A plain sync or restart does not
  clear a lockout.
- **Key store loss.** If Android loses the Keystore key, the encrypted config
  and the database (whose key lived there) cannot be read; the app wipes both
  and returns to setup with a notice instead of crashing on every launch.
- **Re-enrolment** (More → Server address… → Re-enroll, manager PIN with
  `organization:update`) keeps every local row. The first pull of a new
  enrolment ignores the old server's cursor and is a full snapshot, which
  removes the previous server's staff (their PINs stop working) and catalog;
  rows referenced by unsynced ops are kept.
- **Shift references:** sales and cash ops name their shift by its local id.
  The server only maps that id within the batch that opened the shift, so ops
  pushed later are rewritten to the server id before their first push.
- **Schema upgrades** never wipe data: there is no destructive fallback, and
  `MigrationTest` upgrades a v2 database to the latest schema.
- **Rejected ops** stay on the Sync screen and block closing the shift. The
  server locks an op's idempotency key after any failure (Nest behaviour), so a
  plain resend only ever gets 409 "needs recovery". **Retry** therefore resends
  an op whose first rejection was definitive (400/403/404/422/424 — the server
  committed nothing) under a fresh key (`op_queue.wireOpId`); ops that failed
  with 5xx or 409 keep their key, as the server requires for possibly-committed
  work. Typical case: "A session is already open on register X" — close the
  other shift in the back office, then Retry all. If the server will never
  accept an op, **Discard…** with a manager PIN holding
  `pos:override` marks it `discarded`: it stops resending, leaves the shift
  totals, and the receipt shows "Discarded by <manager>". The server keeps its
  own dead-letter copy. Re-ring a sale if the money was taken.
- **Uninstalling deletes everything,** including unsynced sales: Android removes
  the Keystore key, so even a file backup cannot be decrypted. Use tills as
  dedicated devices (kiosk/MDM, or at least screen pinning) and keep them online.
- **Pushes right away.** Every newly queued op enqueues a sync job at once
  (`QueueWatcher`), so sales reach the back office within seconds instead of on
  the 15-minute periodic run; offline, the job waits for a network.
- **OEM force-dark** (MIUI "dark mode for all apps") is disabled for the app:
  Compose already draws its own dark theme, and the forced pass broke contrast.
- **Crash reports** are written to app storage and shown on the Sync screen,
  where staff can share them. Nothing is uploaded automatically.

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
