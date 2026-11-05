package dev.stockflow.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS pending_operations (
                sequence INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                operationId TEXT NOT NULL,
                itemId INTEGER NOT NULL,
                quantity INTEGER NOT NULL,
                attempts INTEGER NOT NULL,
                blocked INTEGER NOT NULL,
                lastError TEXT
            )
        """.trimIndent())
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_pending_operations_operationId ON pending_operations (operationId)")
        // Counts saved before sync existed must not silently remain only on this device.
        db.execSQL("""
            INSERT INTO pending_operations (operationId, itemId, quantity, attempts, blocked, lastError)
            SELECT lower(hex(randomblob(16))), id, countedQuantity, 0, 0, NULL
            FROM items WHERE countedQuantity IS NOT NULL ORDER BY id
        """.trimIndent())
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE items ADD COLUMN serverRevision INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE pending_operations ADD COLUMN baseRevision INTEGER")
        db.execSQL("ALTER TABLE pending_operations ADD COLUMN conflictRevision INTEGER")
        db.execSQL("ALTER TABLE pending_operations ADD COLUMN conflictQuantity INTEGER")
    }
}
