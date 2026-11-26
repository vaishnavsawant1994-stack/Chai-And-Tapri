package com.chaiandtapri.counter

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase as PlatformSQLiteDatabase
import android.database.DatabaseUtils
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper
import java.util.UUID

fun interface CounterPersistenceFaultInjector { fun afterAuditInsert() }

/** SQLCipher-backed local event/snapshot store. The v5 projection, ledger and outbox commit atomically. */
class SqliteCounterPersistence internal constructor(
    context: Context,
    passphrase: ByteArray,
    private val storeDatabaseName: String,
    private val appContext: Context,
    private val initializeExisting: Boolean,
    private val faultInjector: CounterPersistenceFaultInjector,
    private val identityNamespace: String = "default"
) : SQLiteOpenHelper(context.applicationContext, storeDatabaseName, rawKeyPassphrase(passphrase), null, DATABASE_VERSION, 0, null, null, false), CounterPersistence {
    private fun requireOwnerMutation(action: String) {
        if (!LocalSessionState.isOwner) throw SecurityException("Owner access is required to $action.")
    }

    private fun requireManagerMutation(action: String) {
        if (!LocalSessionState.isManager) throw SecurityException("Manager access is required to $action.")
    }

    constructor(context: Context, faultInjector: CounterPersistenceFaultInjector = CounterPersistenceFaultInjector { }) : this(
        context.applicationContext,
        provisionPassphrase(context.applicationContext),
        DATABASE_NAME,
        context.applicationContext,
        true,
        faultInjector
    )

    /** Isolated named store constructor used by recovery qualification and test sandboxes. */
    internal constructor(context: Context, databaseName: String, keyNamespace: String) : this(
        context.applicationContext,
        SecureDatabaseKeyManager(context.applicationContext, context.applicationContext.getDatabasePath(databaseName), keyNamespace).getOrCreatePassphrase(),
        databaseName,
        context.applicationContext,
        false,
        CounterPersistenceFaultInjector { },
        keyNamespace
    )

    init {
        System.loadLibrary("sqlcipher")
        passphrase.fill(0)
        if (initializeExisting) {
            if (BusinessWriteBarrier.currentMaintenance() != null) throw BusinessMaintenanceException()
            RestoreRecoveryJournal.recover(appContext, storeDatabaseName)
            migrateLegacyIfNeeded()
        }
    }

    override fun close() = super<SQLiteOpenHelper>.close()
    override fun onCreate(db: CipherDatabase) {
        db.execSQL("CREATE TABLE orders(order_no INTEGER PRIMARY KEY, fulfillment TEXT NOT NULL, payment TEXT NOT NULL, paid_paise INTEGER NOT NULL, credit_paise INTEGER NOT NULL DEFAULT 0, refunded_paise INTEGER NOT NULL DEFAULT 0, cash_refunded_paise INTEGER NOT NULL DEFAULT 0, credit_reversed_paise INTEGER NOT NULL DEFAULT 0, sale_posted INTEGER NOT NULL, voided INTEGER NOT NULL DEFAULT 0, cancel_reason TEXT, revision INTEGER NOT NULL, created_at INTEGER NOT NULL DEFAULT 0)")
        db.execSQL("CREATE TABLE order_lines(order_no INTEGER NOT NULL, product_id TEXT NOT NULL, name TEXT NOT NULL, quantity INTEGER NOT NULL, delivered_quantity INTEGER NOT NULL DEFAULT 0, refunded_quantity INTEGER NOT NULL DEFAULT 0, unit_price_paise INTEGER NOT NULL, estimated_unit_cost_paise INTEGER CHECK(estimated_unit_cost_paise IS NULL OR estimated_unit_cost_paise BETWEEN 0 AND 10000000), PRIMARY KEY(order_no, product_id), FOREIGN KEY(order_no) REFERENCES orders(order_no))")
        db.execSQL("CREATE TABLE stock(product_id TEXT PRIMARY KEY, quantity INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE stock_baseline_confirmations(product_id TEXT PRIMARY KEY, confirmed_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.insertOrThrow("metadata", null, ContentValues().apply { put("key", STORE_IDENTITY_KEY); put("value", UUID.randomUUID().toString()) })
        db.insertOrThrow("metadata", null, ContentValues().apply { put("key", "next_order_number"); put("value", "12") })
        listOf("cutting_chai", "chai", "samosa", "coffee").forEach { productId ->
            db.insertOrThrow("stock", null, ContentValues().apply { put("product_id", productId); put("quantity", 0) })
        }
        db.execSQL("CREATE TABLE command_receipts(command_id TEXT PRIMARY KEY, result TEXT NOT NULL, committed_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE audit_events(event_id TEXT PRIMARY KEY, command_id TEXT NOT NULL, event TEXT NOT NULL, created_at INTEGER NOT NULL, actor_id TEXT)")
        db.execSQL("CREATE TABLE stock_movements(movement_id TEXT PRIMARY KEY, command_id TEXT NOT NULL, product_id TEXT NOT NULL, quantity_delta INTEGER NOT NULL, reason TEXT NOT NULL, order_no INTEGER, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE customer_credit(credit_id TEXT PRIMARY KEY, customer_id TEXT NOT NULL, order_no INTEGER NOT NULL, amount_paise INTEGER NOT NULL, repaid_paise INTEGER NOT NULL, reversed_paise INTEGER NOT NULL DEFAULT 0, sync_id TEXT NOT NULL)")
        db.execSQL("CREATE TABLE credit_repayments(repayment_id TEXT PRIMARY KEY, customer_id TEXT NOT NULL, amount_paise INTEGER NOT NULL, sync_id TEXT NOT NULL)")
        db.execSQL("CREATE TABLE customer_sync_identities(local_customer_id TEXT PRIMARY KEY, sync_id TEXT NOT NULL UNIQUE)")
        createPaymentEntries(db)
        createSyncOutboxTable(db)
        createProductCatalog(db)
        createProcurementTables(db)
        createPurchaseOrderDraftTables(db)
        createPurchaseReturnTables(db)
        createExpenseTable(db)
        db.execSQL("ALTER TABLE payment_entries ADD COLUMN drawer_session_id TEXT")
        db.execSQL("ALTER TABLE supplier_payments ADD COLUMN drawer_session_id TEXT")
        db.execSQL("ALTER TABLE expenses ADD COLUMN drawer_session_id TEXT")
        createCashDrawerTables(db)
        addStaffShiftColumns(db)
        createCashClosingReviewTable(db)
        seedProductCatalog(db)
        createLocalOwnerTable(db)
        createStaffCredentialsTable(db)
        createCustomerProfilesTable(db)
        migrateOpaqueCustomerSyncIds(db)
        migrateOpaqueProcurementSyncIds(db)
    }

    override fun onUpgrade(db: CipherDatabase, oldVersion: Int, newVersion: Int) {
        // Never drop shop records. Each migration is additive and runs inside SQLiteOpenHelper's transaction.
        if (oldVersion < 2) createPaymentEntries(db)
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE orders ADD COLUMN credit_paise INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE orders ADD COLUMN refunded_paise INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE orders ADD COLUMN voided INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE orders ADD COLUMN cash_refunded_paise INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE orders ADD COLUMN credit_reversed_paise INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE order_lines ADD COLUMN refunded_quantity INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE customer_credit ADD COLUMN reversed_paise INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion in 2..4) db.execSQL("ALTER TABLE payment_entries ADD COLUMN related_entry_id TEXT")
        if (oldVersion < 6) {
            db.insertWithOnConflict("metadata", null, ContentValues().apply { put("key", STORE_IDENTITY_KEY); put("value", UUID.randomUUID().toString()) }, CipherDatabase.CONFLICT_IGNORE)
            db.insertWithOnConflict("metadata", null, ContentValues().apply { put("key", "next_order_number"); put("value", "12") }, CipherDatabase.CONFLICT_IGNORE)
            listOf("cutting_chai", "chai", "samosa", "coffee").forEach { productId ->
                db.insertWithOnConflict("stock", null, ContentValues().apply { put("product_id", productId); put("quantity", 100) }, CipherDatabase.CONFLICT_IGNORE)
            }
        }
        if (oldVersion < 7) {
            createProductCatalog(db)
            seedProductCatalog(db)
        }
        if (oldVersion < 8) db.execSQL("CREATE TABLE stock_baseline_confirmations(product_id TEXT PRIMARY KEY, confirmed_at INTEGER NOT NULL)")
        if (oldVersion < 9) createProcurementTables(db)
        if (oldVersion < 10) createPurchaseReturnTables(db)
        if (oldVersion < 11) createExpenseTable(db)
        if (oldVersion < 12) {
            db.execSQL("ALTER TABLE payment_entries ADD COLUMN drawer_session_id TEXT")
            db.execSQL("ALTER TABLE supplier_payments ADD COLUMN drawer_session_id TEXT")
            db.execSQL("ALTER TABLE expenses ADD COLUMN drawer_session_id TEXT")
            createCashDrawerTables(db)
        }
        if (oldVersion < 13) createCashClosingReviewTable(db)
        if (oldVersion < 14) {
            addStaffShiftColumns(db)
        }
        if (oldVersion < 15) createLocalOwnerTable(db)
        if (oldVersion < 16) db.execSQL("ALTER TABLE orders ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0")
        if (oldVersion < 17) createCustomerProfilesTable(db)
        if (oldVersion < 18) migrateSyncOutbox(db)
        if (oldVersion < 19 && !hasColumn(db, "product_catalog", "reorder_threshold")) {
            db.execSQL("ALTER TABLE product_catalog ADD COLUMN reorder_threshold INTEGER CHECK(reorder_threshold IS NULL OR reorder_threshold BETWEEN 1 AND 100000)")
        }
        if (oldVersion < 20 && !hasColumn(db, "sync_outbox", "server_revision")) {
            db.execSQL("ALTER TABLE sync_outbox ADD COLUMN server_revision INTEGER CHECK(server_revision IS NULL OR server_revision >= 0)")
        }
        if (oldVersion < 21) {
            if (oldVersion >= 9 && !hasColumn(db, "purchase_receipts", "purchase_order_id")) {
                db.execSQL("ALTER TABLE purchase_receipts ADD COLUMN purchase_order_id TEXT")
            }
            createPurchaseOrderDraftTables(db)
        }
        if (oldVersion < 22 && !hasColumn(db, "purchase_receipts", "supplier_invoice_reference")) {
            db.execSQL("ALTER TABLE purchase_receipts ADD COLUMN supplier_invoice_reference TEXT")
        }
        if (oldVersion < 23 && !hasColumn(db, "suppliers", "phone")) {
            db.execSQL("ALTER TABLE suppliers ADD COLUMN phone TEXT")
        }
        if (oldVersion < 24) createStaffCredentialsTable(db)
        if (oldVersion < 25 && !hasColumn(db, "audit_events", "actor_id")) db.execSQL("ALTER TABLE audit_events ADD COLUMN actor_id TEXT")
        if (oldVersion < 26) {
            if (!hasColumn(db, "product_catalog", "estimated_unit_cost_paise")) db.execSQL("ALTER TABLE product_catalog ADD COLUMN estimated_unit_cost_paise INTEGER CHECK(estimated_unit_cost_paise IS NULL OR estimated_unit_cost_paise BETWEEN 0 AND 10000000)")
            if (!hasColumn(db, "order_lines", "estimated_unit_cost_paise")) db.execSQL("ALTER TABLE order_lines ADD COLUMN estimated_unit_cost_paise INTEGER CHECK(estimated_unit_cost_paise IS NULL OR estimated_unit_cost_paise BETWEEN 0 AND 10000000)")
        }
        if (oldVersion < 27) {
            if (!hasColumn(db, "product_catalog", "scan_code")) db.execSQL("ALTER TABLE product_catalog ADD COLUMN scan_code TEXT")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS product_catalog_scan_code_unique ON product_catalog(scan_code COLLATE NOCASE) WHERE scan_code IS NOT NULL AND scan_code <> ''")
        }
        if (oldVersion < 28) migrateOpaqueProcurementSyncIds(db)
        if (oldVersion < 29) migrateOpaqueCustomerSyncIds(db)
        if (oldVersion < 30 && !hasColumn(db, "cash_drawer_sessions", "staff_actor_id")) db.execSQL("ALTER TABLE cash_drawer_sessions ADD COLUMN staff_actor_id TEXT")
        if (oldVersion < 31 && !hasColumn(db, "product_catalog", "unit_label")) db.execSQL("ALTER TABLE product_catalog ADD COLUMN unit_label TEXT NOT NULL DEFAULT 'units'")
        if (oldVersion < 32 && !hasColumn(db, "cash_drawer_sessions", "handover_to_staff_id")) db.execSQL("ALTER TABLE cash_drawer_sessions ADD COLUMN handover_to_staff_id TEXT")
        if (oldVersion < 33 && !hasColumn(db, "cash_drawer_sessions", "handover_received_at")) db.execSQL("ALTER TABLE cash_drawer_sessions ADD COLUMN handover_received_at INTEGER")
        if (oldVersion < 33 && !hasColumn(db, "cash_drawer_sessions", "handover_received_cash_paise")) db.execSQL("ALTER TABLE cash_drawer_sessions ADD COLUMN handover_received_cash_paise INTEGER CHECK(handover_received_cash_paise IS NULL OR handover_received_cash_paise BETWEEN 0 AND 100000000)")
        if (oldVersion < 34) {
            if (!hasColumn(db, "product_catalog", "purchase_unit_label")) db.execSQL("ALTER TABLE product_catalog ADD COLUMN purchase_unit_label TEXT NOT NULL DEFAULT 'units'")
            if (!hasColumn(db, "product_catalog", "base_units_per_purchase_unit")) db.execSQL("ALTER TABLE product_catalog ADD COLUMN base_units_per_purchase_unit INTEGER NOT NULL DEFAULT 1 CHECK(base_units_per_purchase_unit BETWEEN 1 AND 100000)")
            db.execSQL("UPDATE product_catalog SET purchase_unit_label=unit_label WHERE purchase_unit_label='units' AND unit_label<>'units'")
        }
        if (newVersion > DATABASE_VERSION || oldVersion > 33) {
            throw IllegalStateException("No migration is defined from database version $oldVersion to $newVersion")
        }
    }

    private fun migrateOpaqueProcurementSyncIds(db: CipherDatabase) {
        listOf("suppliers" to "supplier_id", "purchase_receipts" to "receipt_id", "supplier_payments" to "payment_id", "purchase_returns" to "return_id").forEach { (table, idColumn) ->
            if (!hasColumn(db, table, "sync_id")) db.execSQL("ALTER TABLE $table ADD COLUMN sync_id TEXT NOT NULL DEFAULT ''")
            val ids = mutableListOf<String>()
            db.rawQuery("SELECT $idColumn FROM $table WHERE sync_id='' OR sync_id IS NULL", null).use { c ->
                while (c.moveToNext()) ids += c.getString(0)
            }
            ids.forEach { localId ->
                val changed = db.update(table, ContentValues().apply { put("sync_id", UUID.randomUUID().toString()) }, "$idColumn=? AND (sync_id='' OR sync_id IS NULL)", arrayOf(localId))
                check(changed == 1) { "Could not migrate opaque procurement sync identity." }
            }
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS ${table}_sync_id_unique ON $table(sync_id)")
        }
    }

    private fun migrateOpaqueCustomerSyncIds(db: CipherDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS customer_sync_identities(local_customer_id TEXT PRIMARY KEY, sync_id TEXT NOT NULL UNIQUE)")
        if (!hasColumn(db, "customer_credit", "sync_id")) db.execSQL("ALTER TABLE customer_credit ADD COLUMN sync_id TEXT NOT NULL DEFAULT ''")
        if (!hasColumn(db, "credit_repayments", "sync_id")) db.execSQL("ALTER TABLE credit_repayments ADD COLUMN sync_id TEXT NOT NULL DEFAULT ''")
        val localIds = linkedSetOf<String>()
        listOf("customer_profiles" to "customer_id", "customer_credit" to "customer_id", "credit_repayments" to "customer_id", "payment_entries" to "customer_id").forEach { (table, column) ->
            if (tableExists(db, table) && hasColumn(db, table, column)) db.rawQuery("SELECT DISTINCT $column FROM $table WHERE $column IS NOT NULL AND $column<>''", null).use { c -> while (c.moveToNext()) localIds += c.getString(0) }
        }
        localIds.forEach { localId ->
            db.insertWithOnConflict("customer_sync_identities", null, ContentValues().apply { put("local_customer_id", localId); put("sync_id", UUID.randomUUID().toString()) }, CipherDatabase.CONFLICT_IGNORE)
        }
        listOf("customer_credit" to "credit_id", "credit_repayments" to "repayment_id").forEach { (table, idColumn) ->
            val ids = mutableListOf<String>()
            db.rawQuery("SELECT $idColumn FROM $table WHERE sync_id='' OR sync_id IS NULL", null).use { c -> while (c.moveToNext()) ids += c.getString(0) }
            ids.forEach { id -> db.update(table, ContentValues().apply { put("sync_id", UUID.randomUUID().toString()) }, "$idColumn=? AND (sync_id='' OR sync_id IS NULL)", arrayOf(id)) }
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS ${table}_sync_id_unique ON $table(sync_id)")
        }
        if (!hasColumn(db, "payment_entries", "sync_id")) db.execSQL("ALTER TABLE payment_entries ADD COLUMN sync_id TEXT NOT NULL DEFAULT ''")
        val paymentIds = mutableListOf<String>()
        db.rawQuery("SELECT entry_id FROM payment_entries WHERE sync_id='' OR sync_id IS NULL", null).use { c -> while (c.moveToNext()) paymentIds += c.getString(0) }
        paymentIds.forEach { id -> db.update("payment_entries", ContentValues().apply { put("sync_id", UUID.randomUUID().toString()) }, "entry_id=? AND (sync_id='' OR sync_id IS NULL)", arrayOf(id)) }
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS payment_entries_sync_id_unique ON payment_entries(sync_id)")
    }

    private fun tableExists(db: CipherDatabase, table: String): Boolean = db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { it.moveToFirst() }

    private fun migrateLegacyIfNeeded() {
        recoverInterruptedMigration()
        val active = appContext.getDatabasePath(DATABASE_NAME)
        if (!active.exists() || !SecureDatabaseKeyManager.isLegacyPlaintextDatabase(active)) return

        val tempName = "$DATABASE_NAME.encrypted-migration"
        val temp = appContext.getDatabasePath(tempName)
        val backup = appContext.getDatabasePath("$DATABASE_NAME.plaintext-backup")
        deleteDatabaseFamily(temp)
        snapshotDatabaseFamily(active, backup)
        var sourceForCleanup: PlatformSQLiteDatabase? = null
        var target: SqliteCounterPersistence? = null
        try {
            val source = PlatformSQLiteDatabase.openDatabase(active.path, null, PlatformSQLiteDatabase.OPEN_READWRITE)
            sourceForCleanup = source
            check(DatabaseUtils.stringForQuery(source, "PRAGMA integrity_check", null) == "ok") { "Legacy database failed integrity_check. The original was preserved." }
            source.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { }
            val passphrase = SecureDatabaseKeyManager(appContext, active).getOrCreatePassphrase()
            target = SqliteCounterPersistence(appContext, passphrase.copyOf(), tempName, appContext, false, CounterPersistenceFaultInjector { })
            passphrase.fill(0)
            val encrypted = target.writableDatabase
            encrypted.beginTransaction()
            try {
                val sourceTables = mutableListOf<String>()
                source.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name", null).use { c -> while (c.moveToNext()) sourceTables += c.getString(0) }
                val targetTables = mutableSetOf<String>()
                encrypted.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'", null).use { c -> while (c.moveToNext()) targetTables += c.getString(0) }
                // New-store defaults (demo stock, order counter, generated shop ID) must not
                // leak into a migrated business or collide with its historical records.
                encrypted.delete("stock", null, null)
                encrypted.delete("metadata", null, null)
                for (table in sourceTables) {
                    if (table == "android_metadata") continue
                    check(table in targetTables) { "Unknown table '$table'; migration stopped without replacing the original database." }
                    val quoted = "\"${table.replace("\"", "\"\"")}\""
                    val targetColumns = mutableSetOf<String>()
                    encrypted.rawQuery("PRAGMA table_info($quoted)", null).use { c -> while (c.moveToNext()) targetColumns += c.getString(1) }
                    var copiedRows = 0L
                    source.rawQuery("SELECT * FROM $quoted", null).use { cursor ->
                        while (cursor.moveToNext()) {
                            val row = ContentValues()
                            cursor.columnNames.forEachIndexed { index, column ->
                                if (column !in targetColumns) return@forEachIndexed
                                when (cursor.getType(index)) {
                                    android.database.Cursor.FIELD_TYPE_NULL -> row.putNull(column)
                                    android.database.Cursor.FIELD_TYPE_INTEGER -> row.put(column, cursor.getLong(index))
                                    android.database.Cursor.FIELD_TYPE_FLOAT -> row.put(column, cursor.getDouble(index))
                                    android.database.Cursor.FIELD_TYPE_STRING -> row.put(column, cursor.getString(index))
                                    android.database.Cursor.FIELD_TYPE_BLOB -> row.put(column, cursor.getBlob(index))
                                }
                            }
                            if ("sync_id" in targetColumns && "sync_id" !in cursor.columnNames) row.put("sync_id", UUID.randomUUID().toString())
                            encrypted.insertOrThrow(table, null, row)
                            copiedRows++
                        }
                    }
                    val oldCount = DatabaseUtils.longForQuery(source, "SELECT COUNT(*) FROM $quoted", null)
                    val newCount = cipherLong(encrypted, "SELECT COUNT(*) FROM $quoted")
                    check(copiedRows == oldCount && newCount == oldCount) { "Row-count verification failed for '$table'." }
                }
                migrateSyncOutbox(encrypted)
                val identityExists = encrypted.rawQuery("SELECT 1 FROM metadata WHERE key=?", arrayOf(STORE_IDENTITY_KEY)).use { it.moveToFirst() }
                if (!identityExists) encrypted.insertOrThrow("metadata", null, ContentValues().apply {
                    put("key", STORE_IDENTITY_KEY)
                    put("value", UUID.randomUUID().toString())
                })
                encrypted.rawQuery("SELECT value FROM metadata WHERE key='next_order_number'", null).use {
                    check(it.moveToFirst()) { "Legacy database is missing its next order number; the original was preserved." }
                }
                check(cipherString(encrypted, "PRAGMA integrity_check") == "ok") { "Encrypted migration copy failed integrity_check." }
                check(encrypted.rawQuery("PRAGMA cipher_version", null).use { it.moveToFirst() && it.getString(0).isNotBlank() }) { "Encrypted migration target is not SQLCipher." }
                encrypted.setTransactionSuccessful()
            } finally { encrypted.endTransaction() }
            target.close(); target = null
            source.close()

            deleteDatabaseFamily(active)
            if (!temp.renameTo(active)) {
                restoreDatabaseFamily(backup, active)
                throw IllegalStateException("Could not activate encrypted database; original was restored.")
            }
            try {
                val verified = writableDatabase
                verifyCipherDatabase(verified)
                close()
                deleteDatabaseFamily(backup)
            } catch (error: Throwable) {
                close()
                val failed = appContext.getDatabasePath("$DATABASE_NAME.encrypted-failed")
                if (active.exists()) restoreDatabaseFamily(active, failed)
                if (backup.exists()) restoreDatabaseFamily(backup, active)
                throw IllegalStateException("Encrypted cutover failed; the original database was restored and preserved.", error)
            }
        } catch (error: Throwable) {
            try { target?.close() } catch (_: Exception) { }
            try { sourceForCleanup?.close() } catch (_: Exception) { }
            deleteDatabaseFamily(temp)
            if (backup.exists()) {
                deleteDatabaseFamily(active)
                restoreDatabaseFamily(backup, active)
            }
            throw error
        }
    }

    private fun snapshotDatabaseFamily(source: java.io.File, destination: java.io.File) {
        deleteDatabaseFamily(destination)
        check(source.exists()) { "Legacy source database disappeared before it could be preserved." }
        try {
            listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
                val from = java.io.File(source.path + suffix)
                if (from.exists()) java.nio.file.Files.copy(from.toPath(), java.io.File(destination.path + suffix).toPath())
            }
            check(destination.exists() && destination.length() == source.length()) { "Could not verify the preserved source database copy." }
        } catch (error: Throwable) {
            deleteDatabaseFamily(destination)
            throw IllegalStateException("Could not preserve the source database before migration; migration was not started.", error)
        }
    }

    private fun restoreDatabaseFamily(from: java.io.File, to: java.io.File) {
        listOf("", "-wal", "-shm", "-journal").forEach { suffix ->
            val source = java.io.File(from.path + suffix)
            val destination = java.io.File(to.path + suffix)
            if (source.exists()) check(source.renameTo(destination)) { "Could not restore database file '$suffix'; source data remains at ${source.name}." }
        }
    }

    private fun recoverInterruptedMigration() {
        val active = appContext.getDatabasePath(DATABASE_NAME)
        val backup = appContext.getDatabasePath("$DATABASE_NAME.plaintext-backup")
        val temp = appContext.getDatabasePath("$DATABASE_NAME.encrypted-migration")
        if (!backup.exists()) {
            if (active.exists() && SecureDatabaseKeyManager.isLegacyPlaintextDatabase(active) && temp.exists()) deleteDatabaseFamily(temp)
            return
        }
        if (!active.exists() && !temp.exists()) {
            restoreDatabaseFamily(backup, active)
            return
        }
        if (!active.exists() && temp.exists()) {
            val key = SecureDatabaseKeyManager(appContext, backup).getOrCreatePassphrase()
            val candidate = SqliteCounterPersistence(appContext, key, temp.name, appContext, false, CounterPersistenceFaultInjector { })
            val valid = try { verifyCipherDatabase(candidate.writableDatabase); true } catch (_: Exception) { false } finally { candidate.close(); key.fill(0) }
            if (valid && temp.renameTo(active)) {
                verifyCipherDatabase(writableDatabase)
                close()
                deleteDatabaseFamily(backup)
                return
            }
            check(backup.renameTo(active)) { "Encrypted migration is invalid and original could not be restored; files were preserved." }
            throw IllegalStateException("Interrupted encrypted migration was rolled back to its original database for retry.")
        }
        if (active.exists() && !SecureDatabaseKeyManager.isLegacyPlaintextDatabase(active)) {
            verifyCipherDatabase(writableDatabase)
            close()
            deleteDatabaseFamily(backup)
            deleteDatabaseFamily(temp)
            return
        }
        throw IllegalStateException("Interrupted migration has conflicting plaintext files. No database was reset or deleted.")
    }

    private fun verifyCipherDatabase(db: CipherDatabase) {
        check(db.rawQuery("PRAGMA cipher_version", null).use { it.moveToFirst() && it.getString(0).isNotBlank() }) { "SQLCipher is not active; refusing plaintext database access." }
        check(cipherString(db, "PRAGMA integrity_check") == "ok") { "Database integrity check failed. Writes are blocked and the file is preserved." }
    }

    private fun assertCipherActive(db: CipherDatabase) {
        check(db.rawQuery("PRAGMA cipher_version", null).use { it.moveToFirst() && it.getString(0).isNotBlank() }) { "SQLCipher is not active; refusing plaintext database access." }
    }

    private fun cipherString(db: CipherDatabase, sql: String): String = db.rawQuery(sql, null).use { if (it.moveToFirst()) it.getString(0) else "" }
    private fun cipherLong(db: CipherDatabase, sql: String): Long = db.rawQuery(sql, null).use { if (it.moveToFirst()) it.getLong(0) else 0L }

    private fun deleteDatabaseFamily(file: java.io.File) {
        listOf("", "-wal", "-shm", "-journal").forEach { suffix -> java.io.File(file.path + suffix).delete() }
    }

    private fun createPaymentEntries(db: CipherDatabase) {
        db.execSQL("CREATE TABLE payment_entries(entry_id TEXT PRIMARY KEY, order_no INTEGER, customer_id TEXT, amount_paise INTEGER NOT NULL, method TEXT NOT NULL, status TEXT NOT NULL, kind TEXT NOT NULL, related_entry_id TEXT, created_at INTEGER NOT NULL, sync_id TEXT NOT NULL)")
    }

    private fun createSyncOutboxTable(db: CipherDatabase) {
        db.execSQL("CREATE TABLE sync_outbox(event_id TEXT PRIMARY KEY, command_id TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'blocked_contract', created_at INTEGER NOT NULL, payload_version INTEGER, payload_json TEXT, blocked_reason TEXT, base_revision INTEGER, server_revision INTEGER CHECK(server_revision IS NULL OR server_revision >= 0))")
    }

    /** Old outbox rows contain no safe replay payload; preserve them as explicitly blocked history. */
    private fun migrateSyncOutbox(db: CipherDatabase) {
        val columns = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info(sync_outbox)", null).use { c -> while (c.moveToNext()) columns += c.getString(1) }
        if ("payload_version" !in columns) db.execSQL("ALTER TABLE sync_outbox ADD COLUMN payload_version INTEGER")
        if ("payload_json" !in columns) db.execSQL("ALTER TABLE sync_outbox ADD COLUMN payload_json TEXT")
        if ("blocked_reason" !in columns) db.execSQL("ALTER TABLE sync_outbox ADD COLUMN blocked_reason TEXT")
        if ("base_revision" !in columns) db.execSQL("ALTER TABLE sync_outbox ADD COLUMN base_revision INTEGER")
        val keys = mutableListOf<Pair<String, String>>()
        db.rawQuery("SELECT event_id,command_id FROM sync_outbox", null).use { c -> while (c.moveToNext()) keys += c.getString(0) to c.getString(1) }
        keys.forEach { (eventId, commandId) ->
            val normalizedEventId = runCatching { UUID.fromString(eventId).toString(); eventId }.getOrElse { UUID.randomUUID().toString() }
            if (normalizedEventId != eventId) db.execSQL("UPDATE sync_outbox SET event_id=? WHERE event_id=?", arrayOf(normalizedEventId, eventId))
            db.execSQL("UPDATE sync_outbox SET status='blocked_contract', blocked_reason=COALESCE(blocked_reason,'legacy_payload_unavailable') WHERE command_id=? AND payload_json IS NULL", arrayOf(commandId))
        }
    }

    private fun hasColumn(db: CipherDatabase, table: String, column: String): Boolean =
        db.rawQuery("PRAGMA table_info(\"${table.replace("\"", "\"\"")}\")", null).use { cursor ->
            while (cursor.moveToNext()) if (cursor.getString(1) == column) return@use true
            false
        }

    private fun insertSyncOutbox(db: CipherDatabase, commandId: String, createdAt: Long, payload: SyncOutboxPayload) {
        db.insertOrThrow("sync_outbox", null, ContentValues().apply {
            put("event_id", UUID.randomUUID().toString())
            put("command_id", commandId)
            put("status", if (payload.sendable) "pending" else "blocked_contract")
            put("created_at", createdAt)
            put("payload_version", payload.version)
            put("payload_json", payload.json)
            put("blocked_reason", payload.blockedReason)
        })
    }

    override fun loadPendingSyncEvents(limit: Int): List<SyncOutboxEvent> {
        require(limit in 1..100)
        val permit = BusinessWriteBarrier.tryBeginCommit() ?: throw BusinessMaintenanceException()
        try {
            return readableDatabase.rawQuery(
                "SELECT event_id,command_id,payload_version,payload_json,(SELECT actor_id FROM audit_events WHERE command_id=sync_outbox.command_id ORDER BY created_at DESC,rowid DESC LIMIT 1) FROM sync_outbox WHERE status='pending' AND payload_version IS NOT NULL AND payload_json IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sync_outbox conflict WHERE conflict.status='conflict' AND (conflict.created_at<sync_outbox.created_at OR (conflict.created_at=sync_outbox.created_at AND conflict.event_id<sync_outbox.event_id))) ORDER BY created_at,event_id LIMIT ?",
                arrayOf(limit.toString())
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(SyncOutboxEvent(cursor.getString(0), cursor.getString(1), cursor.getInt(2), cursor.getString(3), cursor.getString(4)))
                }
            }
        } finally { permit.close() }
    }

    override fun loadSyncCursor(): Long {
        val permit = BusinessWriteBarrier.tryBeginCommit() ?: throw BusinessMaintenanceException()
        try {
            return readableDatabase.rawQuery("SELECT value FROM metadata WHERE key='sync_server_revision'", null).use { cursor ->
                if (!cursor.moveToFirst()) 0L else cursor.getString(0).toLongOrNull()?.takeIf { it >= 0L }
                    ?: throw IllegalStateException("Stored sync revision is invalid.")
            }
        } finally { permit.close() }
    }

    override fun recordSyncOutcome(eventId: String, outcome: String, serverRevision: Long): Boolean {
        require(eventId.isNotBlank() && outcome in setOf("accepted", "duplicate", "conflict") && serverRevision >= 0L)
        val permit = BusinessWriteBarrier.tryBeginCommit() ?: throw BusinessMaintenanceException()
        try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                val current = db.rawQuery("SELECT status,server_revision FROM sync_outbox WHERE event_id=?", arrayOf(eventId)).use { cursor ->
                    if (!cursor.moveToFirst()) return false
                    cursor.getString(0) to if (cursor.isNull(1)) null else cursor.getLong(1)
                }
                if (current.first == "acknowledged") {
                    val sameOutcome = outcome in setOf("accepted", "duplicate") && current.second == serverRevision
                    if (sameOutcome) { db.setTransactionSuccessful(); return true }
                    return false
                }
                if (current.first == "conflict" && outcome == "conflict" && current.second == serverRevision) {
                    db.setTransactionSuccessful(); return true
                }
                if (current.first != "pending") return false
                val nextEligibleEvent = db.rawQuery(
                    "SELECT event_id FROM sync_outbox queued WHERE queued.status='pending' AND queued.payload_version IS NOT NULL AND queued.payload_json IS NOT NULL AND NOT EXISTS (SELECT 1 FROM sync_outbox conflict WHERE conflict.status='conflict' AND (conflict.created_at<queued.created_at OR (conflict.created_at=queued.created_at AND conflict.event_id<queued.event_id))) ORDER BY queued.created_at,queued.event_id LIMIT 1",
                    null
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                if (nextEligibleEvent != eventId) return false
                if (outcome == "conflict") {
                    db.execSQL("UPDATE sync_outbox SET status='conflict',server_revision=?,blocked_reason='server_revision_conflict' WHERE event_id=? AND status='pending'", arrayOf<Any>(serverRevision, eventId))
                } else {
                    db.execSQL("UPDATE sync_outbox SET status='acknowledged',server_revision=?,blocked_reason=NULL WHERE event_id=? AND status='pending'", arrayOf<Any>(serverRevision, eventId))
                    val oldCursor = db.rawQuery("SELECT value FROM metadata WHERE key='sync_server_revision'", null).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0).toLongOrNull() ?: 0L else 0L }
                    if (serverRevision > oldCursor) {
                        db.insertWithOnConflict("metadata", null, ContentValues().apply { put("key", "sync_server_revision"); put("value", serverRevision.toString()) }, CipherDatabase.CONFLICT_REPLACE)
                    }
                }
                db.setTransactionSuccessful()
                return true
            } finally { db.endTransaction() }
        } finally { permit.close() }
    }

    private fun createLocalOwnerTable(db: CipherDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS local_owner_credentials(username TEXT PRIMARY KEY, display_name TEXT NOT NULL, salt BLOB NOT NULL, verifier BLOB NOT NULL, iterations INTEGER NOT NULL, failed_attempts INTEGER NOT NULL DEFAULT 0, locked_until_ms INTEGER NOT NULL DEFAULT 0, enabled INTEGER NOT NULL DEFAULT 1)")
    }

    private fun createStaffCredentialsTable(db: CipherDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS staff_credentials(staff_id TEXT PRIMARY KEY, display_name TEXT NOT NULL CHECK(length(display_name) BETWEEN 1 AND 80), role TEXT NOT NULL CHECK(role IN ('CASHIER','MANAGER')), salt BLOB NOT NULL, verifier BLOB NOT NULL, iterations INTEGER NOT NULL CHECK(iterations >= 100000), failed_attempts INTEGER NOT NULL DEFAULT 0, locked_until_ms INTEGER NOT NULL DEFAULT 0, enabled INTEGER NOT NULL DEFAULT 1 CHECK(enabled IN (0,1)), created_at INTEGER NOT NULL)")
    }

    override fun listStaffAccounts(): List<StaffAccount> {
        val permit = BusinessWriteBarrier.tryBeginCommit() ?: throw BusinessMaintenanceException()
        try { return readableDatabase.rawQuery("SELECT staff_id,display_name,role,enabled,created_at FROM staff_credentials ORDER BY display_name COLLATE NOCASE", null).use { c ->
            buildList { while (c.moveToNext()) add(StaffAccount(c.getString(0), c.getString(1), StaffRole.valueOf(c.getString(2)), c.getInt(3) != 0, c.getLong(4))) }
        } } finally { permit.close() }
    }

    override fun loadStaffCredential(staffId: String): StaffCredential? {
        val permit = BusinessWriteBarrier.tryBeginCommit() ?: throw BusinessMaintenanceException()
        try { return readableDatabase.rawQuery("SELECT staff_id,display_name,role,enabled,created_at,salt,verifier,iterations,failed_attempts,locked_until_ms FROM staff_credentials WHERE staff_id=? COLLATE NOCASE", arrayOf(staffId.trim())).use { c ->
            if (!c.moveToFirst()) null else StaffCredential(StaffAccount(c.getString(0), c.getString(1), StaffRole.valueOf(c.getString(2)), c.getInt(3) != 0, c.getLong(4)), c.getBlob(5), c.getBlob(6), c.getInt(7), c.getInt(8), c.getLong(9))
        } } finally { permit.close() }
    }

    override fun createStaffCredential(credential: StaffCredential, commandId: String): Boolean {
        requireOwnerMutation("manage staff accounts")
        require(credential.account.displayName.trim().length in 1..80 && credential.salt.size >= 16 && credential.verifier.size >= 32 && credential.iterations >= 100_000)
        val permit = BusinessWriteBarrier.tryBeginCommit() ?: throw BusinessMaintenanceException()
        return try { val db = writableDatabase; db.beginTransaction(); try {
            db.insertOrThrow("staff_credentials", null, ContentValues().apply { put("staff_id", credential.account.staffId); put("display_name", credential.account.displayName.trim()); put("role", credential.account.role.name); put("salt", credential.salt); put("verifier", credential.verifier); put("iterations", credential.iterations); put("enabled", 1); put("created_at", credential.account.createdAtEpochMs) })
            db.insertOrThrow("audit_events", null, auditValues(commandId, commandId, "staff.account_created role=${credential.account.role.name}", System.currentTimeMillis()))
            db.setTransactionSuccessful(); true
        } finally { db.endTransaction() } } finally { permit.close() }
    }

    override fun setStaffAccountEnabled(staffId: String, enabled: Boolean, commandId: String): Boolean {
        requireOwnerMutation("manage staff accounts")
        val permit = BusinessWriteBarrier.tryBeginCommit() ?: throw BusinessMaintenanceException()
        return try { val db = writableDatabase; db.beginTransaction(); try {
            val changed = db.update("staff_credentials", ContentValues().apply { put("enabled", if (enabled) 1 else 0); put("failed_attempts", 0); put("locked_until_ms", 0) }, "staff_id=?", arrayOf(staffId))
            if (changed > 0) db.insertOrThrow("audit_events", null, auditValues(commandId, commandId, if (enabled) "staff.account_enabled" else "staff.account_disabled", System.currentTimeMillis()))
            db.setTransactionSuccessful(); changed > 0
        } finally { db.endTransaction() } } finally { permit.close() }
    }

    override fun recordStaffLogin(staffId: String, succeeded: Boolean, commandId: String, nowMs: Long): Boolean {
        val permit = BusinessWriteBarrier.tryBeginCommit() ?: throw BusinessMaintenanceException()
        return try { val db = writableDatabase; db.beginTransaction(); try {
            val prior = db.rawQuery("SELECT failed_attempts,locked_until_ms FROM staff_credentials WHERE staff_id=? AND enabled=1", arrayOf(staffId.trim())).use { c -> if (c.moveToFirst()) c.getInt(0) to c.getLong(1) else return false }
            if (!succeeded && prior.second > nowMs) return false
            val failures = if (succeeded) 0 else prior.first + 1
            val lockedUntil = if (failures >= 5) nowMs + 60_000 else 0L
            db.execSQL("UPDATE staff_credentials SET failed_attempts=?,locked_until_ms=? WHERE staff_id=?", arrayOf(failures, lockedUntil, staffId.trim()))
            db.insertOrThrow("audit_events", null, auditValues(commandId, commandId, if (succeeded) "staff.login_succeeded" else "staff.login_failed", nowMs, staffId.trim().takeIf { succeeded }))
            db.setTransactionSuccessful(); true
        } finally { db.endTransaction() } } finally { permit.close() }
    }

    private fun createCustomerProfilesTable(db: CipherDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS customer_profiles(customer_id TEXT PRIMARY KEY,display_name TEXT NOT NULL CHECK(length(display_name) BETWEEN 2 AND 80),phone TEXT,contact_consent INTEGER NOT NULL DEFAULT 0 CHECK(contact_consent IN (0,1)),created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,CHECK(phone IS NULL OR contact_consent=1))")
    }

    override fun localOwnerExists(): Boolean {
        val permit = BusinessWriteBarrier.tryBeginCommit() ?: throw BusinessMaintenanceException()
        try { return readableDatabase.rawQuery("SELECT 1 FROM local_owner_credentials WHERE enabled=1 LIMIT 1", null).use { it.moveToFirst() } }
        finally { permit.close() }
    }

    override fun loadLocalOwnerCredential(username: String): LocalOwnerCredential? {
        val permit = BusinessWriteBarrier.tryBeginCommit() ?: throw BusinessMaintenanceException()
        try {
            return readableDatabase.rawQuery("SELECT username,display_name,salt,verifier,iterations,failed_attempts,locked_until_ms,enabled FROM local_owner_credentials WHERE username=?", arrayOf(username)).use { c ->
                if (!c.moveToFirst()) null else LocalOwnerCredential(c.getString(0), c.getString(1), c.getBlob(2), c.getBlob(3), c.getInt(4), c.getInt(5), c.getLong(6), c.getInt(7) != 0)
            }
        } finally { permit.close() }
    }

    override fun createLocalOwnerCredential(credential: LocalOwnerCredential, commandId: String): Boolean {
        require(credential.username.isNotBlank() && credential.salt.size >= 16 && credential.verifier.size >= 32)
        val permit = BusinessWriteBarrier.tryBeginCommit() ?: throw BusinessMaintenanceException()
        try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                if (db.rawQuery("SELECT 1 FROM local_owner_credentials LIMIT 1", null).use { it.moveToFirst() }) return false
                db.insertOrThrow("local_owner_credentials", null, ContentValues().apply {
                    put("username", credential.username); put("display_name", credential.displayName); put("salt", credential.salt)
                    put("verifier", credential.verifier); put("iterations", credential.iterations); put("enabled", 1)
                })
                db.insertOrThrow("audit_events", null, auditValues(commandId, commandId, "local_owner.enrolled", System.currentTimeMillis(), "local-owner"))
                db.setTransactionSuccessful(); return true
            } finally { db.endTransaction() }
        } finally { permit.close() }
    }

    override fun recordLocalOwnerLogin(username: String, succeeded: Boolean, commandId: String, nowMs: Long): Boolean {
        val permit = BusinessWriteBarrier.tryBeginCommit() ?: throw BusinessMaintenanceException()
        try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                val prior = db.rawQuery("SELECT failed_attempts,locked_until_ms FROM local_owner_credentials WHERE username=? AND enabled=1", arrayOf(username)).use { c -> if (c.moveToFirst()) c.getInt(0) to c.getLong(1) else return false }
                if (!succeeded && prior.second > nowMs) return false
                val failures = if (succeeded) 0 else prior.first + 1
                val lockedUntil = if (failures >= 5) nowMs + 60_000 else 0L
                db.execSQL("UPDATE local_owner_credentials SET failed_attempts=?,locked_until_ms=? WHERE username=?", arrayOf(failures, lockedUntil, username))
                db.insertOrThrow("audit_events", null, auditValues(commandId, commandId, if (succeeded) "local_owner.login_succeeded" else "local_owner.login_failed", nowMs, "local-owner".takeIf { succeeded }))
                db.setTransactionSuccessful(); return true
            } finally { db.endTransaction() }
        } finally { permit.close() }
    }

    override fun upgradeLocalOwnerVerifier(username: String, salt: ByteArray, verifier: ByteArray, iterations: Int, commandId: String): Boolean {
        require(salt.size >= 16 && verifier.size >= 32 && iterations >= 100_000)
        val permit = BusinessWriteBarrier.tryBeginCommit() ?: throw BusinessMaintenanceException()
        try {
            val db = writableDatabase
            db.beginTransaction()
            try {
                val changed = db.update("local_owner_credentials", ContentValues().apply {
                    put("salt", salt); put("verifier", verifier); put("iterations", iterations)
                }, "username=? AND iterations<?", arrayOf(username, iterations.toString()))
                if (changed > 0) db.insertOrThrow("audit_events", null, auditValues(commandId, commandId, "local_owner.verifier_upgraded iterations=$iterations", System.currentTimeMillis(), "local-owner"))
                db.setTransactionSuccessful(); return changed > 0
            } finally { db.endTransaction() }
        } finally { permit.close() }
    }

    override fun load(): CounterSnapshot? {
        val permit = BusinessWriteBarrier.tryBeginCommit() ?: throw BusinessMaintenanceException()
        try { return loadSnapshot() } finally { permit.close() }
    }

    override fun loadShopProfile(): ShopProfile {
        val permit = BusinessWriteBarrier.tryBeginCommit() ?: throw BusinessMaintenanceException()
        try {
            val db = readableDatabase
            assertCipherActive(db)
            fun value(key: String, fallback: String) = db.rawQuery("SELECT value FROM metadata WHERE key=?", arrayOf(key)).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else fallback
            }
            return ShopProfile(
                shopName = value("shop_name", ""),
                businessType = value("business_type", "chai_tapri"),
                languageTag = value("language_tag", "hi-IN"),
                currencyCode = value("currency_code", "INR")
            )
        } finally { permit.close() }
    }

    override fun loadVoiceLanguageTag(): String {
        val permit = BusinessWriteBarrier.tryBeginCommit() ?: throw BusinessMaintenanceException()
        try {
            val db = readableDatabase
            assertCipherActive(db)
            return db.rawQuery("SELECT value FROM metadata WHERE key='voice_language_tag'", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else ""
            }
        } finally { permit.close() }
    }

    override fun saveVoiceLanguageTag(languageTag: String, commandId: String): Boolean {
        requireOwnerMutation("change voice settings")
        require(languageTag in setOf("hi-IN", "mr-IN", "en-IN")) { "Unsupported speech language." }
        require(commandId.isNotBlank()) { "Command ID is required." }
        val permit = BusinessWriteBarrier.tryBeginCommit() ?: throw BusinessMaintenanceException()
        try {
            val db = writableDatabase
            assertCipherActive(db)
            db.beginTransaction()
            try {
                db.rawQuery("SELECT 1 FROM command_receipts WHERE command_id=?", arrayOf(commandId)).use { if (it.moveToFirst()) return false }
                db.insertWithOnConflict("metadata", null, ContentValues().apply { put("key", "voice_language_tag"); put("value", languageTag) }, CipherDatabase.CONFLICT_REPLACE)
                val now = System.currentTimeMillis()
                val event = "voice.language_updated language=$languageTag"
                db.insertOrThrow("command_receipts", null, ContentValues().apply { put("command_id", commandId); put("result", "Voice language saved"); put("committed_at", now) })
                db.insertOrThrow("audit_events", null, auditValues(commandId, commandId, event, now))
                insertSyncOutbox(db, commandId, now, SyncOutboxPayload(null, null, "shop_settings_contract_pending"))
                db.setTransactionSuccessful()
                return true
            } finally { db.endTransaction() }
        } finally { permit.close() }
    }

    override fun saveShopProfile(profile: ShopProfile, commandId: String): Boolean {
        requireOwnerMutation("change shop settings")
        val permit = BusinessWriteBarrier.tryBeginCommit() ?: throw BusinessMaintenanceException()
        try {
            val db = writableDatabase
            assertCipherActive(db)
            db.beginTransaction()
            try {
                db.rawQuery("SELECT 1 FROM command_receipts WHERE command_id=?", arrayOf(commandId)).use { if (it.moveToFirst()) return false }
                listOf(
                    "shop_name" to profile.shopName,
                    "business_type" to profile.businessType,
                    "language_tag" to profile.languageTag,
                    "currency_code" to profile.currencyCode
                ).forEach { (key, value) ->
                    db.insertWithOnConflict("metadata", null, ContentValues().apply { put("key", key); put("value", value) }, CipherDatabase.CONFLICT_REPLACE)
                }
                val now = System.currentTimeMillis()
                val event = "shop.profile_updated type=${profile.businessType} language=${profile.languageTag} currency=${profile.currencyCode}"
                db.insertOrThrow("command_receipts", null, ContentValues().apply { put("command_id", commandId); put("result", "Shop profile saved"); put("committed_at", now) })
                db.insertOrThrow("audit_events", null, auditValues(commandId, commandId, event, now))
                insertSyncOutbox(db, commandId, now, SyncOutboxPayload(null, null, "shop_settings_contract_pending"))
                db.setTransactionSuccessful()
                return true
            } finally { db.endTransaction() }
        } finally { permit.close() }
    }

    override fun loadProducts(): List<TapriProduct> {
        val permit = BusinessWriteBarrier.tryBeginCommit() ?: throw BusinessMaintenanceException()
        try {
            val db = readableDatabase
            assertCipherActive(db)
            return db.rawQuery("SELECT product_id,name,category,unit_price_paise,active,favorite,price_confirmed,aliases,reorder_threshold,estimated_unit_cost_paise,scan_code,unit_label,purchase_unit_label,base_units_per_purchase_unit FROM product_catalog ORDER BY name COLLATE NOCASE", null).use { c ->
                buildList { while (c.moveToNext()) add(TapriProduct(c.getString(0), c.getString(1), c.getString(2), c.getInt(3), c.getInt(4) != 0, c.getInt(5) != 0, c.getInt(6) != 0, c.getString(7).split('|').filter(String::isNotBlank), if (c.isNull(8)) null else c.getInt(8), if (c.isNull(9)) null else c.getInt(9), if (c.isNull(10)) null else c.getString(10), c.getString(11), c.getString(12), c.getInt(13))) }
            }
        } finally { permit.close() }
    }

    override fun saveProduct(product: TapriProduct, commandId: String): Boolean {
        requireOwnerMutation("change the product catalogue")
        val permit = BusinessWriteBarrier.tryBeginCommit() ?: throw BusinessMaintenanceException()
        try {
            val db = writableDatabase
            assertCipherActive(db)
            db.beginTransaction()
            try {
                db.rawQuery("SELECT 1 FROM command_receipts WHERE command_id=?", arrayOf(commandId)).use { if (it.moveToFirst()) return false }
                val existed = db.rawQuery("SELECT 1 FROM product_catalog WHERE product_id=?", arrayOf(product.productId)).use { it.moveToFirst() }
                product.scanCode?.trim()?.takeIf(String::isNotEmpty)?.let { code ->
                    db.rawQuery("SELECT 1 FROM product_catalog WHERE scan_code=? COLLATE NOCASE AND product_id<>? LIMIT 1", arrayOf(code, product.productId)).use {
                        if (it.moveToFirst()) return false
                    }
                }
                db.insertWithOnConflict("product_catalog", null, ContentValues().apply {
                    put("product_id", product.productId); put("name", product.name.trim()); put("category", product.category.trim()); put("unit_price_paise", product.unitPricePaise)
                    put("active", if (product.active) 1 else 0); put("favorite", if (product.favorite) 1 else 0); put("price_confirmed", if (product.priceConfirmed) 1 else 0)
                    put("aliases", product.aliases.map { it.trim().lowercase() }.filter(String::isNotBlank).distinct().joinToString("|"))
                    if (product.reorderThreshold == null) putNull("reorder_threshold") else put("reorder_threshold", product.reorderThreshold)
                    if (product.estimatedUnitCostPaise == null) putNull("estimated_unit_cost_paise") else put("estimated_unit_cost_paise", product.estimatedUnitCostPaise)
                    if (product.scanCode.isNullOrBlank()) putNull("scan_code") else put("scan_code", product.scanCode.trim())
                    put("unit_label", product.unitLabel.trim())
                    put("purchase_unit_label", product.purchaseUnitLabel.trim())
                    put("base_units_per_purchase_unit", product.baseUnitsPerPurchaseUnit)
                }, CipherDatabase.CONFLICT_REPLACE)
                if (!existed) db.insertWithOnConflict("stock", null, ContentValues().apply { put("product_id", product.productId); put("quantity", 0) }, CipherDatabase.CONFLICT_IGNORE)
                val now = System.currentTimeMillis()
                db.insertOrThrow("command_receipts", null, ContentValues().apply { put("command_id", commandId); put("result", "Product saved"); put("committed_at", now) })
                db.insertOrThrow("audit_events", null, auditValues(commandId, commandId, "product.catalog_saved product_id=${product.productId} price_confirmed=${product.priceConfirmed} active=${product.active} unit=${product.unitLabel.trim()} purchase_unit=${product.purchaseUnitLabel.trim()} base_units_per_purchase_unit=${product.baseUnitsPerPurchaseUnit}", now))
                insertSyncOutbox(db, commandId, now, SyncOutboxPayloadEncoder.catalogueProduct(product))
                db.setTransactionSuccessful()
                return true
            } finally { db.endTransaction() }
        } finally { permit.close() }
    }

    override fun loadCustomerProfiles(): List<CustomerProfile> {
        val permit = BusinessWriteBarrier.tryBeginCommit() ?: throw BusinessMaintenanceException()
        try {
            val db = readableDatabase
            assertCipherActive(db)
            return db.rawQuery("SELECT customer_id,display_name,phone,contact_consent,created_at,updated_at FROM customer_profiles ORDER BY display_name COLLATE NOCASE,customer_id", null).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(CustomerProfile(
                        customerId = cursor.getString(0), displayName = cursor.getString(1),
                        phone = cursor.getString(2)?.takeIf(String::isNotBlank), contactConsent = cursor.getInt(3) != 0,
                        createdAt = cursor.getLong(4), updatedAt = cursor.getLong(5)
                    ))
                }
            }
        } finally { permit.close() }
    }

    override fun loadAuditRecords(): List<AuditRecord> {
        val db = readableDatabase
        assertCipherActive(db)
        return db.rawQuery("SELECT event_id,command_id,event,created_at,actor_id FROM audit_events ORDER BY created_at,rowid", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(AuditRecord(cursor.getString(0), cursor.getString(1), cursor.getString(2), cursor.getLong(3), cursor.getString(4)))
            }
        }
    }

    override fun saveCustomerProfile(profile: CustomerProfile, commandId: String): Boolean {
        requireManagerMutation("manage customer profiles")
        require(commandId.isNotBlank()) { "Command ID is required." }
        require(profile.customerId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._ -]{1,59}"))) { "Customer account ID is invalid." }
        require(profile.displayName.trim().length in 2..80) { "Customer name must be 2 to 80 characters." }
        require(profile.phone == null || (profile.contactConsent && profile.phone.matches(Regex("\\+?[0-9]{8,15}")))) {
            "A valid phone number requires explicit contact consent."
        }
        require(profile.phone != null || !profile.contactConsent) { "Contact consent requires a phone number." }
        val permit = BusinessWriteBarrier.tryBeginCommit() ?: throw BusinessMaintenanceException()
        try {
            val db = writableDatabase
            assertCipherActive(db)
            db.beginTransaction()
            try {
                db.rawQuery("SELECT 1 FROM command_receipts WHERE command_id=?", arrayOf(commandId)).use { if (it.moveToFirst()) return false }
                db.rawQuery("SELECT 1 FROM customer_profiles WHERE customer_id=?", arrayOf(profile.customerId)).use { if (it.moveToFirst()) return false }
                val now = System.currentTimeMillis()
                db.insertOrThrow("customer_profiles", null, ContentValues().apply {
                    put("customer_id", profile.customerId); put("display_name", profile.displayName.trim())
                    put("phone", profile.phone); put("contact_consent", if (profile.contactConsent) 1 else 0)
                    put("created_at", now); put("updated_at", now)
                })
                val event = "customer.profile_saved phone_stored=${profile.phone != null} consent=${profile.contactConsent}"
                db.insertOrThrow("command_receipts", null, ContentValues().apply { put("command_id", commandId); put("result", "Customer profile saved"); put("committed_at", now) })
                db.insertOrThrow("audit_events", null, auditValues(commandId, commandId, event, now))
                insertSyncOutbox(db, commandId, now, SyncOutboxPayload(null, null, "customer_profile_contract_pending"))
                db.setTransactionSuccessful()
                return true
            } finally { db.endTransaction() }
        } finally { permit.close() }
    }

    override fun updateCustomerProfile(profile: CustomerProfile, commandId: String): Boolean {
        requireManagerMutation("manage customer profiles")
        require(commandId.isNotBlank()) { "Command ID is required." }
        require(profile.customerId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._ -]{1,59}"))) { "Customer account ID is invalid." }
        require(profile.displayName.trim().length in 2..80) { "Customer name must be 2 to 80 characters." }
        require(profile.phone == null || (profile.contactConsent && profile.phone.matches(Regex("\\+?[0-9]{8,15}")))) {
            "A valid phone number requires explicit contact consent."
        }
        require(profile.phone != null || !profile.contactConsent) { "Contact consent requires a phone number." }
        val permit = BusinessWriteBarrier.tryBeginCommit() ?: throw BusinessMaintenanceException()
        try {
            val db = writableDatabase
            assertCipherActive(db)
            db.beginTransaction()
            try {
                db.rawQuery("SELECT 1 FROM command_receipts WHERE command_id=?", arrayOf(commandId)).use { if (it.moveToFirst()) return false }
                val oldCreatedAt = db.rawQuery("SELECT created_at FROM customer_profiles WHERE customer_id=?", arrayOf(profile.customerId)).use {
                    if (!it.moveToFirst()) return false
                    it.getLong(0)
                }
                val now = System.currentTimeMillis()
                val updated = db.update("customer_profiles", ContentValues().apply {
                    put("display_name", profile.displayName.trim())
                    put("phone", profile.phone)
                    put("contact_consent", if (profile.contactConsent) 1 else 0)
                    put("created_at", oldCreatedAt)
                    put("updated_at", now)
                }, "customer_id=?", arrayOf(profile.customerId))
                if (updated != 1) return false
                val event = "customer.profile_updated phone_stored=${profile.phone != null} consent=${profile.contactConsent} consent_revoked=${profile.phone == null}"
                db.insertOrThrow("command_receipts", null, ContentValues().apply { put("command_id", commandId); put("result", "Customer profile updated"); put("committed_at", now) })
                db.insertOrThrow("audit_events", null, auditValues(commandId, commandId, event, now))
                insertSyncOutbox(db, commandId, now, SyncOutboxPayload(null, null, "customer_profile_contract_pending"))
                db.setTransactionSuccessful()
                return true
            } finally { db.endTransaction() }
        } finally { permit.close() }
    }

    internal fun loadDuringMaintenance(): CounterSnapshot? = loadSnapshot()

    internal fun shopIdentity(): String {
        val db = readableDatabase
        assertCipherActive(db)
        return db.rawQuery("SELECT value FROM metadata WHERE key=?", arrayOf(STORE_IDENTITY_KEY)).use { cursor ->
            check(cursor.moveToFirst()) { "Encrypted shop identity is missing." }
            cursor.getString(0).also { check(UUID.fromString(it).toString() == it) { "Encrypted shop identity is invalid." } }
        }
    }

    /** A small unbacked local binding lets recovery distinguish this shop after its DB key is lost. */
    internal fun bindShopIdentity(): String {
        val identity = shopIdentity()
        val prefs = appContext.getSharedPreferences("chai_tapri_shop_identity_guard", Context.MODE_PRIVATE)
        val key = "${identityNamespace}:${storeDatabaseName}"
        // Called only while the database key is available and its identity has been read from SQLCipher.
        // Refreshing after an explicit new-shop setup prevents a stale prior-shop binding.
        check(prefs.edit().putString(key, identity).commit()) { "Could not persist the local shop recovery binding." }
        return identity
    }

    internal fun hasBusinessActivity(): Boolean {
        val db = readableDatabase
        assertCipherActive(db)
        return listOf("orders", "command_receipts", "audit_events", "stock_movements", "customer_credit", "customer_profiles", "credit_repayments", "customer_sync_identities", "payment_entries", "suppliers", "purchase_receipts", "supplier_payments", "purchase_returns", "expenses", "cash_drawer_sessions", "cash_drawer_movements", "cash_closing_reviews")
            .any { table -> cipherLong(db, "SELECT COUNT(*) FROM $table") > 0L }
    }

    private fun loadSnapshot(): CounterSnapshot? {
        val db = readableDatabase
        assertCipherActive(db)
        val next = db.rawQuery("SELECT value FROM metadata WHERE key='next_order_number'", null).use { cursor ->
            if (!cursor.moveToFirst()) return null
            cursor.getString(0).toInt()
        }
        val orders = mutableListOf<CounterOrderRecord>()
        db.rawQuery("SELECT order_no, fulfillment, payment, paid_paise, sale_posted, cancel_reason, revision, credit_paise, refunded_paise, voided, cash_refunded_paise, credit_reversed_paise, created_at FROM orders ORDER BY order_no", null).use { c ->
            while (c.moveToNext()) {
                val number = c.getInt(0)
                val lines = mutableListOf<CounterLine>()
                db.rawQuery("SELECT product_id, name, quantity, delivered_quantity, unit_price_paise, refunded_quantity, estimated_unit_cost_paise FROM order_lines WHERE order_no=? ORDER BY product_id", arrayOf(number.toString())).use { l ->
                    while (l.moveToNext()) lines += CounterLine(l.getString(0), l.getString(1), l.getInt(2), l.getInt(4), l.getInt(3), l.getInt(5), if (l.isNull(6)) null else l.getInt(6))
                }
                orders += CounterOrderRecord(number, lines, Fulfillment.valueOf(c.getString(1)), Payment.valueOf(c.getString(2)), c.getInt(3), c.getInt(4) != 0, if (c.isNull(5)) null else c.getString(5), c.getInt(6), c.getInt(7), c.getInt(8), c.getInt(9) != 0, c.getInt(10), c.getInt(11), c.getLong(12))
            }
        }
        val stock = mutableMapOf<String, Int>()
        db.rawQuery("SELECT product_id, quantity FROM stock", null).use { c -> while (c.moveToNext()) stock[c.getString(0)] = c.getInt(1) }
        val commandIds = mutableSetOf<String>()
        db.rawQuery("SELECT command_id FROM command_receipts", null).use { c -> while (c.moveToNext()) commandIds += c.getString(0) }
        val events = mutableListOf<String>()
        db.rawQuery("SELECT event FROM audit_events ORDER BY rowid", null).use { c -> while (c.moveToNext()) events += c.getString(0) }
        val movements = mutableListOf<StockMovement>()
        db.rawQuery("SELECT movement_id, product_id, quantity_delta, reason, order_no FROM stock_movements ORDER BY rowid", null).use { c ->
            while (c.moveToNext()) movements += StockMovement(c.getString(0), c.getString(1), c.getInt(2), c.getString(3), if (c.isNull(4)) null else c.getInt(4))
        }
        val credits = mutableListOf<CustomerCredit>()
        db.rawQuery("SELECT credit_id, customer_id, order_no, amount_paise, repaid_paise, reversed_paise, sync_id FROM customer_credit ORDER BY rowid", null).use { c ->
            while (c.moveToNext()) credits += CustomerCredit(c.getString(0),c.getString(1),c.getInt(2),c.getInt(3),c.getInt(4),c.getInt(5),c.getString(6))
        }
        val repayments = mutableListOf<CreditRepayment>()
        db.rawQuery("SELECT repayment_id, customer_id, amount_paise, sync_id FROM credit_repayments ORDER BY rowid", null).use { c ->
            while (c.moveToNext()) repayments += CreditRepayment(c.getString(0),c.getString(1),c.getInt(2),c.getString(3))
        }
        val customerSyncIdentities = mutableMapOf<String, String>()
        db.rawQuery("SELECT local_customer_id,sync_id FROM customer_sync_identities", null).use { c -> while (c.moveToNext()) customerSyncIdentities[c.getString(0)] = c.getString(1) }
        val paymentEntries = mutableListOf<PaymentEntry>()
        db.rawQuery("SELECT entry_id, order_no, customer_id, amount_paise, method, status, kind, related_entry_id, drawer_session_id, created_at, sync_id FROM payment_entries ORDER BY rowid", null).use { c ->
            while (c.moveToNext()) paymentEntries += PaymentEntry(c.getString(0), if (c.isNull(1)) null else c.getInt(1), if (c.isNull(2)) null else c.getString(2), c.getInt(3), c.getString(4), c.getString(5), c.getString(6), if (c.isNull(7)) null else c.getString(7), if (c.isNull(8)) null else c.getString(8), c.getLong(9), c.getString(10))
        }
        val confirmedStock = mutableSetOf<String>()
        db.rawQuery("SELECT product_id FROM stock_baseline_confirmations", null).use { c -> while (c.moveToNext()) confirmedStock += c.getString(0) }
        val suppliers = mutableListOf<SupplierRecord>()
        db.rawQuery("SELECT supplier_id,name,phone,sync_id FROM suppliers ORDER BY name COLLATE NOCASE", null).use { c -> while (c.moveToNext()) suppliers += SupplierRecord(c.getString(0), c.getString(1), if (c.isNull(2)) null else c.getString(2), c.getString(3)) }
        val receiptLines = mutableMapOf<String, MutableList<PurchaseReceiptLine>>()
        db.rawQuery("SELECT receipt_id,product_id,product_name,quantity,unit_cost_paise FROM purchase_receipt_lines ORDER BY rowid", null).use { c ->
            while (c.moveToNext()) receiptLines.getOrPut(c.getString(0)) { mutableListOf() } += PurchaseReceiptLine(c.getString(1), c.getString(2), c.getInt(3), c.getInt(4))
        }
        val receipts = mutableListOf<PurchaseReceipt>()
        db.rawQuery("SELECT receipt_id,supplier_id,total_paise,supplier_invoice_reference,sync_id FROM purchase_receipts ORDER BY created_at,receipt_id", null).use { c ->
            while (c.moveToNext()) receipts += PurchaseReceipt(c.getString(0), c.getString(1), receiptLines[c.getString(0)].orEmpty(), c.getLong(2), null, if (c.isNull(3)) null else c.getString(3), c.getString(4))
        }
        val linkedReceipts = mutableMapOf<String, String>()
        db.rawQuery("SELECT receipt_id,purchase_order_id FROM purchase_receipts WHERE purchase_order_id IS NOT NULL", null).use { c ->
            while (c.moveToNext()) linkedReceipts[c.getString(0)] = c.getString(1)
        }
        val linkedReceiptList = receipts.map { receipt -> receipt.copy(purchaseOrderId = linkedReceipts[receipt.receiptId]) }
        val draftLines = mutableMapOf<String, MutableList<PurchaseOrderDraftLine>>()
        db.rawQuery("SELECT purchase_order_id,product_id,product_name,ordered_quantity,unit_cost_paise FROM purchase_order_draft_lines ORDER BY rowid", null).use { c ->
            while (c.moveToNext()) draftLines.getOrPut(c.getString(0)) { mutableListOf() } += PurchaseOrderDraftLine(c.getString(1), c.getString(2), c.getInt(3), c.getInt(4))
        }
        val purchaseOrderDrafts = mutableListOf<PurchaseOrderDraft>()
        db.rawQuery("SELECT purchase_order_id,supplier_id,status,revision,created_at FROM purchase_order_drafts ORDER BY created_at,purchase_order_id", null).use { c ->
            while (c.moveToNext()) purchaseOrderDrafts += PurchaseOrderDraft(c.getString(0), c.getString(1), draftLines[c.getString(0)].orEmpty(), PurchaseOrderStatus.valueOf(c.getString(2)), c.getInt(3), c.getLong(4))
        }
        val supplierPayments = mutableListOf<SupplierPayment>()
        db.rawQuery("SELECT payment_id,supplier_id,amount_paise,method,status,drawer_session_id,sync_id FROM supplier_payments ORDER BY created_at,payment_id", null).use { c ->
            while (c.moveToNext()) supplierPayments += SupplierPayment(c.getString(0), c.getString(1), c.getInt(2), c.getString(3), c.getString(4), if (c.isNull(5)) null else c.getString(5), c.getString(6))
        }
        val returnLines = mutableMapOf<String, MutableList<PurchaseReturnLine>>()
        db.rawQuery("SELECT return_id,product_id,product_name,quantity,unit_cost_paise FROM purchase_return_lines ORDER BY rowid", null).use { c ->
            while (c.moveToNext()) returnLines.getOrPut(c.getString(0)) { mutableListOf() } += PurchaseReturnLine(c.getString(1), c.getString(2), c.getInt(3), c.getInt(4))
        }
        val purchaseReturns = mutableListOf<PurchaseReturn>()
        db.rawQuery("SELECT return_id,supplier_id,receipt_id,total_paise,sync_id FROM purchase_returns ORDER BY created_at,return_id", null).use { c ->
            while (c.moveToNext()) purchaseReturns += PurchaseReturn(c.getString(0), c.getString(1), c.getString(2), returnLines[c.getString(0)].orEmpty(), c.getLong(3), c.getString(4))
        }
        val expenses = mutableListOf<BusinessExpense>()
        db.rawQuery("SELECT expense_id,category,amount_paise,method,status,note,drawer_session_id,created_at FROM expenses ORDER BY created_at,expense_id", null).use { c ->
            while (c.moveToNext()) expenses += BusinessExpense(c.getString(0), c.getString(1), c.getInt(2), c.getString(3), c.getString(4), c.getString(5),
                if (c.isNull(6)) null else c.getString(6), c.getLong(7).takeIf { it > 0 })
        }
        val drawerSessions = mutableListOf<CashDrawerSession>()
        db.rawQuery("SELECT session_id,opening_cash_paise,opened_at,closing_cash_paise,closed_at,staff_label,handover_to_label,handover_note,staff_actor_id,handover_to_staff_id,handover_received_at,handover_received_cash_paise FROM cash_drawer_sessions ORDER BY rowid", null).use { c ->
            while (c.moveToNext()) drawerSessions += CashDrawerSession(c.getString(0), c.getInt(1), c.getLong(2), if (c.isNull(3)) null else c.getInt(3), if (c.isNull(4)) null else c.getLong(4), if (c.isNull(5)) null else c.getString(5), if (c.isNull(6)) null else c.getString(6), c.getString(7), if (c.isNull(8)) null else c.getString(8), if (c.isNull(9)) null else c.getString(9), if (c.isNull(10)) null else c.getLong(10), if (c.isNull(11)) null else c.getInt(11))
        }
        val drawerMovements = mutableListOf<CashDrawerMovement>()
        db.rawQuery("SELECT movement_id,session_id,kind,amount_paise,note FROM cash_drawer_movements ORDER BY created_at,movement_id", null).use { c ->
            while (c.moveToNext()) drawerMovements += CashDrawerMovement(c.getString(0), c.getString(1), c.getString(2), c.getInt(3), c.getString(4))
        }
        val closeReviews = mutableListOf<CashClosingReview>()
        db.rawQuery("SELECT review_id,session_id,reviewed_at,note FROM cash_closing_reviews ORDER BY reviewed_at,review_id", null).use { c ->
            while (c.moveToNext()) closeReviews += CashClosingReview(c.getString(0), c.getString(1), c.getLong(2), c.getString(3))
        }
        return CounterSnapshot(next, orders, stock, commandIds, events, movements, credits, repayments, paymentEntries, confirmedStock, suppliers, linkedReceiptList, supplierPayments, purchaseReturns, expenses, drawerSessions, drawerMovements, closeReviews, purchaseOrderDrafts, customerSyncIdentities)
    }

    override fun commit(snapshot: CounterSnapshot, commandId: String, result: String, event: String, actorId: String?, movements: List<StockMovement>, repayments: List<CreditRepayment>, paymentEntries: List<PaymentEntry>, syncPayload: SyncOutboxPayload): Boolean {
        val eventPrefix = "$commandId "
        if (LocalSessionState.authenticated && !event.startsWith(eventPrefix)) {
            throw SecurityException("Authenticated business commits require command-bound audit attribution.")
        }
        LocalBusinessMutationPolicy.requireAllowed(
            LocalSessionState.authenticated,
            LocalSessionState.actorId,
            LocalSessionState.role,
            actorId,
            event.removePrefix(eventPrefix)
        )
        val permit = BusinessWriteBarrier.tryBeginCommit() ?: throw BusinessMaintenanceException()
        try {
        val db = writableDatabase
        assertCipherActive(db)
        db.beginTransaction()
        try {
            db.rawQuery("SELECT 1 FROM command_receipts WHERE command_id=?", arrayOf(commandId)).use { if (it.moveToFirst()) return false }
            // These bounded prototype tables are rewritten under one SQLite transaction. Audit, movement and outbox
            // tables are append-only; only the current order/stock projections are replaced.
            db.delete("order_lines", null, null)
            db.delete("orders", null, null)
            db.delete("stock", null, null)
            db.delete("stock_baseline_confirmations", null, null)
            db.delete("customer_credit", null, null)
            snapshot.orders.forEach { order ->
                db.insertOrThrow("orders", null, ContentValues().apply {
                    put("order_no", order.number); put("fulfillment", order.fulfillment.name); put("payment", order.payment.name)
                    put("paid_paise", order.paidPaise); put("credit_paise", order.creditPaise); put("refunded_paise", order.refundedPaise)
                    put("cash_refunded_paise", order.cashRefundedPaise); put("credit_reversed_paise", order.creditReversedPaise)
                    put("created_at", order.createdAtEpochMs)
                    put("sale_posted", if (order.salePosted) 1 else 0); put("voided", if (order.voided) 1 else 0); put("cancel_reason", order.cancelReason); put("revision", order.revision)
                })
                order.lines.forEach { line -> db.insertOrThrow("order_lines", null, ContentValues().apply {
                    put("order_no", order.number); put("product_id", line.productId); put("name", line.name)
                    put("quantity", line.quantity); put("delivered_quantity", line.deliveredQuantity); put("refunded_quantity", line.refundedQuantity); put("unit_price_paise", line.unitPricePaise)
                    if (line.estimatedUnitCostPaise == null) putNull("estimated_unit_cost_paise") else put("estimated_unit_cost_paise", line.estimatedUnitCostPaise)
                }) }
            }
            snapshot.stock.forEach { (id, quantity) -> db.insertOrThrow("stock", null, ContentValues().apply { put("product_id", id); put("quantity", quantity) }) }
            snapshot.stockConfirmedProductIds.forEach { productId ->
                check(productId in snapshot.stock) { "Confirmed stock must have a stock projection." }
                db.insertOrThrow("stock_baseline_confirmations", null, ContentValues().apply { put("product_id", productId); put("confirmed_at", System.currentTimeMillis()) })
            }
            db.insertWithOnConflict("metadata", null, ContentValues().apply { put("key", "next_order_number"); put("value", snapshot.nextNumber.toString()) }, CipherDatabase.CONFLICT_REPLACE)
            val now = System.currentTimeMillis()
            db.insertOrThrow("command_receipts", null, ContentValues().apply { put("command_id", commandId); put("result", result); put("committed_at", now) })
            db.insertOrThrow("audit_events", null, ContentValues().apply { put("event_id", commandId); put("command_id", commandId); put("event", event); put("created_at", now); put("actor_id", actorId) })
            faultInjector.afterAuditInsert()
            movements.forEach { movement -> db.insertOrThrow("stock_movements", null, ContentValues().apply {
                put("movement_id", movement.movementId); put("command_id", commandId); put("product_id", movement.productId)
                put("quantity_delta", movement.quantityDelta); put("reason", movement.reason); put("order_no", movement.orderNumber); put("created_at", now)
            }) }
            snapshot.customerCredits.forEach { credit -> db.insertOrThrow("customer_credit", null, ContentValues().apply {
                put("credit_id",credit.creditId); put("customer_id",credit.customerId); put("order_no",credit.orderNumber)
                put("amount_paise",credit.amountPaise); put("repaid_paise",credit.repaidPaise); put("reversed_paise",credit.reversedPaise); put("sync_id",credit.syncId)
            }) }
            snapshot.customerSyncIdentities.forEach { (localId, syncId) -> db.insertWithOnConflict("customer_sync_identities", null, ContentValues().apply { put("local_customer_id", localId); put("sync_id", syncId) }, CipherDatabase.CONFLICT_REPLACE) }
            snapshot.suppliers.forEach { supplier -> db.insertWithOnConflict("suppliers", null, ContentValues().apply {
                put("supplier_id", supplier.supplierId); put("name", supplier.name); put("active", 1); put("phone", supplier.phone); put("sync_id", supplier.syncId)
            }, CipherDatabase.CONFLICT_REPLACE) }
            snapshot.purchaseReceipts.forEach { receipt ->
                db.insertWithOnConflict("purchase_receipts", null, ContentValues().apply {
                    put("receipt_id", receipt.receiptId); put("supplier_id", receipt.supplierId); put("total_paise", receipt.totalPaise); put("created_at", now); put("purchase_order_id", receipt.purchaseOrderId); put("supplier_invoice_reference", receipt.supplierInvoiceReference); put("sync_id", receipt.syncId)
                }, CipherDatabase.CONFLICT_IGNORE)
                receipt.lines.forEach { line -> db.insertWithOnConflict("purchase_receipt_lines", null, ContentValues().apply {
                    put("receipt_id", receipt.receiptId); put("product_id", line.productId); put("product_name", line.productName)
                    put("quantity", line.quantity); put("unit_cost_paise", line.unitCostPaise)
                }, CipherDatabase.CONFLICT_IGNORE) }
            }
            snapshot.purchaseOrderDrafts.forEach { purchaseOrder ->
                val values = ContentValues().apply {
                    put("purchase_order_id", purchaseOrder.purchaseOrderId); put("supplier_id", purchaseOrder.supplierId)
                    put("status", purchaseOrder.status.name); put("revision", purchaseOrder.revision); put("created_at", purchaseOrder.createdAtEpochMs)
                }
                if (db.update("purchase_order_drafts", values, "purchase_order_id=?", arrayOf(purchaseOrder.purchaseOrderId)) == 0) {
                    db.insertOrThrow("purchase_order_drafts", null, values)
                }
                purchaseOrder.lines.forEach { line -> db.insertWithOnConflict("purchase_order_draft_lines", null, ContentValues().apply {
                    put("purchase_order_id", purchaseOrder.purchaseOrderId); put("product_id", line.productId); put("product_name", line.productName)
                    put("ordered_quantity", line.orderedQuantity); put("unit_cost_paise", line.unitCostPaise)
                }, CipherDatabase.CONFLICT_IGNORE) }
            }
            snapshot.supplierPayments.forEach { payment -> db.insertWithOnConflict("supplier_payments", null, ContentValues().apply {
                put("payment_id", payment.paymentId); put("supplier_id", payment.supplierId); put("amount_paise", payment.amountPaise); put("sync_id", payment.syncId)
                put("method", payment.method); put("status", payment.status); put("drawer_session_id", payment.drawerSessionId); put("created_at", now)
            }, CipherDatabase.CONFLICT_IGNORE) }
            snapshot.purchaseReturns.forEach { purchaseReturn ->
                db.insertWithOnConflict("purchase_returns", null, ContentValues().apply {
                    put("return_id", purchaseReturn.returnId); put("supplier_id", purchaseReturn.supplierId); put("receipt_id", purchaseReturn.receiptId); put("sync_id", purchaseReturn.syncId)
                    put("total_paise", purchaseReturn.totalPaise); put("created_at", now)
                }, CipherDatabase.CONFLICT_IGNORE)
                purchaseReturn.lines.forEach { line -> db.insertWithOnConflict("purchase_return_lines", null, ContentValues().apply {
                    put("return_id", purchaseReturn.returnId); put("product_id", line.productId); put("product_name", line.productName)
                    put("quantity", line.quantity); put("unit_cost_paise", line.unitCostPaise)
                }, CipherDatabase.CONFLICT_IGNORE) }
            }
            snapshot.expenses.forEach { expense -> db.insertWithOnConflict("expenses", null, ContentValues().apply {
                put("expense_id", expense.expenseId); put("category", expense.category); put("amount_paise", expense.amountPaise)
                put("method", expense.method); put("status", expense.status); put("note", expense.note); put("drawer_session_id", expense.drawerSessionId)
                put("created_at", expense.recordedAtEpochMs ?: 0L)
            }, CipherDatabase.CONFLICT_IGNORE) }
            snapshot.cashDrawerSessions.forEach { session ->
                val values = ContentValues().apply {
                put("session_id", session.sessionId); put("opening_cash_paise", session.openingCashPaise); put("opened_at", session.openedAt)
                put("closing_cash_paise", session.closingCashPaise); put("closed_at", session.closedAt); put("staff_label", session.staffLabel)
                put("handover_to_label", session.handoverToLabel); put("handover_note", session.handoverNote); put("staff_actor_id", session.staffActorId); put("handover_to_staff_id", session.handoverToStaffId)
                put("handover_received_at", session.handoverReceivedAt); put("handover_received_cash_paise", session.handoverReceivedCashPaise)
                }
                val updated = db.update("cash_drawer_sessions", values, "session_id=?", arrayOf(session.sessionId))
                if (updated == 0) db.insertOrThrow("cash_drawer_sessions", null, values)
            }
            snapshot.cashDrawerMovements.forEach { movement -> db.insertWithOnConflict("cash_drawer_movements", null, ContentValues().apply {
                put("movement_id", movement.movementId); put("command_id", commandId); put("session_id", movement.sessionId); put("kind", movement.kind)
                put("amount_paise", movement.amountPaise); put("note", movement.note); put("created_at", now)
            }, CipherDatabase.CONFLICT_IGNORE) }
            snapshot.cashClosingReviews.forEach { review -> db.insertWithOnConflict("cash_closing_reviews", null, ContentValues().apply {
                put("review_id", review.reviewId); put("session_id", review.sessionId); put("reviewed_at", review.reviewedAt); put("note", review.note)
            }, CipherDatabase.CONFLICT_IGNORE) }
            repayments.forEach { repayment -> db.insertOrThrow("credit_repayments", null, ContentValues().apply {
                put("repayment_id",repayment.repaymentId); put("customer_id",repayment.customerId); put("amount_paise",repayment.amountPaise); put("sync_id", repayment.syncId)
            }) }
            paymentEntries.forEach { entry -> db.insertOrThrow("payment_entries", null, ContentValues().apply {
                put("entry_id", entry.entryId); put("order_no", entry.orderNumber); put("customer_id", entry.customerId)
                put("amount_paise", entry.amountPaise); put("method", entry.method); put("status", entry.status); put("kind", entry.kind); put("related_entry_id", entry.relatedEntryId); put("drawer_session_id", entry.drawerSessionId); put("created_at", entry.createdAtEpochMs.takeIf { it > 0L } ?: now); put("sync_id", entry.syncId)
            }) }
            insertSyncOutbox(db, commandId, now, syncPayload)
            db.setTransactionSuccessful()
            return true
        } finally {
            db.endTransaction()
        }
        } finally { permit.close() }
    }

    companion object {
        internal const val DATABASE_NAME = "chai_tapri_counter.db"
        internal const val DATABASE_VERSION = 34
        internal const val STORE_IDENTITY_KEY = "shop_identity"

        internal fun locallyBoundShopIdentity(context: Context, databaseName: String, keyNamespace: String): String? =
            context.applicationContext.getSharedPreferences("chai_tapri_shop_identity_guard", Context.MODE_PRIVATE)
                .getString("$keyNamespace:$databaseName", null)

        internal fun rawKeyPassphrase(key: ByteArray): ByteArray {
            val hex = key.joinToString("") { "%02x".format(it) }
            return "x'$hex'".toByteArray(Charsets.US_ASCII)
        }

        private fun provisionPassphrase(context: Context): ByteArray {
            System.loadLibrary("sqlcipher")
            return SecureDatabaseKeyManager(context, context.getDatabasePath(DATABASE_NAME)).getOrCreatePassphrase()
        }
    }

    private fun createProductCatalog(db: CipherDatabase) {
        db.execSQL("CREATE TABLE product_catalog(product_id TEXT PRIMARY KEY,name TEXT NOT NULL,category TEXT NOT NULL,unit_price_paise INTEGER NOT NULL CHECK(unit_price_paise >= 0),active INTEGER NOT NULL,favorite INTEGER NOT NULL,price_confirmed INTEGER NOT NULL,aliases TEXT NOT NULL DEFAULT '',reorder_threshold INTEGER CHECK(reorder_threshold IS NULL OR reorder_threshold BETWEEN 1 AND 100000),estimated_unit_cost_paise INTEGER CHECK(estimated_unit_cost_paise IS NULL OR estimated_unit_cost_paise BETWEEN 0 AND 10000000),scan_code TEXT,unit_label TEXT NOT NULL DEFAULT 'units',purchase_unit_label TEXT NOT NULL DEFAULT 'units',base_units_per_purchase_unit INTEGER NOT NULL DEFAULT 1 CHECK(base_units_per_purchase_unit BETWEEN 1 AND 100000))")
        db.execSQL("CREATE UNIQUE INDEX product_catalog_scan_code_unique ON product_catalog(scan_code COLLATE NOCASE) WHERE scan_code IS NOT NULL AND scan_code <> ''")
    }

    /** Builds an audit row with the current authenticated operator when available. */
    private fun auditValues(
        eventId: String,
        commandId: String,
        event: String,
        createdAt: Long,
        actorId: String? = LocalSessionState.actorId
    ): ContentValues = ContentValues().apply {
        put("event_id", eventId)
        put("command_id", commandId)
        put("event", event)
        put("created_at", createdAt)
        if (actorId == null) putNull("actor_id") else put("actor_id", actorId)
    }

    private fun createProcurementTables(db: CipherDatabase) {
        db.execSQL("CREATE TABLE suppliers(supplier_id TEXT PRIMARY KEY,name TEXT NOT NULL,active INTEGER NOT NULL DEFAULT 1,phone TEXT,sync_id TEXT NOT NULL DEFAULT '')")
        db.execSQL("CREATE TABLE purchase_receipts(receipt_id TEXT PRIMARY KEY,supplier_id TEXT NOT NULL,total_paise INTEGER NOT NULL CHECK(total_paise >= 0),created_at INTEGER NOT NULL,purchase_order_id TEXT,supplier_invoice_reference TEXT,sync_id TEXT NOT NULL DEFAULT '',FOREIGN KEY(supplier_id) REFERENCES suppliers(supplier_id))")
        db.execSQL("CREATE TABLE purchase_receipt_lines(receipt_id TEXT NOT NULL,product_id TEXT NOT NULL,product_name TEXT NOT NULL,quantity INTEGER NOT NULL CHECK(quantity > 0),unit_cost_paise INTEGER NOT NULL CHECK(unit_cost_paise > 0),PRIMARY KEY(receipt_id,product_id),FOREIGN KEY(receipt_id) REFERENCES purchase_receipts(receipt_id))")
        db.execSQL("CREATE TABLE supplier_payments(payment_id TEXT PRIMARY KEY,supplier_id TEXT NOT NULL,amount_paise INTEGER NOT NULL CHECK(amount_paise > 0),method TEXT NOT NULL,status TEXT NOT NULL,created_at INTEGER NOT NULL,sync_id TEXT NOT NULL DEFAULT '',FOREIGN KEY(supplier_id) REFERENCES suppliers(supplier_id))")
    }

    private fun createPurchaseOrderDraftTables(db: CipherDatabase) {
        // Legacy migration tests and interrupted upgrades can re-enter this helper after
        // a schema version has been rewound. Keep this additive migration idempotent.
        db.execSQL("CREATE TABLE IF NOT EXISTS purchase_order_drafts(purchase_order_id TEXT PRIMARY KEY,supplier_id TEXT NOT NULL,status TEXT NOT NULL CHECK(status IN ('DRAFT','SENT','CANCELLED')),revision INTEGER NOT NULL CHECK(revision >= 0),created_at INTEGER NOT NULL,FOREIGN KEY(supplier_id) REFERENCES suppliers(supplier_id))")
        db.execSQL("CREATE TABLE IF NOT EXISTS purchase_order_draft_lines(purchase_order_id TEXT NOT NULL,product_id TEXT NOT NULL,product_name TEXT NOT NULL,ordered_quantity INTEGER NOT NULL CHECK(ordered_quantity > 0),unit_cost_paise INTEGER NOT NULL CHECK(unit_cost_paise > 0),PRIMARY KEY(purchase_order_id,product_id),FOREIGN KEY(purchase_order_id) REFERENCES purchase_order_drafts(purchase_order_id))")
    }

    private fun createPurchaseReturnTables(db: CipherDatabase) {
        db.execSQL("CREATE TABLE purchase_returns(return_id TEXT PRIMARY KEY,supplier_id TEXT NOT NULL,receipt_id TEXT NOT NULL,total_paise INTEGER NOT NULL CHECK(total_paise > 0),created_at INTEGER NOT NULL,sync_id TEXT NOT NULL DEFAULT '',FOREIGN KEY(supplier_id) REFERENCES suppliers(supplier_id),FOREIGN KEY(receipt_id) REFERENCES purchase_receipts(receipt_id))")
        db.execSQL("CREATE TABLE purchase_return_lines(return_id TEXT NOT NULL,product_id TEXT NOT NULL,product_name TEXT NOT NULL,quantity INTEGER NOT NULL CHECK(quantity > 0),unit_cost_paise INTEGER NOT NULL CHECK(unit_cost_paise > 0),PRIMARY KEY(return_id,product_id),FOREIGN KEY(return_id) REFERENCES purchase_returns(return_id))")
    }

    private fun createExpenseTable(db: CipherDatabase) {
        db.execSQL("CREATE TABLE expenses(expense_id TEXT PRIMARY KEY,category TEXT NOT NULL,amount_paise INTEGER NOT NULL CHECK(amount_paise > 0),method TEXT NOT NULL,status TEXT NOT NULL,note TEXT NOT NULL,created_at INTEGER NOT NULL)")
    }

    private fun createCashDrawerTables(db: CipherDatabase) {
        db.execSQL("CREATE TABLE cash_drawer_sessions(session_id TEXT PRIMARY KEY,opening_cash_paise INTEGER NOT NULL CHECK(opening_cash_paise >= 0),opened_at INTEGER NOT NULL,closing_cash_paise INTEGER,closed_at INTEGER)")
        db.execSQL("CREATE TABLE cash_drawer_movements(movement_id TEXT PRIMARY KEY,command_id TEXT NOT NULL,session_id TEXT NOT NULL,kind TEXT NOT NULL CHECK(kind IN ('cash_in','cash_out')),amount_paise INTEGER NOT NULL CHECK(amount_paise > 0),note TEXT NOT NULL,created_at INTEGER NOT NULL,FOREIGN KEY(session_id) REFERENCES cash_drawer_sessions(session_id))")
    }

    private fun addStaffShiftColumns(db: CipherDatabase) {
        db.execSQL("ALTER TABLE cash_drawer_sessions ADD COLUMN staff_label TEXT")
        db.execSQL("ALTER TABLE cash_drawer_sessions ADD COLUMN handover_to_label TEXT")
        db.execSQL("ALTER TABLE cash_drawer_sessions ADD COLUMN handover_note TEXT NOT NULL DEFAULT ''")
        if (!hasColumn(db, "cash_drawer_sessions", "staff_actor_id")) db.execSQL("ALTER TABLE cash_drawer_sessions ADD COLUMN staff_actor_id TEXT")
        if (!hasColumn(db, "cash_drawer_sessions", "handover_to_staff_id")) db.execSQL("ALTER TABLE cash_drawer_sessions ADD COLUMN handover_to_staff_id TEXT")
        if (!hasColumn(db, "cash_drawer_sessions", "handover_received_at")) db.execSQL("ALTER TABLE cash_drawer_sessions ADD COLUMN handover_received_at INTEGER")
        if (!hasColumn(db, "cash_drawer_sessions", "handover_received_cash_paise")) db.execSQL("ALTER TABLE cash_drawer_sessions ADD COLUMN handover_received_cash_paise INTEGER CHECK(handover_received_cash_paise IS NULL OR handover_received_cash_paise BETWEEN 0 AND 100000000)")
    }

    private fun createCashClosingReviewTable(db: CipherDatabase) {
        db.execSQL("CREATE TABLE cash_closing_reviews(review_id TEXT PRIMARY KEY,session_id TEXT NOT NULL UNIQUE,reviewed_at INTEGER NOT NULL,note TEXT NOT NULL,FOREIGN KEY(session_id) REFERENCES cash_drawer_sessions(session_id))")
    }

    private fun seedProductCatalog(db: CipherDatabase) {
        listOf(TapriProduct("cutting_chai", "Cutting Chai", "Chai", 1000), TapriProduct("chai", "Regular Chai", "Chai", 1500), TapriProduct("samosa", "Samosa", "Snacks", 2000), TapriProduct("coffee", "Coffee", "Coffee", 2500)).forEach { p ->
            db.insertWithOnConflict("product_catalog", null, ContentValues().apply {
                put("product_id", p.productId); put("name", p.name); put("category", p.category); put("unit_price_paise", p.unitPricePaise)
                put("active", 1); put("favorite", 0); put("price_confirmed", 0); put("aliases", "")
            }, CipherDatabase.CONFLICT_IGNORE)
        }
    }
}
