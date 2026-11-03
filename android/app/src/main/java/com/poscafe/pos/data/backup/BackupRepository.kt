package com.poscafe.pos.data.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.poscafe.pos.data.DeviceConfig
import com.poscafe.pos.data.local.PosDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whole-till backup and restore.
 *
 * The live database is SQLCipher-encrypted with a key that lives in this phone's Keystore and
 * dies with an uninstall, so a copy of that file restores nowhere else. A backup is therefore
 * re-encrypted (`sqlcipher_export`) under a passphrase the owner knows, into one file that also
 * carries the receipt profile and the sale counter. It restores on any phone running this app.
 *
 * Restore replaces every local row. Unsynced sales on the phone being restored over are lost,
 * which is why the screen shows that count and asks first.
 */
@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: PosDatabase,
    private val config: DeviceConfig,
) {
    data class Meta(
        val createdAt: Long,
        val schema: Int,
        val businessName: String,
        val standalone: Boolean,
        val prefix: String,
        val saleCounter: Long,
        val sales: Int,
        val values: Map<String, String>,
    )

    class WrongPassphraseException : Exception("Wrong passphrase, or this is not a POS backup file.")

    private val mutex = Mutex()

    /** Rows the server has not acknowledged yet; a restore over them throws them away. */
    suspend fun unsyncedCount(): Int = withContext(Dispatchers.IO) {
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM op_queue WHERE status IN ('queued','failed')").use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    fun fileName(now: Instant = Instant.now()): String {
        val slug = config.businessName.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "pos" }
        return "poscafe-$slug-${STAMP.format(now.atZone(ZoneId.systemDefault()))}.posbak"
    }

    /** Write a backup to [target] (a SAF document the user picked or the auto-backup created). */
    suspend fun backupTo(target: Uri): Meta = mutex.withLock {
        withContext(Dispatchers.IO) {
            val pass = config.backupPassphrase ?: error("Set a backup passphrase first.")
            val tmp = File(context.cacheDir, "backup-out.db").apply { delete() }
            try {
                val meta = export(tmp, pass)
                context.contentResolver.openOutputStream(target, "wt")?.use { out -> tmp.inputStream().use { it.copyTo(out) } }
                    ?: error("Could not write to the chosen location.")
                config.lastBackupAt = meta.createdAt
                config.lastBackupError = null
                meta
            } catch (e: Exception) {
                config.lastBackupError = e.message ?: e.javaClass.simpleName
                throw e
            } finally {
                tmp.delete()
            }
        }
    }

    /** Automatic backup into the chosen folder, keeping the newest [keep] files there. */
    suspend fun backupToFolder(keep: Int = 7): Meta {
        val tree = config.backupFolderUri?.let(Uri::parse) ?: error("Choose a backup folder first.")
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = try {
            DocumentsContract.createDocument(resolver, parent, MIME, fileName())
        } catch (e: Exception) {
            config.lastBackupError = "Backup folder is no longer available. Choose it again."
            throw IllegalStateException(config.lastBackupError, e)
        } ?: error("Could not create a file in the backup folder.")
        val meta = backupTo(doc)
        prune(tree, keep)
        return meta
    }

    private fun prune(tree: Uri, keep: Int) = runCatching {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val files = mutableListOf<Pair<String, String>>()
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c -> while (c.moveToNext()) files += c.getString(0) to (c.getString(1) ?: "") }
        files.filter { it.second.startsWith("poscafe-") && it.second.endsWith(".posbak") }
            .sortedByDescending { it.second.takeLast(20) }
            .drop(keep)
            .forEach { (id, _) -> DocumentsContract.deleteDocument(context.contentResolver, DocumentsContract.buildDocumentUriUsingTree(tree, id)) }
    }

    /** Keep the folder permission across restarts (call right after the folder picker returns). */
    fun rememberFolder(tree: Uri) {
        context.contentResolver.takePersistableUriPermission(
            tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        config.backupFolderUri = tree.toString()
    }

    /** Copy the live database into [out], encrypted under [pass], with a `backup_meta` table. */
    private fun export(out: File, pass: String): Meta {
        val live = db.openHelper.writableDatabase
        val schema = live.version
        live.execSQL("ATTACH DATABASE ? AS bk KEY ?", arrayOf(out.absolutePath, pass))
        try {
            live.query("SELECT sqlcipher_export('bk')").use { it.moveToFirst() }
            live.execSQL("PRAGMA bk.user_version = $schema")
            val sales = live.query("SELECT COUNT(*) FROM local_sales").use { if (it.moveToFirst()) it.getInt(0) else 0 }
            val meta = Meta(
                createdAt = System.currentTimeMillis(),
                schema = schema,
                businessName = config.businessName,
                standalone = config.standalone,
                prefix = config.prefix,
                saleCounter = config.saleCounter,
                sales = sales,
                values = profile(),
            )
            live.execSQL("CREATE TABLE bk.backup_meta (key TEXT PRIMARY KEY NOT NULL, value TEXT)")
            write(meta).forEach { (k, v) -> live.execSQL("INSERT INTO bk.backup_meta(key, value) VALUES (?, ?)", arrayOf(k, v)) }
            return meta
        } finally {
            live.execSQL("DETACH DATABASE bk")
        }
    }

    private fun profile(): Map<String, String> = buildMap {
        put("businessName", config.businessName)
        config.businessAddress?.let { put("businessAddress", it) }
        config.businessTin?.let { put("businessTin", it) }
        put("receiptFooter", config.receiptFooter)
        config.printerHost?.let { put("printerHost", it) }
        config.serverUrl?.let { put("serverUrl", it) }
    }

    private fun write(m: Meta): Map<String, String> = m.values + mapOf(
        "format" to "1",
        "createdAt" to m.createdAt.toString(),
        "schema" to m.schema.toString(),
        "standalone" to m.standalone.toString(),
        "prefix" to m.prefix,
        "saleCounter" to m.saleCounter.toString(),
        "sales" to m.sales.toString(),
    )

    /** Copy [source] locally and check the passphrase; returns what the backup holds. */
    suspend fun inspect(source: Uri, pass: String): Meta = withContext(Dispatchers.IO) {
        val file = staged()
        context.contentResolver.openInputStream(source)?.use { input -> file.outputStream().use { input.copyTo(it) } }
            ?: error("Could not read the chosen file.")
        val bk = open(file, pass, SQLiteDatabase.OPEN_READONLY)
        try {
            val values = mutableMapOf<String, String>()
            bk.rawQuery("SELECT key, value FROM backup_meta", null).use { c -> while (c.moveToNext()) values[c.getString(0)] = c.getString(1) ?: "" }
            val schema = bk.version
            if (schema > db.openHelper.readableDatabase.version) {
                error("This backup is from a newer version of the app. Update the app, then restore.")
            }
            Meta(
                createdAt = values["createdAt"]?.toLongOrNull() ?: 0L,
                schema = schema,
                businessName = values["businessName"] ?: "",
                standalone = values["standalone"] == "true",
                prefix = values["prefix"] ?: "D?",
                saleCounter = values["saleCounter"]?.toLongOrNull() ?: 0L,
                sales = values["sales"]?.toIntOrNull() ?: 0,
                values = values,
            )
        } finally {
            bk.close()
        }
    }

    /**
     * Replace the live database with the staged backup (from [inspect]) and apply its profile.
     * The open database is closed for good: the caller must restart the app straight after.
     */
    suspend fun restoreStaged(pass: String, meta: Meta) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val file = staged()
            check(file.exists()) { "Pick the backup file again." }
            val dbFile = context.getDatabasePath(DB_NAME)
            val fresh = File(dbFile.parentFile, "$DB_NAME.restore").apply { delete() }
            // CREATE: ATTACH inherits the connection's flags and must create the new file.
            val bk = open(file, pass, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY)
            try {
                bk.execSQL("DROP TABLE IF EXISTS backup_meta")
                bk.execSQL("ATTACH DATABASE ? AS live KEY ?", arrayOf(fresh.absolutePath, String(config.dbPassphrase(), Charsets.UTF_8)))
                bk.rawQuery("SELECT sqlcipher_export('live')", null).use { it.moveToFirst() }
                bk.execSQL("PRAGMA live.user_version = ${meta.schema}")
                bk.execSQL("DETACH DATABASE live")
            } catch (e: Exception) {
                fresh.delete()
                throw IllegalStateException("Restore failed; nothing on this phone was changed. (${e.message})", e)
            } finally {
                bk.close()
            }
            db.close()
            listOf("", "-wal", "-shm", "-journal").forEach { File(dbFile.path + it).delete() }
            check(fresh.renameTo(dbFile)) { "Could not replace the database." }
            file.delete()
            applyProfile(meta)
        }
    }

    private fun applyProfile(meta: Meta) {
        val v = meta.values
        v["businessName"]?.let { config.businessName = it }
        config.businessAddress = v["businessAddress"]
        config.businessTin = v["businessTin"]
        v["receiptFooter"]?.let { config.receiptFooter = it }
        v["printerHost"]?.let { config.printerHost = it }
        // Never reissue a receipt number the restored sales already used.
        if (meta.prefix == config.prefix || meta.standalone) config.saleCounter = maxOf(config.saleCounter, meta.saleCounter)
        if (meta.standalone) {
            config.standalone = true
            config.prefix = meta.prefix
        } else if (!config.isEnrolled) {
            // A connected till's token is not in the backup: point at its server and re-enrol.
            v["serverUrl"]?.let { config.serverUrl = it }
        }
    }

    private fun staged() = File(context.cacheDir, "backup-in.db")

    private fun open(file: File, pass: String, flags: Int): SQLiteDatabase = try {
        System.loadLibrary("sqlcipher")
        SQLiteDatabase.openDatabase(file.absolutePath, pass, null, flags, null, null).also { d ->
            // The key is only checked on first read.
            d.rawQuery("SELECT count(*) FROM sqlite_master", null).use { it.moveToFirst() }
        }
    } catch (e: Exception) {
        throw WrongPassphraseException()
    }

    companion object {
        const val MIME = "application/octet-stream"
        private const val DB_NAME = "pos-cafe.db"
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
    }
}
