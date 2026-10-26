package com.poscafe.pos

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.poscafe.pos.data.local.PosDatabase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Upgrades a v2 database through every migration to the current version and
 * checks the result matches the exported schema. With the destructive fallback
 * gone, a missing or wrong migration fails here instead of on a till.
 * (No 1.json was exported, so v1 cannot be created.)
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), PosDatabase::class.java)

    @Test
    fun migrateV2ToLatest() {
        helper.createDatabase(DB, 2).apply {
            execSQL(
                "INSERT INTO op_queue (opId, deviceSeq, type, actorUserId, occurredAt, payloadJson, status, attempts, lastError) " +
                    "VALUES ('op-1', 1, 'sale.checkout', 'u1', 0, '{}', 'queued', 0, NULL)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(
            DB, LATEST, true,
            PosDatabase.MIGRATION_2_3, PosDatabase.MIGRATION_3_4, PosDatabase.MIGRATION_4_5,
            PosDatabase.MIGRATION_5_6, PosDatabase.MIGRATION_6_7, PosDatabase.MIGRATION_7_8,
            PosDatabase.MIGRATION_8_9, PosDatabase.MIGRATION_9_10, PosDatabase.MIGRATION_10_11,
            PosDatabase.MIGRATION_11_12, PosDatabase.MIGRATION_12_13,
        )
        // Unsynced sales survive the upgrade.
        db.query("SELECT COUNT(*) FROM op_queue WHERE status = 'queued'").use { c ->
            c.moveToFirst()
            check(c.getInt(0) == 1) { "queued op lost in migration" }
        }
    }

    private companion object {
        const val DB = "migration-test.db"
        const val LATEST = 13
    }
}
