package com.fitledger.data

import androidx.sqlite.db.SupportSQLiteDatabase
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager

class FeatureMigrationTest {
    @Test fun everyPreviousSchemaUpgradesToThirteenWithoutChangingHistoricalValues() {
        for(version in 1..12) DriverManager.getConnection("jdbc:sqlite::memory:").use {c ->
            create(c,version)
            c.createStatement().use {s ->
                s.execute("INSERT INTO bodies (day,weight,fatPercent,lean,muscle) VALUES (20000,72.4,15.2,61.3952,58.1)")
                if(version>=3) s.execute("UPDATE bodies SET energy_age=26,energy_heightCm=175,energy_sex='MALE',energy_birthday=10957,energy_activityFactor=1.55,energy_bmrKcal=1700,energy_expenditureKcal=2635")
                s.execute("INSERT INTO foods (id,name,unit,basisAmount,kcal,carbs,protein,fat,step,maximum,allowed) VALUES (7,'米饭','g',100,116,26,2.6,0.3,10,500,1)")
                if(version>=2) s.execute("UPDATE foods SET energyInputUnit='KJ'")
                if(version>=5) s.execute("UPDATE foods SET isDeleted=1")
                s.execute("INSERT INTO targets (day,kcal,carbs,protein,fat,planSnapshot) VALUES (20000,2000,250,120,57.77,'exact historical plan JSON')")
                s.execute("INSERT INTO intakes (id,day,foodId,name,unit,quantity,kcal,carbs,protein,fat) VALUES (9,20000,7,'原食品名称','g',200,232,52,5.2,0.6)")
                s.execute("INSERT INTO intakes (id,day,foodId,name,unit,quantity,kcal,carbs,protein,fat) VALUES (100,20000,7,'删除过的摄入','g',100,116,26,2.6,0.3)")
                s.execute("DELETE FROM intakes WHERE id=100")
                if(version>=6) {
                    s.execute("INSERT INTO bodies (day,weight,fatPercent,lean,muscle) VALUES (19999,72.4,15.2,61.3952,NULL)")
                    s.execute("INSERT INTO targets (day,kcal,carbs,protein,fat,planSnapshot) VALUES (19999,2000,250,120,57.77,'other original plan')")
                    s.execute("INSERT INTO intakes (id,day,foodId,name,unit,quantity,kcal,carbs,protein,fat,source) VALUES (10,19999,NULL,'直接添加营养','次',1,590,100,25,10,'DIRECT')")
                }
                if(version>=2) s.execute("INSERT INTO pending_settings VALUES (1,'exact old settings journal')")
                if(version>=4) s.execute("INSERT INTO exercises VALUES (3,20000,'MANUAL',NULL,NULL,NULL,300,1)")
                if(version>=9) {
                    s.execute("UPDATE foods SET favorite=1,quickAmounts='50,100'")
                    s.execute("INSERT INTO food_combos (id,name,items) VALUES (23,'原组合','[{\"foodId\":7,\"unit\":\"g\",\"quantity\":100}]')")
                    s.execute("INSERT INTO daily_food_skips VALUES (20000,55)")
                }
            }
            val oldTables=tables(c)
            val before=oldTables.associateWith {rows(c,it)}
            Migrations.ALL.filter {it.startVersion>=version}.forEach {it.migrate(adapter(c))}
            before.forEach {(table,records) ->
                val oldColumns=records.firstOrNull()?.keys
                val actual=rows(c,table)
                assertEquals("v$version $table",records,if(oldColumns==null) actual else actual.map {it.filterKeys {k->k in oldColumns}})
            }
            assertEquals("58.1",rows(c,"bodies").first {it["day"]=="20000"}["muscle"])
            assertEquals("FOOD",rows(c,"intakes").first {it["id"]=="9"}["source"])
            assertTrue(rows(c,"food_zones").isEmpty())
            assertNull(rows(c,"foods").single()["zoneId"])
            assertEquals("1.0",rows(c,"foods").single()["zonePortionSize"])
            assertEquals("0",rows(c,"foods").single()["sortOrder"])
            assertEquals("",rows(c,"foods").single()["nutritionTags"])
            assertNull(rows(c,"foods").single()["pricePerUnitYuan"])
            assertNull(rows(c,"foods").single()["gramsPerPortion"]);assertEquals("",rows(c,"foods").single()["aliases"])
            assertEquals(if(version>=9) "1" else "0",rows(c,"foods").single()["favorite"])
            assertEquals(if(version>=9) "50,100" else "",rows(c,"foods").single()["quickAmounts"])
            assertEquals(if(version>=9) 1 else 0,rows(c,"food_combos").size)
            assertEquals(if(version>=9) 1 else 0,rows(c,"daily_food_skips").size)
            assertTrue(rows(c,"food_preset_plans").isEmpty());assertTrue(rows(c,"food_preset_items").isEmpty())
            assertTrue(rows(c,"intakes").all {it["costYuan"]==null})
            DriverManager.getConnection("jdbc:sqlite::memory:").use {fresh ->
                create(fresh,13)
                assertEquals(tables(fresh),tables(c))
                tables(fresh).forEach {assertEquals("v$version schema $it",columns(fresh,it),columns(c,it))}
                assertEquals(indexes(fresh),indexes(c))
            }
            c.createStatement().use {s ->
                s.execute("INSERT INTO bodies (day,weight,fatPercent,lean,muscle) VALUES (20001,72.4,15.2,61.3952,NULL)")
                s.execute("INSERT INTO intakes (day,foodId,name,unit,quantity,kcal,carbs,protein,fat,source) VALUES (20001,NULL,'直接添加营养','次',1,590,100,25,10,'DIRECT')")
                s.executeQuery("SELECT id,foodId,source FROM intakes WHERE day=20001").use {r ->
                    assertTrue(r.next());assertTrue(r.getLong(1)>100);assertNull(r.getObject(2));assertEquals("DIRECT",r.getString(3))
                }
                s.executeQuery("PRAGMA integrity_check").use {r ->assertTrue(r.next());assertEquals("ok",r.getString(1))}
            }
        }
    }

    @Test fun tenToElevenOnlyAddsEmptyAliasesAndPreservesEveryExistingTable() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use {c->
            create(c,10)
            c.createStatement().use {s->
                s.execute("INSERT INTO bodies (day,weight,fatPercent,lean,muscle) VALUES (20000,72.4,15.2,61.3952,NULL)")
                s.execute("INSERT INTO targets (day,kcal,carbs,protein,fat,planSnapshot) VALUES (20000,2200,250,160,62.22,'frozen target JSON')")
                s.execute("INSERT INTO foods (id,name,unit,basisAmount,kcal,carbs,protein,fat,step,maximum,allowed,zoneId,favorite,quickAmounts) VALUES (7,'米饭','g',100,116,26,2.6,0.3,10,500,1,3,1,'100,200')")
                s.execute("INSERT INTO food_zones VALUES (3,'主食',500,300,2,0,'G')")
                s.execute("INSERT INTO intakes (id,day,foodId,name,unit,quantity,kcal,carbs,protein,fat,source,costYuan) VALUES (9,20000,7,'历史食品名称','g',200,232,52,5.2,0.6,'ROUTINE',4.0)")
                s.execute("INSERT INTO pending_settings VALUES (1,'old settings recovery journal')")
                s.execute("INSERT INTO exercises VALUES (3,20000,'MANUAL',NULL,NULL,NULL,300,1)")
                s.execute("INSERT INTO daily_food_templates VALUES (5,7,'g','FIXED',200,1,20000,'old schedule JSON')")
                s.execute("INSERT INTO daily_food_applications VALUES (20000,5,9)")
                s.execute("INSERT INTO daily_food_skips VALUES (20000,6)")
                s.execute("INSERT INTO day_modes VALUES (20000,1)")
                s.execute("INSERT INTO food_combos VALUES (8,'组合','old items JSON')")
                s.execute("INSERT INTO food_preset_plans VALUES (20001,20000,2635,72.4)")
                s.execute("INSERT INTO food_preset_items VALUES (8,20001,7,'旧预设名称','g',200,4,NULL,232,52,5.2,0.6)")
            }
            val before=tables(c).associateWith {rows(c,it)}
            Migrations.MIGRATION_10_11.migrate(adapter(c))
            assertEquals(before.keys,tables(c).toSet())
            before.forEach {(table,records)->
                val actual=rows(c,table).map {row->if(table=="foods") row.filterKeys {it!="aliases"} else row}
                assertEquals(table,records,actual)
            }
            assertEquals("",rows(c,"foods").single()["aliases"])
            c.createStatement().use {s->
                s.executeQuery("PRAGMA integrity_check").use {r->assertTrue(r.next());assertEquals("ok",r.getString(1))}
            }
        }
    }

    @Test fun sevenThroughNinePreservesRecurringCompletionMarkersAndAutoincrementHighWater() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use {c ->
            create(c,7)
            c.createStatement().use {s ->
                s.execute("INSERT INTO food_zones VALUES (7,'原分区',3,2,11)")
                s.execute("INSERT INTO daily_food_templates VALUES (1,7,'g','FIXED',100,1,20000)")
                s.execute("INSERT INTO daily_food_templates VALUES (900,7,'g','FIXED',200,1,20000)")
                s.execute("DELETE FROM daily_food_templates WHERE id=900")
                s.execute("INSERT INTO daily_food_applications VALUES (20000,900,999)")
                s.execute("INSERT INTO day_modes VALUES (20000,1)")
            }
            val before=listOf("daily_food_templates","daily_food_applications","day_modes").associateWith {rows(c,it)}
            Migrations.MIGRATION_7_8.migrate(adapter(c))
            Migrations.MIGRATION_8_9.migrate(adapter(c))
            before.forEach {(table,records) ->assertEquals(records,rows(c,table).map {it.filterKeys {key->key!="schedule"}})}
            assertEquals("",rows(c,"daily_food_templates").single()["schedule"])
            val zone=rows(c,"food_zones").single()
            assertEquals("1",zone["allowMultipleFoods"]);assertEquals("原分区",zone["name"])
            assertEquals("3.0",zone["dailyMaximum"]);assertEquals("2.0",zone["recommendationMaximum"]);assertEquals("11",zone["sortOrder"])
            c.createStatement().use {s ->
                s.execute("INSERT INTO daily_food_templates (foodId,unit,mode,quantity,enabled,startDay) VALUES (7,'g','FIXED',300,1,20001)")
                s.executeQuery("SELECT MAX(id) FROM daily_food_templates").use {r->assertTrue(r.next());assertEquals(901L,r.getLong(1))}
                s.executeQuery("PRAGMA integrity_check").use {r->assertTrue(r.next());assertEquals("ok",r.getString(1))}
            }
        }
    }

    @Test fun releasedEightUpgradesToNinePreservingGramQuotasAndEveryOldColumn() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use {c ->
            create(c,8)
            c.createStatement().use {s ->
                s.execute("INSERT INTO food_zones (id,name,dailyMaximum,recommendationMaximum,sortOrder,limitUnit) VALUES (7,'克数分区',300,100,11,'G')")
                s.execute("INSERT INTO foods (id,name,unit,basisAmount,kcal,carbs,protein,fat,step,maximum,allowed,isDeleted,zoneId,zonePortionSize,gramsPerPortion,pricePerUnitYuan) VALUES (7,'面包','份',1,200,30,10,4,0.5,10,1,1,7,1.5,50,2.25)")
                s.execute("INSERT INTO daily_food_templates VALUES (1,7,'份','FIXED',2,1,20000)")
                s.execute("INSERT INTO daily_food_applications VALUES (20000,1,99)")
                s.execute("INSERT INTO day_modes VALUES (20000,1)")
                s.execute("INSERT INTO pending_settings VALUES (1,'released hotfix settings journal')")
            }
            val before=tables(c).associateWith {rows(c,it)}
            Migrations.MIGRATION_8_9.migrate(adapter(c))
            before.forEach {(table,records) ->
                val originalColumns=records.firstOrNull()?.keys
                val actual=rows(c,table)
                assertEquals(table,records,if(originalColumns==null) actual else actual.map {it.filterKeys {key->key in originalColumns}})
            }
            val food=rows(c,"foods").single();val zone=rows(c,"food_zones").single()
            assertEquals("50.0",food["gramsPerPortion"]);assertEquals("1.5",food["zonePortionSize"])
            assertEquals("G",zone["limitUnit"]);assertEquals("300.0",zone["dailyMaximum"])
            assertEquals("0",food["favorite"]);assertEquals("",food["quickAmounts"])
            assertEquals("1",zone["allowMultipleFoods"]);assertEquals("",rows(c,"daily_food_templates").single()["schedule"])
            assertTrue(rows(c,"food_combos").isEmpty());assertTrue(rows(c,"daily_food_skips").isEmpty())
            DriverManager.getConnection("jdbc:sqlite::memory:").use {fresh ->
                create(fresh,9)
                assertEquals(tables(fresh),tables(c))
                tables(fresh).forEach {assertEquals(it,columns(fresh,it),columns(c,it))}
                assertEquals(indexes(fresh),indexes(c))
            }
        }
    }

    @Test fun presetTablesPermitUnknownEnergyAndPendingItemsButEnforceUniqueAppliedIntake() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use {c ->
            create(c,9)
            Migrations.MIGRATION_9_10.migrate(adapter(c))
            c.createStatement().use {s ->
                s.execute("INSERT INTO food_preset_plans VALUES (20001,20000,NULL,72.4)")
                s.execute("INSERT INTO food_preset_items (day,foodId,name,unit,quantity,kcal,carbs,protein,fat) VALUES (20001,7,'明日食品','g',100,116,26,2.6,0.3)")
                s.execute("INSERT INTO food_preset_items (day,foodId,name,unit,quantity,kcal,carbs,protein,fat) VALUES (20001,7,'另一次','g',100,116,26,2.6,0.3)")
                s.execute("UPDATE food_preset_items SET appliedIntakeId=900 WHERE id=1")
                assertTrue(runCatching {s.execute("UPDATE food_preset_items SET appliedIntakeId=900 WHERE id=2")}.isFailure)
                assertEquals("900",rows(c,"food_preset_items").first()["appliedIntakeId"])
                assertNull(rows(c,"food_preset_items").last()["appliedIntakeId"])
                assertNull(rows(c,"food_preset_plans").single()["referenceExpenditureKcal"])
                assertTrue(rows(c,"bodies").isEmpty());assertTrue(rows(c,"targets").isEmpty());assertTrue(rows(c,"intakes").isEmpty())
            }
        }
    }

    @Test fun emptyIntakeTableRetainsDeletedHighWaterMarkDuringUpgrade() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use {c ->
            create(c,5)
            c.createStatement().use {s ->
                s.execute("INSERT INTO intakes VALUES (100,20000,7,'old','g',100,100,20,4,0.4)")
                s.execute("DELETE FROM intakes")
            }
            Migrations.MIGRATION_5_6.migrate(adapter(c))
            c.createStatement().use {s ->
                s.execute("INSERT INTO intakes (day,foodId,name,unit,quantity,kcal,carbs,protein,fat,source) VALUES (20000,NULL,'直接添加营养','次',1,4,1,0,0,'DIRECT')")
                s.executeQuery("SELECT id FROM intakes").use {r ->assertTrue(r.next());assertEquals(101L,r.getLong(1))}
            }
        }
    }

    @Test fun elevenToTwelvePreservesEveryOldTableAndSequenceWhileAddingOnlySafeRecipeAndRatioDefaults() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use {c->
            create(c,11)
            c.createStatement().use {s->
                s.execute("INSERT INTO bodies (day,weight,fatPercent,lean,muscle,energy_age,energy_heightCm,energy_sex,energy_birthday,energy_activityFactor,energy_bmrKcal,energy_expenditureKcal) VALUES (20000,72.4,15.2,61.3952,NULL,26,175,'MALE',10957,1.55,1700,2635)")
                s.execute("INSERT INTO targets (day,kcal,carbs,protein,fat,planSnapshot) VALUES (20000,2200,250,160,62.22,'exact frozen target including old settings')")
                s.execute("INSERT INTO food_zones VALUES (3,'主食',500,300,2,0,'G')")
                s.execute("INSERT INTO foods (id,name,unit,basisAmount,kcal,carbs,protein,fat,step,maximum,allowed,isDeleted,zoneId,pricePerUnitYuan,favorite,quickAmounts,aliases) VALUES (7,'米饭','g',100,116,26,2.6,0.3,10,500,1,1,3,0.01,1,'100,200','[\"常吃的饭\"]')")
                s.execute("INSERT INTO intakes (id,day,foodId,name,unit,quantity,kcal,carbs,protein,fat,source,costYuan) VALUES (9,20000,7,'真实历史名','g',200,232,52,5.2,0.6,'ROUTINE',2)")
                s.execute("INSERT INTO intakes (id,day,foodId,name,unit,quantity,kcal,carbs,protein,fat,source,costYuan) VALUES (10,20000,NULL,'仅热量保持未知','次',1,700,0,0,0,'ENERGY',NULL)")
                s.execute("INSERT INTO exercises VALUES (3,20000,'MANUAL',NULL,NULL,NULL,300,1)")
                s.execute("INSERT INTO pending_settings VALUES (1,'unmodified settings recovery journal')")
                s.execute("INSERT INTO daily_food_templates VALUES (5,7,'g','FIXED',200,1,20000,'unchanged schedule JSON')")
                s.execute("INSERT INTO daily_food_applications VALUES (20000,5,9)")
                s.execute("INSERT INTO daily_food_skips VALUES (20000,6)")
                s.execute("INSERT INTO day_modes VALUES (20000,1)")
                s.execute("INSERT INTO food_combos VALUES (8,'组合','unchanged combo JSON')")
                s.execute("INSERT INTO food_combos VALUES (900,'已删除组合','deleted payload')");s.execute("DELETE FROM food_combos WHERE id=900")
                s.execute("INSERT INTO food_preset_plans VALUES (20001,20000,2635,72.4)")
                s.execute("INSERT INTO food_preset_items VALUES (11,20001,7,'预设快照','g',200,2,999,232,52,5.2,0.6)")
                s.execute("INSERT INTO food_preset_items VALUES (901,20001,7,'已删除预设','g',100,NULL,NULL,116,26,2.6,0.3)")
                s.execute("DELETE FROM food_preset_items WHERE id=901")
            }
            val before=tables(c).associateWith {rows(c,it)}
            val sequences=query(c,"SELECT name,seq FROM sqlite_sequence ORDER BY name")
            Migrations.MIGRATION_11_12.migrate(adapter(c))
            assertEquals(before.keys+"food_recipes",tables(c).toSet())
            before.forEach {(table,records)->
                assertEquals(table,records,rows(c,table).map {row->
                    if(table in setOf("food_combos","food_preset_plans")) row.filterKeys {it!="ratioRules"} else row
                })
            }
            assertEquals(sequences,query(c,"SELECT name,seq FROM sqlite_sequence WHERE name!='food_recipes' ORDER BY name"))
            assertEquals("",rows(c,"food_combos").single()["ratioRules"])
            assertEquals("",rows(c,"food_preset_plans").single()["ratioRules"])
            assertEquals("exact frozen target including old settings",rows(c,"targets").single()["planSnapshot"])
            val energy=rows(c,"intakes").single {it["id"]=="10"}
            assertEquals("ENERGY",energy["source"]);assertEquals("700.0",energy["kcal"]);assertNull(energy["costYuan"])
            assertTrue(rows(c,"food_recipes").isEmpty())
            DriverManager.getConnection("jdbc:sqlite::memory:").use {fresh->
                create(fresh,12)
                assertEquals(tables(fresh),tables(c))
                tables(fresh).forEach {assertEquals(it,columns(fresh,it),columns(c,it))}
                assertEquals(indexes(fresh),indexes(c))
            }
            c.createStatement().use {s->
                s.execute("INSERT INTO food_combos (name,items) VALUES ('新组合','new payload')")
                s.execute("INSERT INTO food_preset_items (day,foodId,name,unit,quantity,kcal,carbs,protein,fat) VALUES (20001,7,'新预设','g',100,116,26,2.6,0.3)")
                assertEquals("901",rows(c,"food_combos").last()["id"])
                assertEquals("902",rows(c,"food_preset_items").last()["id"])
                s.execute("INSERT INTO food_recipes (name,ingredients,finishedWeightG,foodId) VALUES ('配方','snapshot JSON',350,7)")
                assertTrue(runCatching {s.execute("INSERT INTO food_recipes (name,ingredients,finishedWeightG,foodId) VALUES ('重复关联','other',350,7)")}.isFailure)
                s.execute("INSERT INTO food_recipes (name,ingredients,finishedWeightG) VALUES ('未发布配方1','snapshot1',200)")
                s.execute("INSERT INTO food_recipes (name,ingredients,finishedWeightG) VALUES ('未发布配方2','snapshot2',300)")
                assertEquals(3,rows(c,"food_recipes").size)
                s.executeQuery("PRAGMA integrity_check").use {r->assertTrue(r.next());assertEquals("ok",r.getString(1))}
            }
        }
    }

    @Test fun twelveToThirteenPreservesLegacyFractionsRecipesAndAllHistoricalTables() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use {c->
            create(c,12)
            c.createStatement().use {s->
                s.execute("INSERT INTO bodies(day,weight,fatPercent,lean,muscle) VALUES(20000,60,20,48,NULL)")
                s.execute("INSERT INTO targets(day,planSnapshot,kcal,carbs,protein,fat) VALUES(20000,'frozen plan',2000,250,150,60)")
                s.execute("INSERT INTO foods(id,name,unit,basisAmount,kcal,carbs,protein,fat,step,maximum,allowed,aliases) VALUES(7,'旧面包','份',1,200,30,10,4,0.5,5,1,'[\"旧别名\"]')")
                s.execute("INSERT INTO intakes(id,day,foodId,name,unit,quantity,kcal,carbs,protein,fat,source,costYuan) VALUES(10,20000,7,'半份快照','份',0.5,100,15,5,2,'FOOD',NULL)")
                s.execute("INSERT INTO food_combos(id,name,items,ratioRules) VALUES(8,'旧组合','frozen items','frozen ratios')")
                s.execute("INSERT INTO food_recipes(id,name,ingredients,finishedWeightG,foodId) VALUES(30,'旧配方','frozen ingredients',300,7)")
                s.execute("INSERT INTO pending_settings(id,payload) VALUES(1,'frozen preferences journal')")
            }
            val before=tables(c).associateWith {rows(c,it)}
            val sequences=query(c,"SELECT name,seq FROM sqlite_sequence ORDER BY name")
            Migrations.MIGRATION_12_13.migrate(adapter(c))
            assertEquals(before.keys+setOf("pending_foods","recipe_batches"),tables(c).toSet())
            before.forEach {(table,records)->assertEquals(table,records,rows(c,table).map {row->if(table=="foods") row.filterKeys {it !in setOf("minimum","preparationTags")} else row})}
            assertEquals(sequences,query(c,"SELECT name,seq FROM sqlite_sequence WHERE name NOT IN ('pending_foods','recipe_batches') ORDER BY name"))
            assertEquals("0.0",rows(c,"foods").single()["minimum"]);assertEquals("",rows(c,"foods").single()["preparationTags"])
            assertEquals("0.5",rows(c,"intakes").single()["quantity"])
            assertTrue(rows(c,"pending_foods").isEmpty());assertTrue(rows(c,"recipe_batches").isEmpty())
            DriverManager.getConnection("jdbc:sqlite::memory:").use {fresh->
                create(fresh,13);assertEquals(tables(fresh),tables(c));assertEquals(indexes(fresh),indexes(c))
                tables(fresh).forEach {assertEquals(it,columns(fresh,it),columns(c,it))}
            }
            c.createStatement().use {s->
                s.execute("INSERT INTO pending_foods(day,groupId,foodId,name,unit,quantity,appliedIntakeId,createdAt,kcal,carbs,protein,fat) VALUES(20000,'g',7,'面包','份',1,1000,1,200,30,10,4)")
                assertTrue(runCatching {s.execute("INSERT INTO pending_foods(day,groupId,foodId,name,unit,quantity,appliedIntakeId,createdAt,kcal,carbs,protein,fat) VALUES(20000,'g',7,'面包','份',1,1000,1,200,30,10,4)")}.isFailure)
                s.executeQuery("PRAGMA integrity_check").use {r->assertTrue(r.next());assertEquals("ok",r.getString(1))}
            }
        }
    }

    private fun adapter(c:Connection)=Proxy.newProxyInstance(SupportSQLiteDatabase::class.java.classLoader,arrayOf(SupportSQLiteDatabase::class.java)) {_,m,args ->
        if(m.name=="execSQL") {c.createStatement().use {it.execute(args!![0] as String)};null} else throw UnsupportedOperationException(m.name)
    } as SupportSQLiteDatabase
    private fun create(c:Connection,version:Int) {
        val entities=JSONObject(File("schemas/com.fitledger.data.LedgerDatabase/$version.json").readText()).getJSONObject("database").getJSONArray("entities")
        for(i in 0 until entities.length()) {
            val e=entities.getJSONObject(i)
            c.createStatement().use {s ->
                s.execute(e.getString("createSql").replace("\${TABLE_NAME}",e.getString("tableName")))
                val indexes=e.getJSONArray("indices")
                for(j in 0 until indexes.length()) s.execute(indexes.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}",e.getString("tableName")))
            }
        }
    }
    private fun tables(c:Connection)=query(c,"SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name").map {it.single()!!}
    private fun indexes(c:Connection)=query(c,"SELECT name,tbl_name FROM sqlite_master WHERE type='index' AND name NOT LIKE 'sqlite_%' ORDER BY name")
    private fun columns(c:Connection,table:String)=query(c,"SELECT name,type,\"notnull\",dflt_value,pk FROM pragma_table_info('$table') ORDER BY name")
    private fun query(c:Connection,sql:String):List<List<String?>> = c.createStatement().use {s ->s.executeQuery(sql).use {r ->
        val out=mutableListOf<List<String?>>()
        while(r.next()) out+=(1..r.metaData.columnCount).map {r.getString(it)}
        out
    }}
    private fun rows(c:Connection,table:String):List<Map<String,String?>> = c.createStatement().use {s ->s.executeQuery("SELECT * FROM $table ORDER BY rowid").use {r ->
        val out=mutableListOf<Map<String,String?>>()
        while(r.next()) out+=(1..r.metaData.columnCount).associate {r.metaData.getColumnName(it) to r.getString(it)}
        out
    }}
}

