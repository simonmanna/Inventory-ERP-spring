package com.poscafe.pos.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.KeyStore
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Device-local secrets + settings, stored in EncryptedSharedPreferences whose
 * master key lives in the Android Keystore (hardware-backed where available):
 *   - serverUrl      LAN and/or cloud base URL, e.g. http://192.168.1.10:8080/api/v1/
 *   - deviceId/token from /sync/devices/register (token shown once by server)
 *   - prefix         provisional-number prefix, e.g. "D1"
 *   - dbPassphrase   SQLCipher key (generated on first run)
 *   - saleCounter    monotonic counter behind provisional numbers
 */
// commit() is deliberate where used: counters, keys and revocation must hit disk before we go on.
@SuppressLint("ApplySharedPref")
@Singleton
class DeviceConfig internal constructor(private val context: Context, testPrefs: SharedPreferences?) {

    @Inject constructor(@ApplicationContext context: Context) : this(context, null)

    /** [testPrefs] lets JVM tests use plain prefs: the Android Keystore is not available there. */
    private val prefs: SharedPreferences = testPrefs ?: openPrefs()

    /**
     * Some devices lose or corrupt the Keystore master key (OS update, factory
     * restore of the keystore, OEM bugs); EncryptedSharedPreferences then
     * throws on every launch and the app can never start. The SQLCipher
     * passphrase lived in those prefs, so the database is unreadable too:
     * recover by wiping both and starting over at setup, and record it so the
     * setup screen can say why.
     */
    private fun openPrefs(): SharedPreferences = try {
        createPrefs()
    } catch (e: Exception) {
        Log.e(TAG, "Encrypted config unreadable, resetting device", e)
        context.deleteSharedPreferences(PREFS_NAME)
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                .deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        }
        context.deleteDatabase(DB_NAME)
        createPrefs().also { it.edit().putBoolean("keystoreReset", true).commit() }
    }

    private fun createPrefs(): SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    /** True once after [openPrefs] had to wipe the device; cleared by [acknowledgeKeystoreReset]. */
    val keystoreReset: Boolean get() = prefs.getBoolean("keystoreReset", false)

    fun acknowledgeKeystoreReset() {
        prefs.edit().remove("keystoreReset").apply()
    }

    /** Set when the server revoked this device's token; drives the app back to setup. */
    private val _revoked = MutableStateFlow(prefs.getBoolean("revoked", false))
    val revoked: StateFlow<Boolean> = _revoked

    fun markRevoked(value: Boolean) {
        prefs.edit().putBoolean("revoked", value).commit()
        _revoked.value = value
        if (!value) _setupRequested.value = false
    }

    // ---- licence (from /sync/pull, persisted so it survives a restart offline) ----

    /**
     * What the installation's licence allows, as last reported by the server.
     *
     * Persisted deliberately: a till that is restarted with no network must still know whether it
     * may sell. The alternative — assuming the best — would let an expired licence be cleared by
     * pulling the plug, and the alternative to that — assuming the worst — would stop a shop every
     * time it rebooted before the first pull. Keeping the last known answer is the only honest
     * option, and it is also the one that fails in the customer's favour for exactly one session.
     */
    private val _license = MutableStateFlow(readLicense())
    val license: StateFlow<LicenseState> = _license

    /** The licence as a till needs it. */
    data class LicenseState(
        val mode: String = "ACTIVE",
        /** May this terminal start a new sale? */
        val writable: Boolean = true,
        val message: String? = null,
        val daysRemaining: Long? = null,
        val graceUntil: String? = null,
        /** True once a pull has actually reported a licence; false means "never heard". */
        val known: Boolean = false,
    ) {
        /** Worth putting on the screen: expiring soon, in grace, or stopped. */
        val warrants: Boolean get() = !writable || mode == "GRACE" ||
            (daysRemaining != null && daysRemaining <= 14)
    }

    private fun readLicense(): LicenseState = LicenseState(
        mode = prefs.getString("license.mode", "ACTIVE") ?: "ACTIVE",
        writable = prefs.getBoolean("license.writable", true),
        message = prefs.getString("license.message", null),
        daysRemaining = prefs.getLong("license.days", Long.MIN_VALUE)
            .takeIf { it != Long.MIN_VALUE },
        graceUntil = prefs.getString("license.graceUntil", null),
        known = prefs.getBoolean("license.known", false),
    )

    /** Record what a pull reported. A pull that carried no licence leaves the last answer alone. */
    fun applyLicense(mode: String, writable: Boolean, message: String?, daysRemaining: Long?,
                     graceUntil: String?) {
        prefs.edit()
            .putString("license.mode", mode)
            .putBoolean("license.writable", writable)
            .putString("license.message", message)
            .putLong("license.days", daysRemaining ?: Long.MIN_VALUE)
            .putString("license.graceUntil", graceUntil)
            .putBoolean("license.known", true)
            .commit()
        _license.value = LicenseState(mode, writable, message, daysRemaining, graceUntil, known = true)
    }

    /** A manager chose to re-enroll; in memory only, since a cleared enrollment sends start-up to setup anyway. */
    private val _setupRequested = MutableStateFlow(false)
    val setupRequested: StateFlow<Boolean> = _setupRequested

    fun requestSetup() {
        _setupRequested.value = true
    }

    // ---- offline PIN lockout (survives app restarts) ----

    fun pinFailures(userId: String): Int = prefs.getInt("pinFails.$userId", 0)

    fun lastPinFailureAt(userId: String): Long = prefs.getLong("pinFailAt.$userId", 0L)

    fun recordPinFailure(userId: String) {
        prefs.edit()
            .putInt("pinFails.$userId", pinFailures(userId) + 1)
            .putLong("pinFailAt.$userId", System.currentTimeMillis())
            .commit()
    }

    fun clearPinFailures(userId: String) {
        prefs.edit().remove("pinFails.$userId").remove("pinFailAt.$userId").commit()
    }

    /** Every lockout (tests; a device wipe). Syncs clear per user on a PIN reset only. */
    fun clearPinFailures() {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("pinFails.") || it.startsWith("pinFailAt.") }.forEach(editor::remove)
        editor.commit()
    }

    var serverUrl: String?
        get() = prefs.getString("serverUrl", null)
        set(v) = prefs.edit().putString("serverUrl", v).apply()

    var deviceId: String?
        get() = prefs.getString("deviceId", null)
        set(v) = prefs.edit().putString("deviceId", v).apply()

    var deviceToken: String?
        get() = prefs.getString("deviceToken", null)
        set(v) = prefs.edit().putString("deviceToken", v).apply()

    var prefix: String
        get() = prefs.getString("prefix", "D?") ?: "D?"
        set(v) = prefs.edit().putString("prefix", v).apply()

    var printerHost: String?
        get() = prefs.getString("printerHost", null)
        set(v) = prefs.edit().putString("printerHost", v).apply()

    /** Standalone mode: the app is the whole system — no server, no sync. */
    var standalone: Boolean
        get() = prefs.getBoolean("standalone", false)
        set(v) = prefs.edit().putBoolean("standalone", v).apply()

    // ---- business profile (receipt header/footer) ----
    var businessName: String
        get() = prefs.getString("businessName", "POS CAFE") ?: "POS CAFE"
        set(v) = prefs.edit().putString("businessName", v).apply()

    var businessAddress: String?
        get() = prefs.getString("businessAddress", null)
        set(v) = prefs.edit().putString("businessAddress", v).apply()

    var businessTin: String?
        get() = prefs.getString("businessTin", null)
        set(v) = prefs.edit().putString("businessTin", v).apply()

    var receiptFooter: String
        get() = prefs.getString("receiptFooter", "Thank you!") ?: "Thank you!"
        set(v) = prefs.edit().putString("receiptFooter", v).apply()

    /** Lines printed at the top of every receipt. */
    fun receiptHeader(): List<String> = buildList {
        add(businessName)
        businessAddress?.takeIf { it.isNotBlank() }?.let { add(it) }
        businessTin?.takeIf { it.isNotBlank() }?.let { add("TIN: $it") }
    }

    /**
     * Catalog price-units schema applied to the local Room catalog.
     * 0 = legacy builds that stored menu basePrice ÷100; 1 = MAJOR as on the server.
     * See SyncRepository.ensureCatalogUnits.
     */
    var catalogUnitsVersion: Int
        get() = prefs.getInt("catalogUnitsVersion", 0)
        set(v) = prefs.edit().putInt("catalogUnitsVersion", v).apply()

    /**
     * Decimal places the server rounds line tax to (the organisation currency's
     * `decimalPlaces`, sent with every pull). 2 until a server has told us; -1 =
     * the server has no currency and rounds to 6 places.
     */
    var taxScale: Int
        get() = prefs.getInt("taxScale", 2)
        set(v) = prefs.edit().putInt("taxScale", v).apply()

    /** Organisation currency code shown next to every amount; UGX until a server says otherwise. */
    var currencyCode: String
        get() = prefs.getString("currencyCode", "UGX") ?: "UGX"
        set(v) = prefs.edit().putString("currencyCode", v).apply()

    /** Decimal places amounts are shown with (the organisation currency's). */
    var currencyDecimals: Int
        get() = prefs.getInt("currencyDecimals", 0)
        set(v) = prefs.edit().putInt("currencyDecimals", v).apply()

    /** Queued legacy menuItem.upsert payloads already rescaled (one-shot). */
    var legacyMenuQueueRescaled: Boolean
        get() = prefs.getBoolean("legacyMenuQueueRescaled", false)
        set(v) = prefs.edit().putBoolean("legacyMenuQueueRescaled", v).apply()

    /** When this APK was installed/updated — ops older than this came from a previous build. */
    val appUpdatedAt: Long
        get() = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime

    val isEnrolled: Boolean get() = !deviceToken.isNullOrBlank() && !serverUrl.isNullOrBlank()

    /** Ready to sell: either enrolled against a server or set up standalone. */
    val isReady: Boolean get() = isEnrolled || standalone

    /** SQLCipher passphrase — generated once, never leaves the device. */
    fun dbPassphrase(): ByteArray {
        val existing = prefs.getString("dbPassphrase", null)
        if (existing != null) return existing.toByteArray(Charsets.UTF_8)
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        // Synchronous: losing this key after the DB is created loses the DB.
        check(prefs.edit().putString("dbPassphrase", encoded).commit()) { "Could not store the database key" }
        return encoded.toByteArray(Charsets.UTF_8)
    }

    /** Next provisional number, e.g. D1-000042. */
    @Synchronized
    fun nextProvisionalNumber(): String {
        val next = prefs.getLong("saleCounter", 0L) + 1
        // Synchronous: an async write lost to a crash would reissue this number.
        check(prefs.edit().putLong("saleCounter", next).commit()) { "Could not advance the sale counter" }
        return "%s-%06d".format(prefix, next)
    }

    /** Last provisional number issued; a restore moves it forward so numbers are never reused. */
    var saleCounter: Long
        get() = prefs.getLong("saleCounter", 0L)
        set(v) { check(prefs.edit().putLong("saleCounter", v).commit()) { "Could not store the sale counter" } }

    // ---- backup (see BackupRepository) ----

    /** Passphrase that encrypts backup files; unlike the DB key it is known to the owner, so a backup restores on a new phone. */
    var backupPassphrase: String?
        get() = prefs.getString("backupPassphrase", null)
        set(v) { prefs.edit().putString("backupPassphrase", v).commit() }

    /** SAF tree URI the daily automatic backup writes into (persisted permission). */
    var backupFolderUri: String?
        get() = prefs.getString("backupFolderUri", null)
        set(v) = prefs.edit().putString("backupFolderUri", v).apply()

    var autoBackup: Boolean
        get() = prefs.getBoolean("autoBackup", false)
        set(v) = prefs.edit().putBoolean("autoBackup", v).apply()

    var lastBackupAt: Long
        get() = prefs.getLong("lastBackupAt", 0L)
        set(v) = prefs.edit().putLong("lastBackupAt", v).apply()

    var lastBackupError: String?
        get() = prefs.getString("lastBackupError", null)
        set(v) = prefs.edit().putString("lastBackupError", v).apply()

    /** Revocation wipe: forget enrollment (DB wipe is handled by the caller). */
    // ---- ADR-S083 protocol 2 (O6) ----

    /** A rotated token from a protocol-2 pull: committed before the pulled rows are applied (the old one has 24 h grace). */
    fun storeRotatedToken(token: String, expiresAt: String?) {
        prefs.edit().putString("deviceToken", token).putString("deviceTokenExpiresAt", expiresAt).commit()
    }

    val deviceTokenExpiresAt: String? get() = prefs.getString("deviceTokenExpiresAt", null)

    var serverId: String?
        get() = prefs.getString("serverId", null)
        set(v) = prefs.edit().putString("serverId", v).apply()

    /** ADR-S083 C4 user offline-session rules, from the last pull (defaults until the first one). */
    data class OfflinePolicy(
        val offlineWindowHours: Int = 72,
        val idleTimeoutMinutes: Int = 30,
        val pinMaxAttempts: Int = 5,
        val pinLockoutMinutes: Int = 5,
    )

    val offlinePolicy: OfflinePolicy
        get() = OfflinePolicy(
            offlineWindowHours = prefs.getInt("policy.offlineWindowHours", 72),
            idleTimeoutMinutes = prefs.getInt("policy.idleTimeoutMinutes", 30),
            pinMaxAttempts = prefs.getInt("policy.pinMaxAttempts", 5),
            pinLockoutMinutes = prefs.getInt("policy.pinLockoutMinutes", 5),
        )

    fun applyOfflinePolicy(windowHours: Int?, idleMinutes: Int?, pinMaxAttempts: Int?, pinLockoutMinutes: Int?) {
        val e = prefs.edit()
        windowHours?.let { e.putInt("policy.offlineWindowHours", it) }
        idleMinutes?.let { e.putInt("policy.idleTimeoutMinutes", it) }
        pinMaxAttempts?.let { e.putInt("policy.pinMaxAttempts", it) }
        pinLockoutMinutes?.let { e.putInt("policy.pinLockoutMinutes", it) }
        e.commit()
    }

    /**
     * Why sync cannot complete right now, as the sync screen shows it: `token_expired` (the server stopped accepting
     * this device's token for pull — queued work is still pushed and held for a supervisor; re-enroll) or
     * `upgrade_required` (426: this app's protocol is too old for the server). Null when sync is healthy.
     */
    private val _syncProblem = MutableStateFlow(prefs.getString("syncProblem", null))
    val syncProblem: StateFlow<String?> = _syncProblem

    fun setSyncProblem(value: String?) {
        if (_syncProblem.value == value) return
        prefs.edit().putString("syncProblem", value).commit()
        _syncProblem.value = value
    }

    /**
     * Highest device sequence ever sent to the server. Kept outside the database so a restored backup (older op
     * queue) cannot hand out a sequence the server already saw under another opId (`SEQ_REUSED`).
     */
    var seqHighWater: Long
        get() = prefs.getLong("seqHighWater", 0L)
        set(v) { prefs.edit().putLong("seqHighWater", v).commit() }

    /** When the last pull completed (epoch ms), read synchronously by the offline-window check (ADR-S083 C4). */
    var lastPullAt: Long?
        get() = prefs.getLong("lastPullAt", 0L).takeIf { it > 0 }
        set(v) { prefs.edit().putLong("lastPullAt", v ?: 0L).commit() }

    /** Set by a backup restore: the next push renumbers unsent ops above [seqHighWater] once. */
    var renumberPending: Boolean
        get() = prefs.getBoolean("renumberPending", false)
        set(v) { prefs.edit().putBoolean("renumberPending", v).commit() }

    fun clearEnrollment() {
        prefs.edit().remove("deviceId").remove("deviceToken").remove("prefix").remove("deviceTokenExpiresAt")
            .remove("syncProblem").commit()
        _syncProblem.value = null
    }

    private companion object {
        const val TAG = "DeviceConfig"
        const val PREFS_NAME = "pos-device-config"
        const val DB_NAME = "pos-cafe.db"
    }
}
