package com.poscafe.pos.data.repo

import at.favre.lib.crypto.bcrypt.BCrypt
import com.poscafe.pos.data.DeviceConfig
import com.poscafe.pos.data.local.dao.OpQueueDao
import com.poscafe.pos.data.local.dao.StaffDao
import com.poscafe.pos.data.local.entity.OpQueueEntity
import com.poscafe.pos.data.local.entity.StaffEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Offline PIN login. Verification runs entirely on-device against the synced
 * bcrypt pinHash — no network needed. Lockout mirrors the server's
 * MAX_FAILED_ATTEMPTS=10 so a stolen tablet cannot brute-force PINs through
 * the UI (the DB itself is SQLCipher-encrypted against offline extraction).
 * Failure counts live in [DeviceConfig], so force-stopping the app does not
 * reset them; from [BACKOFF_AFTER] failures each attempt waits out a doubling
 * delay. A PIN reset in the back office (a new hash on the next sync) clears it.
 */
@Singleton
class AuthRepository @Inject constructor(
    private val staffDao: StaffDao,
    private val opQueue: OpQueueDao,
    private val config: DeviceConfig,
) {
    data class LoggedIn(val userId: String, val displayName: String, val permissions: Set<String>)

    @Volatile
    var current: LoggedIn? = null
        private set

    suspend fun staffList(): List<StaffEntity> = staffDao.all().filter { it.isActive && it.pinHash != null }

    suspend fun loginWithPin(userId: String, pin: String): Result<LoggedIn> = runCatching {
        val user = staffDao.byId(userId)
            ?: return@runCatching Result.failure(IllegalArgumentException("Unknown user"))
        // Revocation, checked explicitly rather than relying on the server
        // having nulled the hash. A suspended or terminated employee must not
        // be able to open a shift on a terminal that is still offline.
        if (!user.isActive) {
            return@runCatching Result.failure(IllegalStateException("This account has been deactivated"))
        }
        val hash = user.pinHash
            ?: return@runCatching Result.failure(IllegalStateException("No PIN set for this user"))

        val attempts = config.pinFailures(userId)
        if (attempts >= MAX_FAILED_ATTEMPTS) {
            return@runCatching Result.failure(IllegalStateException("Account locked after $MAX_FAILED_ATTEMPTS failed attempts — ask a manager to reset your PIN in the back office"))
        }
        val waitMs = backoffMs(attempts) - (System.currentTimeMillis() - config.lastPinFailureAt(userId))
        if (waitMs > 0) {
            return@runCatching Result.failure(IllegalStateException("Too many wrong PINs — try again in ${(waitMs + 999) / 1000}s"))
        }

        val ok = try {
            verifyPin(pin, hash)
        } catch (t: Throwable) {
            return@runCatching Result.failure(IllegalStateException("PIN verification error: ${t.message ?: t.javaClass.simpleName}"))
        }
        if (ok) {
            config.clearPinFailures(userId)
            val session = LoggedIn(
                userId = user.id,
                displayName = listOfNotNull(user.firstName, user.lastName).joinToString(" "),
                permissions = user.permissions.split(',').filter { it.isNotBlank() }.toSet(),
            )
            current = session
            Result.success(session)
        } else {
            config.recordPinFailure(userId)
            Result.failure(IllegalArgumentException("Wrong PIN"))
        }
    }.fold({ it }, { failure -> Result.failure(failure) })

    fun logout() {
        current = null
    }

    /**
     * Self-service PIN change. Verifies the current PIN against the synced
     * bcrypt hash, hashes the new PIN locally, updates the local row so the
     * cashier can log in again immediately, and (on enrolled devices) queues a
     * `staff.pinChange` op so the new hash reaches the server — the next pull
     * then fans it out to every device. The server never sees the plaintext.
     */
    suspend fun changePin(userId: String, currentPin: String, newPin: String): Result<Unit> = runCatching {
        val user = staffDao.byId(userId)
            ?: return@runCatching Result.failure(IllegalArgumentException("Unknown user"))
        val hash = user.pinHash
            ?: return@runCatching Result.failure(IllegalStateException("No PIN set for this user"))
        val ok = try {
            verifyPin(currentPin, hash)
        } catch (t: Throwable) {
            return@runCatching Result.failure(IllegalStateException("PIN verification error: ${t.message ?: t.javaClass.simpleName}"))
        }
        if (!ok) return@runCatching Result.failure(IllegalArgumentException("Current PIN is incorrect"))
        if (currentPin == newPin) return@runCatching Result.failure(IllegalArgumentException("New PIN must be different"))

        val newHash = withContext(Dispatchers.Default) { BCrypt.withDefaults().hashToString(10, newPin.toCharArray()) }
        staffDao.upsertAll(listOf(user.copy(pinHash = newHash)))

        if (!config.standalone) {
            val payload = buildJsonObject {
                put("userId", userId)
                put("newPinHash", newHash)
            }
            opQueue.enqueueNext { seq ->
                OpQueueEntity(
                    opId = UUID.randomUUID().toString(),
                    deviceSeq = seq,
                    type = "staff.pinChange",
                    actorUserId = userId,
                    occurredAt = System.currentTimeMillis(),
                    payloadJson = payload.toString(),
                    status = "queued",
                    attempts = 0,
                    lastError = null,
                )
            }
        }
        Result.success(Unit)
    }.fold({ it }, { failure -> Result.failure(failure) })

    data class Override(val userId: String, val displayName: String)

    /**
     * Verify a manager override PIN for a refund/void WITHOUT touching the
     * logged-in cashier. Returns the manager when the PIN matches an active
     * staff member holding `pos:override` — the same permission the server's
     * assertCanOverride requires. Runs on-device against the synced bcrypt
     * hashes; the server re-validates on replay (its authority is final).
     */
    suspend fun verifyOverridePin(pin: String): Result<Override> = verifyPinFor(pin, "pos:override")

    /** Does the signed-in cashier hold [permission] (as last synced)? */
    fun currentHas(permission: String): Boolean = current?.permissions?.contains(permission) == true

    /**
     * Verify an approver's PIN for an action gated by [permission] — shift-close
     * variances (`cash_session:approve_variance`), stock movements
     * (`inventory_doc:approve`). Excludes the signed-in cashier when
     * [excludeCurrent]: the server forbids approving your own work.
     */
    suspend fun verifyPinFor(pin: String, permission: String, excludeCurrent: Boolean = false): Result<Override> = runCatching {
        val managers = staffDao.all().filter { staff ->
            staff.isActive && staff.pinHash != null &&
                !(excludeCurrent && staff.id == current?.userId) &&
                permission in staff.permissions.split(',').map { it.trim() }
        }
        for (m in managers) {
            val ok = try {
                verifyPin(pin, m.pinHash!!)
            } catch (t: Throwable) {
                continue
            }
            if (ok) return@runCatching Result.success(Override(m.id, listOfNotNull(m.firstName, m.lastName).joinToString(" ")))
        }
        Result.failure(IllegalArgumentException("PIN not recognised for a manager holding $permission"))
    }.fold({ it }, { failure -> Result.failure(failure) })

    /** bcrypt is deliberately slow (~100 ms+ per hash); keep it off the main thread. */
    private suspend fun verifyPin(pin: String, hash: String): Boolean = withContext(Dispatchers.Default) {
        BCrypt.verifyer().verify(pin.toCharArray(), hash.toCharArray()).verified
    }

    companion object {
        const val MAX_FAILED_ATTEMPTS = 10
        const val BACKOFF_AFTER = 3
        private const val BACKOFF_BASE_MS = 15_000L

        /** 0 below [BACKOFF_AFTER] failures, then 15 s, 30 s, 60 s, … */
        fun backoffMs(failures: Int): Long =
            if (failures < BACKOFF_AFTER) 0L else BACKOFF_BASE_MS shl (failures - BACKOFF_AFTER).coerceAtMost(6)
    }
}
