package com.poscafe.pos

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import at.favre.lib.crypto.bcrypt.BCrypt
import com.poscafe.pos.data.DeviceConfig
import com.poscafe.pos.data.local.PosDatabase
import com.poscafe.pos.data.local.entity.StaffEntity
import com.poscafe.pos.data.repo.AuthRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AuthRepositoryTest {

    private lateinit var context: Context
    private lateinit var db: PosDatabase

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, PosDatabase::class.java).allowMainThreadQueries().build()
        val hash = BCrypt.withDefaults().hashToString(4, "1234".toCharArray())
        db.staffDao().upsertAll(listOf(StaffEntity("u1", "Ann", null, "ann@x", hash, "pos:sell", true)))
    }

    @After
    fun tearDown() = db.close()

    /** A fresh repository over the same prefs = the app after a force-stop. */
    private fun freshProcess(): Pair<AuthRepository, DeviceConfig> {
        val config = DeviceConfig(context, context.getSharedPreferences("auth-test", Context.MODE_PRIVATE))
        return AuthRepository(db.staffDao(), db.opQueueDao(), config) to config
    }

    @Test
    fun `failed attempts survive a restart`() = runBlocking {
        val (auth, _) = freshProcess()
        repeat(2) { assertTrue(auth.loginWithPin("u1", "0000").isFailure) }

        val (_, config) = freshProcess()
        assertEquals(2, config.pinFailures("u1"))
    }

    @Test
    fun `backoff blocks even the right PIN until it expires`() = runBlocking {
        val (auth, _) = freshProcess()
        repeat(AuthRepository.BACKOFF_AFTER) { auth.loginWithPin("u1", "0000") }

        val blocked = auth.loginWithPin("u1", "1234")
        assertTrue(blocked.exceptionOrNull()?.message.orEmpty().startsWith("Too many wrong PINs"))
    }

    @Test
    fun `lockout after max attempts persists until cleared by sync`() = runBlocking {
        val (_, config) = freshProcess()
        repeat(AuthRepository.MAX_FAILED_ATTEMPTS) { config.recordPinFailure("u1") }

        val (auth, _) = freshProcess()
        assertTrue(auth.loginWithPin("u1", "1234").exceptionOrNull()?.message.orEmpty().startsWith("Account locked"))

        config.clearPinFailures()
        assertTrue(auth.loginWithPin("u1", "1234").isSuccess)
    }

    @Test
    fun `backoff doubles and is capped`() {
        assertEquals(0L, AuthRepository.backoffMs(AuthRepository.BACKOFF_AFTER - 1))
        assertEquals(15_000L, AuthRepository.backoffMs(AuthRepository.BACKOFF_AFTER))
        assertEquals(30_000L, AuthRepository.backoffMs(AuthRepository.BACKOFF_AFTER + 1))
        assertEquals(15_000L shl 6, AuthRepository.backoffMs(100))
    }
}
