package com.poscafe.pos

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.poscafe.pos.data.DeviceConfig
import com.poscafe.pos.data.local.PosDatabase
import com.poscafe.pos.data.local.entity.OpQueueEntity
import com.poscafe.pos.data.remote.PushRequest
import com.poscafe.pos.data.repo.SyncRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

/**
 * ADR-S083 O6 — web/Android parity, Android half. The shared stream (`contract-tests/sync-protocol/parity/stream.json`)
 * goes through the real op queue and [SyncRepository] push; the batch that would go on the wire is compared with the
 * committed golden `android.json` (written when missing or with `UPDATE_GOLDEN=1`). Backend `SyncClientParityIT`
 * pushes that golden and the web one and asserts identical server state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SyncParityGoldenTest {

    private lateinit var db: PosDatabase
    private lateinit var config: DeviceConfig
    private val api = SyncRepositoryTest.FakeApi()
    private val dir = File("../../contract-tests/sync-protocol/parity")
    private val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PosDatabase::class.java).allowMainThreadQueries().build()
        config = DeviceConfig(context, context.getSharedPreferences("parity-config", Context.MODE_PRIVATE))
        config.serverUrl = "http://192.168.1.10:8080/api/v1/"
        config.deviceId = "{{deviceId}}"
        config.deviceToken = "token"
        config.legacyMenuQueueRescaled = true
        config.catalogUnitsVersion = SyncRepository.CATALOG_UNITS_VERSION
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `the Android wire batch for the parity stream matches its golden`() = runBlocking {
        val stream = Json.parseToJsonElement(File(dir, "stream.json").readText()).jsonObject["ops"]!!.jsonArray
        for (element in stream) {
            val o = element.jsonObject
            db.opQueueDao().enqueueNext { seq ->
                OpQueueEntity(
                    opId = o["opId"]!!.jsonPrimitive.content,
                    deviceSeq = seq,
                    type = o["type"]!!.jsonPrimitive.content,
                    actorUserId = "{{actor}}",
                    occurredAt = Instant.parse(o["occurredAt"]!!.jsonPrimitive.content).toEpochMilli(),
                    payloadJson = (o["payload"] as JsonObject).toString(),
                    status = "queued",
                    attempts = 0,
                    lastError = null,
                )
            }
        }
        SyncRepository(api, db, config).pushAll()
        assertEquals("one batch, every op applied", 1, api.pushed.size)
        val wire = pretty.encodeToString(PushRequest.serializer(), api.pushed.single()) + "\n"

        val golden = File(dir, "android.json")
        if (!golden.exists() || System.getenv("UPDATE_GOLDEN") == "1") golden.writeText(wire)
        assertEquals(Json.parseToJsonElement(golden.readText()), Json.parseToJsonElement(wire))
    }
}
