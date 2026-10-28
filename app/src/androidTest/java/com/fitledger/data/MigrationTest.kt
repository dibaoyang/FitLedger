package com.fitledger.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {
    @get:Rule val helper=MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),LedgerDatabase::class.java)
    @Test fun v1DataSurvivesRoomMigration() {
        helper.createDatabase("migration-test",1).apply {
            execSQL("INSERT INTO bodies VALUES (20000,72.4,15.2,61.3952,58.1)")
            execSQL("INSERT INTO foods (id,name,unit,basisAmount,kcal,carbs,protein,fat,step,maximum,allowed) VALUES (7,'米饭','g',100,116,26,2.6,0.3,10,500,1)")
            execSQL("INSERT INTO targets (day,kcal,carbs,protein,fat,planSnapshot) VALUES (20000,2000,250,120,57.77,'original-plan-snapshot')")
            execSQL("INSERT INTO intakes (id,day,foodId,name,unit,quantity,kcal,carbs,protein,fat) VALUES (9,20000,7,'米饭','g',200,232,52,5.2,0.6)")
            close()
        }
        helper.runMigrationsAndValidate("migration-test",8,true,*Migrations.ALL).apply {
            query("SELECT kcal,energyInputUnit FROM foods WHERE id=7").use {assertTrue(it.moveToFirst());assertEquals(116.0,it.getDouble(0),0.0);assertEquals("KCAL",it.getString(1))}
            query("SELECT lean FROM bodies WHERE day=20000").use {assertTrue(it.moveToFirst());assertEquals(61.3952,it.getDouble(0),0.0)}
            query("SELECT planSnapshot FROM targets WHERE day=20000").use {assertTrue(it.moveToFirst());assertEquals("original-plan-snapshot",it.getString(0))}
            query("SELECT kcal FROM intakes WHERE id=9").use {assertTrue(it.moveToFirst());assertEquals(232.0,it.getDouble(0),0.0)}
            query("SELECT energy_bmrKcal,energy_age,energy_expenditureKcal FROM bodies WHERE day=20000").use {assertTrue(it.moveToFirst());assertTrue(it.isNull(0));assertTrue(it.isNull(1));assertTrue(it.isNull(2))}
            query("SELECT isDeleted FROM foods WHERE id=7").use {assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))}
            close()
        }
    }
    @Test fun v2DataSurvivesRoomMigrationWithNullEnergy() {
        helper.createDatabase("migration-v2-test",2).apply {
            execSQL("INSERT INTO bodies VALUES (20000,72.4,15.2,61.3952,58.1)")
            execSQL("INSERT INTO pending_settings VALUES (1,'original-settings')")
            close()
        }
        helper.runMigrationsAndValidate("migration-v2-test",5,true,Migrations.MIGRATION_2_3,Migrations.MIGRATION_3_4,Migrations.MIGRATION_4_5).apply {
            query("SELECT weight,energy_bmrKcal FROM bodies").use {assertTrue(it.moveToFirst());assertEquals(72.4,it.getDouble(0),0.0);assertTrue(it.isNull(1))}
            query("SELECT payload FROM pending_settings").use {assertTrue(it.moveToFirst());assertEquals("original-settings",it.getString(0))}
            close()
        }
    }
    @Test fun v4UpgradeKeepsFoodIdentityIntakesEnergyExerciseAndSettings() {
        helper.createDatabase("migration-v4-test",4).apply {
            execSQL("INSERT INTO bodies VALUES (20000,72.4,15.2,61.3952,58.1,21,175,'MALE',12000,1.55,1700,2635)")
            execSQL("INSERT INTO foods VALUES (7,'rice','g',100,116,26,2.6,0.3,10,500,1,'KJ')")
            execSQL("INSERT INTO targets VALUES (20000,2000,250,120,57.77,'original-plan')")
            execSQL("INSERT INTO intakes VALUES (9,20000,7,'old-rice-name','g',200,232,52,5.2,0.6)")
            execSQL("INSERT INTO exercises VALUES (3,20000,'MANUAL',NULL,NULL,NULL,300,1)")
            execSQL("INSERT INTO pending_settings VALUES (1,'original-settings')")
            close()
        }
        helper.runMigrationsAndValidate("migration-v4-test",5,true,Migrations.MIGRATION_4_5).apply {
            query("SELECT isDeleted,energyInputUnit,kcal,allowed FROM foods WHERE id=7").use {
                assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0));assertEquals("KJ",it.getString(1));assertEquals(116.0,it.getDouble(2),0.0);assertEquals(1,it.getInt(3))
            }
            query("SELECT foodId,name,kcal FROM intakes WHERE id=9").use {
                assertTrue(it.moveToFirst());assertEquals(7L,it.getLong(0));assertEquals("old-rice-name",it.getString(1));assertEquals(232.0,it.getDouble(2),0.0)
            }
            query("SELECT lean,energy_bmrKcal,energy_expenditureKcal FROM bodies WHERE day=20000").use {
                assertTrue(it.moveToFirst());assertEquals(61.3952,it.getDouble(0),0.0);assertEquals(1700.0,it.getDouble(1),0.0);assertEquals(2635.0,it.getDouble(2),0.0)
            }
            query("SELECT planSnapshot FROM targets WHERE day=20000").use {assertTrue(it.moveToFirst());assertEquals("original-plan",it.getString(0))}
            query("SELECT kcal,included FROM exercises WHERE id=3").use {assertTrue(it.moveToFirst());assertEquals(300.0,it.getDouble(0),0.0);assertEquals(1,it.getInt(1))}
            query("SELECT payload FROM pending_settings").use {assertTrue(it.moveToFirst());assertEquals("original-settings",it.getString(0))}
            close()
        }
    }
    @Test fun v5UpgradePreservesMeasurementsAndFoodIntakesAndAllowsAbsentMuscleAndDirectIntakes() {
        helper.createDatabase("migration-v5-test",5).apply {
            execSQL("INSERT INTO bodies VALUES (20000,72.4,15.2,61.3952,58.1,21,175,'MALE',12000,1.55,1700,2635)")
            execSQL("INSERT INTO foods VALUES (7,'rice','g',100,116,26,2.6,0.3,10,500,1,'KJ',1)")
            execSQL("INSERT INTO targets VALUES (20000,2000,250,120,57.77,'original-plan')")
            execSQL("INSERT INTO intakes VALUES (9,20000,7,'old-rice-name','g',200,232,52,5.2,0.6)")
            execSQL("INSERT INTO exercises VALUES (3,20000,'MANUAL',NULL,NULL,NULL,300,1)")
            execSQL("INSERT INTO pending_settings VALUES (1,'old settings with no intro keys')")
            close()
        }
        helper.runMigrationsAndValidate("migration-v5-test",6,true,Migrations.MIGRATION_5_6).apply {
            query("SELECT muscle,lean,energy_bmrKcal FROM bodies WHERE day=20000").use {
                assertTrue(it.moveToFirst());assertEquals(58.1,it.getDouble(0),0.0);assertEquals(61.3952,it.getDouble(1),0.0);assertEquals(1700.0,it.getDouble(2),0.0)
            }
            query("SELECT foodId,name,kcal,source FROM intakes WHERE id=9").use {
                assertTrue(it.moveToFirst());assertEquals(7L,it.getLong(0));assertEquals("old-rice-name",it.getString(1));assertEquals(232.0,it.getDouble(2),0.0);assertEquals("FOOD",it.getString(3))
            }
            query("SELECT isDeleted,energyInputUnit FROM foods WHERE id=7").use {assertTrue(it.moveToFirst());assertEquals(1,it.getInt(0));assertEquals("KJ",it.getString(1))}
            query("SELECT planSnapshot FROM targets").use {assertTrue(it.moveToFirst());assertEquals("original-plan",it.getString(0))}
            query("SELECT kcal FROM exercises").use {assertTrue(it.moveToFirst());assertEquals(300.0,it.getDouble(0),0.0)}
            query("SELECT payload FROM pending_settings").use {assertTrue(it.moveToFirst());assertEquals("old settings with no intro keys",it.getString(0))}
            execSQL("INSERT INTO bodies (day,weight,fatPercent,lean,muscle) VALUES (20001,72.4,15.2,61.3952,NULL)")
            execSQL("INSERT INTO intakes (day,foodId,name,unit,quantity,kcal,carbs,protein,fat,source) VALUES (20001,NULL,'直接添加营养','次',1,590,100,25,10,'DIRECT')")
            query("SELECT muscle FROM bodies WHERE day=20001").use {assertTrue(it.moveToFirst());assertTrue(it.isNull(0))}
            close()
        }
    }
    @Test fun v6UpgradeAddsEmptyZonesWithoutChangingAnyExistingSnapshotsOrSettings() {
        helper.createDatabase("migration-v6-zones",6).apply {
            execSQL("INSERT INTO bodies VALUES (20000,72.4,15.2,61.3952,NULL,21,175,'MALE',12000,1.55,1700,2635)")
            execSQL("INSERT INTO foods VALUES (7,'rice','g',100,116,26,2.6,0.3,10,500,1,'KJ',1)")
            execSQL("INSERT INTO targets VALUES (20000,2000,250,120,57.77,'original-plan')")
            execSQL("INSERT INTO intakes VALUES (9,20000,7,'old-rice-name','g',200,232,52,5.2,0.6,'FOOD')")
            execSQL("INSERT INTO intakes VALUES (10,20000,NULL,'直接添加营养','次',1,590,100,25,10,'DIRECT')")
            execSQL("INSERT INTO exercises VALUES (3,20000,'MANUAL',NULL,NULL,NULL,300,1)")
            execSQL("INSERT INTO pending_settings VALUES (1,'exact old pending settings')")
            close()
        }
        helper.runMigrationsAndValidate("migration-v6-zones",7,true,Migrations.MIGRATION_6_7).apply {
            query("SELECT zoneId,zonePortionSize,isDeleted,energyInputUnit,kcal,nutritionTags,pricePerUnitYuan,sortOrder FROM foods WHERE id=7").use {
                assertTrue(it.moveToFirst());assertTrue(it.isNull(0));assertEquals(1.0,it.getDouble(1),0.0)
                assertEquals(1,it.getInt(2));assertEquals("KJ",it.getString(3));assertEquals(116.0,it.getDouble(4),0.0)
                assertEquals("",it.getString(5));assertTrue(it.isNull(6));assertEquals(0L,it.getLong(7))
            }
            query("SELECT muscle,lean,energy_bmrKcal FROM bodies").use {
                assertTrue(it.moveToFirst());assertTrue(it.isNull(0));assertEquals(61.3952,it.getDouble(1),0.0);assertEquals(1700.0,it.getDouble(2),0.0)
            }
            query("SELECT foodId,kcal,source FROM intakes WHERE id=9").use {
                assertTrue(it.moveToFirst());assertEquals(7L,it.getLong(0));assertEquals(232.0,it.getDouble(1),0.0);assertEquals("FOOD",it.getString(2))
            }
            query("SELECT foodId,kcal,source FROM intakes WHERE id=10").use {
                assertTrue(it.moveToFirst());assertTrue(it.isNull(0));assertEquals(590.0,it.getDouble(1),0.0);assertEquals("DIRECT",it.getString(2))
            }
            query("SELECT planSnapshot FROM targets").use {assertTrue(it.moveToFirst());assertEquals("original-plan",it.getString(0))}
            query("SELECT kcal FROM exercises").use {assertTrue(it.moveToFirst());assertEquals(300.0,it.getDouble(0),0.0)}
            query("SELECT payload FROM pending_settings").use {assertTrue(it.moveToFirst());assertEquals("exact old pending settings",it.getString(0))}
            query("SELECT COUNT(*) FROM food_zones").use {assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))}
            query("SELECT COUNT(*) FROM intakes WHERE costYuan IS NOT NULL").use {assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))}
            for(table in listOf("daily_food_templates","daily_food_applications","day_modes")) {
                query("SELECT COUNT(*) FROM $table").use {assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))}
            }
            close()
        }
    }
    @Test fun v7UpgradeOnlyAddsPortionDefaultAndUnknownGramsKeepingOriginalData() {
        helper.createDatabase("migration-v7-grams",7).apply {
            execSQL("INSERT INTO food_zones VALUES (1,'旧面包',3.0,2.0,5)")
            execSQL("INSERT INTO foods (id,name,unit,basisAmount,kcal,carbs,protein,fat,step,maximum,allowed,energyInputUnit,isDeleted,zoneId,zonePortionSize,sortOrder,nutritionTags,pricePerUnitYuan) VALUES (7,'面包','份',1,100,20,3,1,1,10,1,'KJ',1,1,0.5,6,'CARBS,PROTEIN',2.5)")
            execSQL("INSERT INTO bodies VALUES (20000,72.4,15.2,61.3952,NULL,21,175,'MALE',12000,1.55,1700,2635)")
            execSQL("INSERT INTO targets VALUES (20000,2000,250,120,57.77,'exact old plan')")
            execSQL("INSERT INTO intakes VALUES (9,20000,7,'历史面包名','份',2,200,40,6,2,'FOOD',4.8)")
            execSQL("INSERT INTO pending_settings VALUES (1,'exact old settings')")
            close()
        }
        helper.runMigrationsAndValidate("migration-v7-grams",8,true,Migrations.MIGRATION_7_8).apply {
            query("SELECT name,dailyMaximum,recommendationMaximum,sortOrder,limitUnit FROM food_zones").use {
                assertTrue(it.moveToFirst());assertEquals("旧面包",it.getString(0));assertEquals(3.0,it.getDouble(1),0.0)
                assertEquals(2.0,it.getDouble(2),0.0);assertEquals(5,it.getInt(3));assertEquals("PORTION",it.getString(4))
            }
            query("SELECT zoneId,zonePortionSize,isDeleted,energyInputUnit,kcal,nutritionTags,pricePerUnitYuan,sortOrder,gramsPerPortion FROM foods").use {
                assertTrue(it.moveToFirst());assertEquals(1L,it.getLong(0));assertEquals(0.5,it.getDouble(1),0.0);assertEquals(1,it.getInt(2))
                assertEquals("KJ",it.getString(3));assertEquals(100.0,it.getDouble(4),0.0);assertEquals("CARBS,PROTEIN",it.getString(5))
                assertEquals(2.5,it.getDouble(6),0.0);assertEquals(6,it.getInt(7));assertTrue(it.isNull(8))
            }
            query("SELECT name,kcal,costYuan FROM intakes").use {assertTrue(it.moveToFirst());assertEquals("历史面包名",it.getString(0));assertEquals(200.0,it.getDouble(1),0.0);assertEquals(4.8,it.getDouble(2),0.0)}
            query("SELECT planSnapshot FROM targets").use {assertTrue(it.moveToFirst());assertEquals("exact old plan",it.getString(0))}
            query("SELECT payload FROM pending_settings").use {assertTrue(it.moveToFirst());assertEquals("exact old settings",it.getString(0))}
            close()
        }
    }
}

