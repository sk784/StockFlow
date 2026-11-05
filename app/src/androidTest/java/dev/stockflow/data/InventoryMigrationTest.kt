package dev.stockflow.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InventoryMigrationTest {
    @Test
    fun migrationPreservesVersionOneCountsAndCreatesQueue() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-test.db"
        context.deleteDatabase(name)
        try {
            SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { old ->
                old.execSQL("CREATE TABLE tasks (id INTEGER NOT NULL PRIMARY KEY, title TEXT NOT NULL, location TEXT NOT NULL)")
                old.execSQL("CREATE TABLE items (id INTEGER NOT NULL PRIMARY KEY, taskId INTEGER NOT NULL, name TEXT NOT NULL, barcode TEXT NOT NULL, expectedQuantity INTEGER NOT NULL, countedQuantity INTEGER, FOREIGN KEY(taskId) REFERENCES tasks(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                old.execSQL("CREATE INDEX index_items_taskId ON items(taskId)")
                old.execSQL("INSERT INTO tasks VALUES (1, 'Task', 'Location')")
                old.execSQL("INSERT INTO items VALUES (1, 1, 'Item', '123', 10, 7)")
                old.version = 1
            }
            val migrated = Room.databaseBuilder(context, InventoryDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
            try {
                assertEquals(7, migrated.inventoryDao().observeItems(1).first().single().countedQuantity)
                assertEquals(1, migrated.syncDao().observeStatus().first().pending)
                assertEquals(7, migrated.syncDao().next()!!.quantity)
                assertNull(migrated.syncDao().next()!!.baseRevision)
                assertEquals(0L, migrated.inventoryDao().observeItems(1).first().single().serverRevision)
            } finally {
                migrated.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }
}
