package cl.bovedawilson.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cl.bovedawilson.data.local.db.VaultDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VaultDatabaseTest {

    private val dbName = "test-vault.db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        VaultDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    private lateinit var db: VaultDatabase
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(dbName)
    }

    @After
    fun teardown() {
        if (::db.isInitialized && db.isOpen) {
            db.close()
        }
        context.deleteDatabase(dbName)
    }

    @Test
    fun testSchemaHasNoContentColumns() {
        // Build database and run PRAGMA table_info on all tables
        db = Room.databaseBuilder(context, VaultDatabase::class.java, dbName).build()
        val c = db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type='table'")
        val tables = mutableListOf<String>()
        while (c.moveToNext()) {
            tables.add(c.getString(0))
        }
        c.close()

        val forbiddenWords = listOf("title", "text", "body", "content", "note", "password", "secret", "plaintext")
        val allowedProtectedMetadata = setOf(
            "ciphertext",
            "pendingremoteciphertext",
            "passwordsalt",
            "passwordwrappedvdek",
            "passwordwrapepoch"
        )

        var inspectedColumns = 0
        tables.forEach { table ->
            if (table == "android_metadata" || table == "room_master_table" || table.startsWith("sqlite_")) return@forEach

            val quotedTable = table.replace("`", "``")
            val c2 = db.openHelper.readableDatabase.query("PRAGMA table_info(`$quotedTable`)")
            var tableColumns = 0
            while (c2.moveToNext()) {
                tableColumns++
                inspectedColumns++
                val colName = c2.getString(1).lowercase()
                forbiddenWords.forEach { word ->
                    assertFalse(
                        "Table $table contains forbidden column name $colName which looks like plaintext content.",
                        colName !in allowedProtectedMetadata && colName.contains(word)
                    )
                }
            }
            c2.close()
            assertTrue("PRAGMA did not inspect columns for table $table", tableColumns > 0)
        }
        assertTrue("No application table columns were inspected", inspectedColumns > 0)
    }

    @Test
    fun testExportedVersionOneSchemaCanBeCreated() {
        val schemaDatabase = helper.createDatabase(dbName, 1)
        assertEquals(1, schemaDatabase.version)
        schemaDatabase.close()
    }
}
