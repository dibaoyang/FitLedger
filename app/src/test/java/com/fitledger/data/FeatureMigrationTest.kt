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
    @Test fun everyReleasedSchemaUpgradesToSevenWithoutChangingHistoricalValues() {
        for(version in 1..6) DriverManager.getConnection("jdbc:sqlite::memory:").use {c ->
            create(c,version)
            c.createStatement().use {s ->
                s.execute("INSERT INTO bodies (day,weight,fatPercent,lean,muscle) VALUES (20000,72.4,15.2,61.3952,58.1)")
                if(version>=3) s.execute("UPDATE bodies SET energy_age=26,energy_heightCm=175,energy_sex='MALE',energy_birthday=10957,energy_activityFactor=1.55,energy_bmrKcal=1700,energy_expenditureKcal=2635")
                s.execute("INSERT INTO foods (id,name,unit,basisAmount,kcal,carbs,protein,fat,step,maximum,allowed) VALUES (7,'米饭','g',100,116,26,2.6,0.3,10,500,1)")
                if(version>=2) s.execute("UPDATE foods SET energyInputUnit='KJ'")
                if(version>=5) s.execute("UPDATE foods SET isDeleted=1")
                s.execute("INSERT INTO targets VALUES (20000,2000,250,120,57.77,'exact historical plan JSON')")
                s.execute("INSERT INTO intakes (id,day,foodId,name,unit,quantity,kcal,carbs,protein,fat) VALUES (9,20000,7,'原食品名称','g',200,232,52,5.2,0.6)")
                s.execute("INSERT INTO intakes (id,day,foodId,name,unit,quantity,kcal,carbs,protein,fat) VALUES (100,20000,7,'删除过的摄入','g',100,116,26,2.6,0.3)")
                s.execute("DELETE FROM intakes WHERE id=100")
                if(version>=6) {
                    s.execute("INSERT INTO bodies (day,weight,fatPercent,lean,muscle) VALUES (19999,72.4,15.2,61.3952,NULL)")
                    s.execute("INSERT INTO targets VALUES (19999,2000,250,120,57.77,'other original plan')")
                    s.execute("INSERT INTO intakes (id,day,foodId,name,unit,quantity,kcal,carbs,protein,fat,source) VALUES (10,19999,NULL,'直接添加营养','次',1,590,100,25,10,'DIRECT')")
                }
                if(version>=2) s.execute("INSERT INTO pending_settings VALUES (1,'exact old settings journal')")
                if(version>=4) s.execute("INSERT INTO exercises VALUES (3,20000,'MANUAL',NULL,NULL,NULL,300,1)")
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
            assertTrue(rows(c,"intakes").all {it["costYuan"]==null})
            DriverManager.getConnection("jdbc:sqlite::memory:").use {fresh ->
                create(fresh,7)
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
