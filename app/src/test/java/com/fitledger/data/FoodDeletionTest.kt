package com.fitledger.data

import androidx.sqlite.db.SupportSQLiteDatabase
import com.fitledger.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate

class FoodDeletionTest {
    private val day = LocalDate.of(2026, 10, 13).toEpochDay()
    private val food = Food(7, "土豆泥", "g", 100.0, Nutrition(116.0, 26.0, 2.6, 0.3), 10.0, 500.0, true, "KJ")
    private fun fixture() = Backup(
        bodies = listOf(BodyRecord(day, 72.4, 15.2, 61.3952, 58.1)),
        targets = listOf(DailyTarget(day, Nutrition(2000.0, 250.0, 120.0, 57.777777), PlanCodec.encode(Plan()))),
        foods = listOf(food.copy(isDeleted = true)),
        intakes = listOf(Intake(9, day, 7, "历史食品名", "g", 200.0, Nutrition(232.0, 52.0, 5.2, 0.6))),
        preferences = Preferences(energyUnit = EnergyUnit.KJ, weightInputUnit = WeightUnit.JIN, weightFoodShortcut = false)
    )

    @Test fun deletedFoodBackupKeepsIdentityNutritionAndIntakeSnapshots() {
        val original = fixture()
        val restored = BackupCodec.decode(BackupCodec.encode(original))
        assertEquals(original, restored)
        assertTrue(restored.foods.single().isDeleted)
        assertTrue(restored.foods.single().allowed)
        assertEquals(original.intakes, restored.intakes)
    }

    @Test fun everyOldBackupVersionDefaultsMissingDeletionFlagToFalseAndCanReExport() {
        val original = fixture().copy(foods = listOf(food))
        for (version in 1..6) {
            val root = JSONObject(BackupCodec.encode(original))
            root.put("backupVersion", version).put("databaseVersion", 4)
            editPayload(root) { it.getJSONArray("foods").getJSONObject(0).remove("isDeleted") }
            val restored = BackupCodec.decode(root.toString())
            assertFalse(restored.foods.single().isDeleted)
            assertEquals(original.intakes, restored.intakes)
            assertEquals(restored, BackupCodec.decode(BackupCodec.encode(restored)))
        }
    }

    @Test fun deletedFoodsCannotBeEncodedOrDecodedUnderOldVersionMetadata() {
        val original = fixture()
        assertTrue(runCatching { BackupCodec.encode(original.copy(backupVersion = 6)) }.isFailure)
        assertTrue(runCatching { BackupCodec.encode(original.copy(databaseVersion = 4)) }.isFailure)
        val root = JSONObject(BackupCodec.encode(original))
        root.put("backupVersion", 6)
        assertTrue(runCatching { BackupCodec.decode(root.toString()) }.isFailure)
        root.put("backupVersion", 7).put("databaseVersion", 4)
        assertTrue(runCatching { BackupCodec.decode(root.toString()) }.isFailure)
    }

    @Test fun versionSevenRequiresExplicitBooleanDeletionStateBeforeRestore() {
        for (invalid in listOf(null, "false", 0, JSONObject.NULL)) {
            val root = JSONObject(BackupCodec.encode(fixture()))
            editPayload(root) {
                val row = it.getJSONArray("foods").getJSONObject(0)
                if (invalid == null) row.remove("isDeleted") else row.put("isDeleted", invalid)
            }
            assertTrue("Invalid deletion state: $invalid", runCatching { BackupCodec.decode(root.toString()) }.isFailure)
        }
    }

    @Test fun deletedFoodIsNeitherRecommendedNorAcceptedAsLockedFood() {
        val deleted = food.copy(isDeleted = true).candidate()
        assertFalse(deleted.allowed)
        assertTrue(Recommender.recommend(listOf(deleted), food.nutrition, emptyMap()).quantities.isEmpty())
        assertTrue(runCatching { Recommender.recommend(listOf(deleted), food.nutrition, mapOf(food.id to 100.0)) }.isFailure)
        assertTrue(food.candidate().allowed)
    }

    @Test fun v4ToV5RetainsAllOldValuesAndMatchesFreshSchema() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            createSchema(c, 4)
            c.createStatement().use {
                it.execute("INSERT INTO bodies VALUES (20000,72.4,15.2,61.3952,58.1,21,175,'MALE',12000,1.55,1700,2635)")
                it.execute("INSERT INTO foods VALUES (7,'rice','g',100,116,26,2.6,0.3,10,500,1,'KJ')")
                it.execute("INSERT INTO foods VALUES (8,'milk','份',1,120,10,6,5,1,2,0,'KCAL')")
                it.execute("INSERT INTO targets VALUES (20000,2000,250,120,57.77,'original-plan')")
                it.execute("INSERT INTO intakes VALUES (9,20000,7,'old-rice-name','g',200,232,52,5.2,0.6)")
                it.execute("INSERT INTO exercises VALUES (3,20000,'MANUAL',NULL,NULL,NULL,300,1)")
                it.execute("INSERT INTO pending_settings VALUES (1,'original-settings')")
            }
            val tables = listOf("bodies", "foods", "targets", "intakes", "exercises", "pending_settings")
            val before = tables.associateWith { rows(c, it) }
            Migrations.MIGRATION_4_5.migrate(adapter(c))
            before.forEach { (name, records) ->
                assertEquals(name, records, rows(c, name).map { it.filterKeys { key -> key != "isDeleted" } })
            }
            assertEquals(listOf("0", "0"), rows(c, "foods").map { it["isDeleted"] })
            verifySchemaAndIntegrity(c, tables)
        }
    }

    @Test fun fullV1ToV5MigrationKeepsHistoryWithNullEnergyAndVisibleFoods() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            createSchema(c, 1)
            c.createStatement().use {
                it.execute("INSERT INTO bodies VALUES (20000,72.4,15.2,61.3952,58.1)")
                it.execute("INSERT INTO foods VALUES (7,'rice','g',100,116,26,2.6,0.3,10,500,1)")
                it.execute("INSERT INTO targets VALUES (20000,2000,250,120,57.77,'original-plan')")
                it.execute("INSERT INTO intakes VALUES (9,20000,7,'old-rice-name','g',200,232,52,5.2,0.6)")
            }
            val before = listOf("bodies", "foods", "targets", "intakes").associateWith { rows(c, it) }
            Migrations.ALL.filter {it.endVersion<=5}.forEach { it.migrate(adapter(c)) }
            before.forEach { (name, records) ->
                assertEquals(name, records, rows(c, name).map { it.filterKeys { key -> key != "isDeleted" && key != "energyInputUnit" && !key.startsWith("energy_") } })
            }
            assertEquals("0", rows(c, "foods").single()["isDeleted"])
            assertEquals("KCAL", rows(c, "foods").single()["energyInputUnit"])
            assertTrue(rows(c, "bodies").single().filterKeys { it.startsWith("energy_") }.values.all { it == null })
            assertTrue(rows(c, "exercises").isEmpty())
            assertTrue(rows(c, "pending_settings").isEmpty())
            verifySchemaAndIntegrity(c, before.keys.toList() + listOf("exercises", "pending_settings"))
        }
    }

    private fun editPayload(root: JSONObject, edit: (JSONObject) -> Unit) {
        val data = JSONObject(root.getString("payload"))
        edit(data)
        val payload = data.toString()
        root.put("payload", payload).put("sha256", MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) })
    }

    private fun adapter(c: Connection) = Proxy.newProxyInstance(
        SupportSQLiteDatabase::class.java.classLoader, arrayOf(SupportSQLiteDatabase::class.java)
    ) { _, method, args ->
        if (method.name == "execSQL") { c.createStatement().use { it.execute(args!![0] as String) }; null }
        else throw UnsupportedOperationException(method.name)
    } as SupportSQLiteDatabase

    private fun createSchema(c: Connection, version: Int) {
        val entities = JSONObject(File("schemas/com.fitledger.data.LedgerDatabase/$version.json").readText())
            .getJSONObject("database").getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            c.createStatement().use { it.execute(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName"))) }
        }
    }

    private fun rows(c: Connection, table: String): List<Map<String, String?>> = c.createStatement().use { s ->
        s.executeQuery("SELECT * FROM $table ORDER BY rowid").use { r ->
            val result = mutableListOf<Map<String, String?>>()
            while (r.next()) result += (1..r.metaData.columnCount).associate { r.metaData.getColumnName(it) to r.getString(it) }
            result
        }
    }

    private fun columns(c: Connection, table: String): List<List<String?>> = c.createStatement().use { s ->
        s.executeQuery("PRAGMA table_info($table)").use { r ->
            val result = mutableListOf<List<String?>>()
            while (r.next()) result += listOf("name", "type", "notnull", "dflt_value", "pk").map { r.getString(it) }
            result.sortedBy { it.first() }
        }
    }

    private fun verifySchemaAndIntegrity(c: Connection, tables: List<String>) {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { fresh ->
            createSchema(fresh, 5)
            tables.forEach { assertEquals(it, columns(fresh, it), columns(c, it)) }
        }
        c.createStatement().use { it.executeQuery("PRAGMA integrity_check").use { r -> assertTrue(r.next()); assertEquals("ok", r.getString(1)) } }
    }
}
