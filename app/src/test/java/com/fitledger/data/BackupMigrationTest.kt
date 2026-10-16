package com.fitledger.data

import androidx.sqlite.db.SupportSQLiteDatabase
import com.fitledger.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import java.time.LocalDate
import java.security.MessageDigest

class BackupMigrationTest {
    private val day=LocalDate.of(2026,9,18).toEpochDay()
    private fun fixture()=Backup(
        bodies=listOf(BodyRecord(day,72.4,15.2,61.3952,58.1)),
        targets=listOf(DailyTarget(day,Nutrition(2000.0,250.0,120.0,57.777777),PlanCodec.encode(Plan()))),
        foods=listOf(Food(7,"米饭","g",100.0,Nutrition(100.0,20.0,4.0,0.4),10.0,500.0,true,"KJ")),
        intakes=listOf(Intake(9,day,7,"原食品名称","g",200.0,Nutrition(200.0,40.0,8.0,0.8))),
        preferences=Preferences(energyUnit=EnergyUnit.KJ,reminder=true,hour=7,minute=30,nextPlan=Plan(goal="减脂"),effectiveFrom=day+1))
    @Test fun backupRoundTripPreservesEverySnapshotAndPreference() {
        val b=fixture();assertEquals(b,BackupCodec.decode(BackupCodec.encode(b)))
    }
    @Test fun energyPrivacyAndRatioBackupRoundTrip() {
        val b=fixture();val profile=BodyProfile(Sex.MALE,175.0,LocalDate.of(2005,5,20).toEpochDay(),1.55)
        val body=b.bodies.single().copy(energy=EnergyExpenditureCalculator.snapshot(72.4,profile,day))
        val p=b.preferences.copy(profile=profile,privacy=true,plan=Plan(mode=PlanMode.RATIO,ratio=MacroRatio(4.5,3.5,2.0),intakeMode=IntakeMode.EXPENDITURE_ADJUSTMENT,energyAdjustment=-500.0))
        val backup=b.copy(bodies=listOf(body),preferences=p)
        assertEquals(backup,BackupCodec.decode(BackupCodec.encode(backup)))
        assertEquals(72.4,BackupCodec.decode(BackupCodec.encode(backup)).bodies.single().weight,0.0)
    }
    @Test fun v2BackupDoesNotInventHistoricalEnergyOrProfile() {
        val b=fixture();val root=JSONObject(BackupCodec.encode(b));val data=JSONObject(root.getString("payload"))
        val oldPlan=JSONObject(data.getJSONArray("targets").getJSONObject(0).getString("planSnapshot"))
        listOf("intakeMode","fixedIntakeKcal","energyAdjustment","ratio").forEach {oldPlan.remove(it);data.getJSONObject("preferences").getJSONObject("plan").remove(it)}
        data.getJSONArray("targets").getJSONObject(0).put("planSnapshot",oldPlan.toString())
        data.getJSONObject("preferences").remove("privacy");data.getJSONObject("preferences").remove("profile")
        val payload=data.toString();root.put("payload",payload).put("backupVersion",2).put("databaseVersion",2)
        root.put("sha256",MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it.toInt() and 255)})
        val restored=BackupCodec.decode(root.toString())
        assertNull(restored.bodies.single().energy);assertNull(restored.preferences.profile);assertFalse(restored.preferences.privacy)
        assertEquals(EnergyUnit.KJ,restored.preferences.energyUnit);assertEquals(oldPlan.toString(),restored.targets.single().planSnapshot)
        assertEquals(b.intakes,restored.intakes);assertEquals(PlanMode.FIXED,restored.preferences.plan.mode)
    }
    @Test fun oldKcalBackupDefaultsWithoutRecalculatingHistory() {
        val root=JSONObject(BackupCodec.encode(fixture()))
        root.put("backupVersion",1);root.put("databaseVersion",1)
        val data=JSONObject(root.getString("payload"))
        data.getJSONArray("foods").getJSONObject(0).remove("energyInputUnit")
        data.getJSONObject("preferences").remove("energyUnit")
        root.remove("payload");root.remove("sha256");root.put("data",data)
        val restored=BackupCodec.decode(root.toString())
        assertEquals("KCAL",restored.foods.single().energyInputUnit)
        assertEquals(EnergyUnit.KCAL,restored.preferences.energyUnit)
        assertEquals(fixture().targets,restored.targets);assertEquals(fixture().intakes,restored.intakes)
    }
    @Test fun rejectsDamagedChecksum() {
        val root=JSONObject(BackupCodec.encode(fixture()));root.put("payload",root.getString("payload")+" ")
        assertTrue(runCatching {BackupCodec.decode(root.toString())}.isFailure)
    }
    @Test fun rejectsFutureVersion() {
        val root=JSONObject(BackupCodec.encode(fixture()));root.put("databaseVersion",99)
        assertTrue(runCatching {BackupCodec.decode(root.toString())}.isFailure)
    }
    @Test fun rejectsDuplicateIdsAndOrphanedHistory() {
        val b=fixture()
        assertTrue(runCatching {b.copy(foods=b.foods+b.foods).validate()}.isFailure)
        assertTrue(runCatching {b.copy(bodies=emptyList()).validate()}.isFailure)
    }
    @Test fun plannedStartDoesNotChangeTodayEvenBeforeWeighing() {
        val original=Plan(goal="维持");val replacement=Plan(goal="增肌")
        val p=Preferences(plan=original,nextPlan=replacement,effectiveFrom=day+1)
        assertEquals(original,p.planFor(day));assertEquals(replacement,p.planFor(day+1));assertEquals(replacement,p.planFor(day+100))
    }
    @Test fun exerciseBackupRoundTripAndLegacyMissingExercise() {
        val b=fixture().copy(exercises=listOf(Exercise(1,day,"MANUAL",null,null,null,300.0,true),Exercise(2,day,"HILL",30.0,7.0,70.0,210.0,false)))
        assertEquals(b,BackupCodec.decode(BackupCodec.encode(b)))
        val root=JSONObject(BackupCodec.encode(fixture()));val data=JSONObject(root.getString("payload"));data.remove("exercises")
        val payload=data.toString();root.put("payload",payload).put("backupVersion",4).put("databaseVersion",3)
        root.put("sha256",MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it.toInt() and 255)})
        val old=BackupCodec.decode(root.toString());assertTrue(old.exercises.isEmpty());assertEquals(fixture().intakes,old.intakes)
        assertTrue(runCatching {b.copy(exercises=listOf(b.exercises.first().copy(kcal=Double.NaN))).validate()}.isFailure)
    }
    @Test fun v3ToV4PreservesEveryOldTableAndEnergySnapshot() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use {c->
            createSchema(c,3)
            c.createStatement().use {
                it.execute("INSERT INTO bodies VALUES (20000,72.4,15.2,61.3952,58.1,21,175,'MALE',12000,1.55,1700,2635)")
                it.execute("INSERT INTO foods VALUES (7,'rice','g',100,116,26,2.6,0.3,10,500,1,'KJ')")
                it.execute("INSERT INTO targets VALUES (20000,2000,250,120,57.77,'original-plan')")
                it.execute("INSERT INTO intakes VALUES (9,20000,7,'rice','g',200,232,52,5.2,0.6)")
                it.execute("INSERT INTO pending_settings VALUES (1,'original-settings')")
            }
            val names=listOf("bodies","foods","targets","intakes","pending_settings")
            val before=names.associateWith {rows(c,it)}
            val adapter=Proxy.newProxyInstance(SupportSQLiteDatabase::class.java.classLoader,arrayOf(SupportSQLiteDatabase::class.java)) {_,m,args->
                if(m.name=="execSQL") {c.createStatement().use {it.execute(args!![0] as String)};null} else throw UnsupportedOperationException(m.name)
            } as SupportSQLiteDatabase
            Migrations.MIGRATION_3_4.migrate(adapter)
            before.forEach {(name,records)->assertEquals(records,rows(c,name))}
            assertTrue(rows(c,"exercises").isEmpty())
            DriverManager.getConnection("jdbc:sqlite::memory:").use {fresh->createSchema(fresh,4);(names+"exercises").forEach {assertEquals(columns(fresh,it).sortedBy {it.first()},columns(c,it).sortedBy {it.first()})}}
        }
    }
    @Test fun deficitPlanBackupRoundTripAndLegacyPlanDefaults() {
        val p=Plan(useExpenditureBudget=true,deficitKcal=500.0,fat=com.fitledger.domain.Rule(Basis.REMAINDER))
        val b=fixture().copy(preferences=fixture().preferences.copy(plan=p))
        val restored=BackupCodec.decode(BackupCodec.encode(b));assertEquals(b,restored)
        val legacy=JSONObject(PlanCodec.encode(p));legacy.remove("useExpenditureBudget");legacy.remove("deficitKcal")
        legacy.put("intakeMode","EXPENDITURE_ADJUSTMENT");legacy.put("energyAdjustment",-900)
        val decoded=PlanCodec.decode(legacy.toString())
        assertFalse(decoded.useExpenditureBudget)
        assertEquals(2000.0,TargetCalculator.calculate(decoded,Body(70.0,20.0,45.0),day,estimatedExpenditure=4000.0).kcal,1e-8)
    }
    private fun schema(version:Int)=JSONObject(File("schemas/com.fitledger.data.LedgerDatabase/$version.json").readText()).getJSONObject("database")
    private fun createSchema(connection:Connection,version:Int) {
        val entities=schema(version).getJSONArray("entities")
        for(i in 0 until entities.length()) {
            val entity=entities.getJSONObject(i)
            connection.createStatement().use {it.execute(entity.getString("createSql").replace("\${TABLE_NAME}",entity.getString("tableName")))}
        }
    }
    private fun rows(c:Connection,table:String):List<Map<String,String?>> = c.createStatement().use {s ->
        s.executeQuery("SELECT * FROM $table").use {r ->
            val out=mutableListOf<Map<String,String?>>()
            while(r.next()) out+= (1..r.metaData.columnCount).associate {r.metaData.getColumnName(it) to r.getString(it)}
            out
        }
    }
    private fun columns(c:Connection,table:String):List<List<String?>> = c.createStatement().use {s ->
        s.executeQuery("PRAGMA table_info($table)").use {r ->
            val out=mutableListOf<List<String?>>()
            while(r.next()) out+=listOf("name","type","notnull","dflt_value","pk").map {r.getString(it)}
            out
        }
    }
    @Test fun actualMigrationSqlPreservesV1RowsAndMatchesExportedV2Schema() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use {old ->
            createSchema(old,1)
            old.createStatement().use {
                it.execute("INSERT INTO bodies VALUES (20000,72.4,15.2,61.3952,58.1)")
                it.execute("INSERT INTO foods (id,name,unit,basisAmount,kcal,carbs,protein,fat,step,maximum,allowed) VALUES (7,'米饭','g',100,100,20,4,0.4,10,500,1)")
                it.execute("INSERT INTO intakes (id,day,foodId,name,unit,quantity,kcal,carbs,protein,fat) VALUES (9,20000,7,'原食品名称','g',200,200,40,8,0.8)")
            }
            old.prepareStatement("INSERT INTO targets (day,kcal,carbs,protein,fat,planSnapshot) VALUES (?,?,?,?,?,?)").use {
                it.setLong(1,20000);it.setDouble(2,2000.0);it.setDouble(3,250.0);it.setDouble(4,120.0);it.setDouble(5,57.777777);it.setString(6,PlanCodec.encode(Plan()));it.execute()
            }
            val before=listOf("bodies","foods","intakes","targets").associateWith {rows(old,it)}
            val adapter=Proxy.newProxyInstance(SupportSQLiteDatabase::class.java.classLoader,arrayOf(SupportSQLiteDatabase::class.java)) {_,method,args ->
                if(method.name=="execSQL") {old.createStatement().use {it.execute(args!![0] as String)};null} else throw UnsupportedOperationException(method.name)
            } as SupportSQLiteDatabase
            Migrations.MIGRATION_1_2.migrate(adapter)
            before.forEach { (table,records) -> assertEquals(records,rows(old,table).map {it.filterKeys {key->key!="energyInputUnit"}}) }
            assertEquals("KCAL",rows(old,"foods").single()["energyInputUnit"])
            assertTrue(rows(old,"pending_settings").isEmpty())
            DriverManager.getConnection("jdbc:sqlite::memory:").use {fresh ->
                createSchema(fresh,2)
                listOf("bodies","foods","intakes","targets","pending_settings").forEach {assertEquals(it,columns(fresh,it).sortedBy {c->c.first()},columns(old,it).sortedBy {c->c.first()})}
            }
            Migrations.MIGRATION_2_3.migrate(adapter)
            before.forEach { (table,records) -> assertEquals(records,rows(old,table).map {it.filterKeys {key->key!="energyInputUnit" && !key.startsWith("energy_")}}) }
            assertTrue(rows(old,"bodies").single().filterKeys {it.startsWith("energy_")}.values.all {it==null})
            DriverManager.getConnection("jdbc:sqlite::memory:").use {fresh ->
                createSchema(fresh,3)
                listOf("bodies","foods","intakes","targets","pending_settings").forEach {assertEquals(it,columns(fresh,it).sortedBy {c->c.first()},columns(old,it).sortedBy {c->c.first()})}
            }
            old.createStatement().use {it.executeQuery("PRAGMA integrity_check").use {r->assertTrue(r.next());assertEquals("ok",r.getString(1))}}
        }
    }
    @Test fun directV2MigrationPreservesPendingSettingsAndKjFood() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use {c ->
            createSchema(c,2)
            c.createStatement().use {
                it.execute("INSERT INTO bodies VALUES (20000,72.4,15.2,61.3952,58.1)")
                it.execute("INSERT INTO foods (id,name,unit,basisAmount,kcal,carbs,protein,fat,step,maximum,allowed,energyInputUnit) VALUES (7,'米饭','g',100,100,20,4,0.4,10,500,1,'KJ')")
                it.execute("INSERT INTO pending_settings VALUES (1,'original-settings-payload')")
            }
            val foods=rows(c,"foods");val pending=rows(c,"pending_settings")
            val adapter=Proxy.newProxyInstance(SupportSQLiteDatabase::class.java.classLoader,arrayOf(SupportSQLiteDatabase::class.java)) {_,m,args ->
                if(m.name=="execSQL") {c.createStatement().use {it.execute(args!![0] as String)};null} else throw UnsupportedOperationException(m.name)
            } as SupportSQLiteDatabase
            Migrations.MIGRATION_2_3.migrate(adapter)
            assertEquals(foods,rows(c,"foods"));assertEquals(pending,rows(c,"pending_settings"))
            assertEquals("72.4",rows(c,"bodies").single()["weight"])
            assertEquals(7,rows(c,"bodies").single().filterKeys {it.startsWith("energy_")}.size)
            assertTrue(rows(c,"bodies").single().filterKeys {it.startsWith("energy_")}.values.all {it==null})
        }
    }
}


