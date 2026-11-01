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
    @Test fun releasedElevenUpgradesToTwelveWithoutChangingExistingSnapshotsOrSettings() {
        helper.createDatabase("migration-v11-recipes",11).apply {
            execSQL("INSERT INTO bodies (day,weight,fatPercent,lean,muscle,energy_age,energy_heightCm,energy_sex,energy_birthday,energy_activityFactor,energy_bmrKcal,energy_expenditureKcal) VALUES (20000,72.4,15.2,61.3952,NULL,26,175,'MALE',10957,1.55,1700,2635)")
            execSQL("INSERT INTO targets (day,kcal,carbs,protein,fat,planSnapshot) VALUES (20000,2200,250,160,62.22,'unchanged target snapshot')")
            execSQL("INSERT INTO food_zones VALUES (3,'主食',500,300,2,0,'G')")
            execSQL("INSERT INTO foods (id,name,unit,basisAmount,kcal,carbs,protein,fat,step,maximum,allowed,isDeleted,zoneId,pricePerUnitYuan,favorite,quickAmounts,aliases) VALUES (7,'米饭','g',100,116,26,2.6,0.3,10,500,1,1,3,0.01,1,'100,200','[\"常吃的饭\"]')")
            execSQL("INSERT INTO intakes (id,day,foodId,name,unit,quantity,kcal,carbs,protein,fat,source,costYuan) VALUES (9,20000,7,'真实历史名','g',200,232,52,5.2,0.6,'ROUTINE',2)")
            execSQL("INSERT INTO intakes (id,day,foodId,name,unit,quantity,kcal,carbs,protein,fat,source,costYuan) VALUES (10,20000,NULL,'仅热量保持未知','次',1,700,0,0,0,'ENERGY',NULL)")
            execSQL("INSERT INTO exercises VALUES (3,20000,'MANUAL',NULL,NULL,NULL,300,1)")
            execSQL("INSERT INTO pending_settings VALUES (1,'unmodified settings recovery journal')")
            execSQL("INSERT INTO daily_food_templates VALUES (5,7,'g','FIXED',200,1,20000,'unchanged schedule JSON')")
            execSQL("INSERT INTO daily_food_applications VALUES (20000,5,9)")
            execSQL("INSERT INTO daily_food_skips VALUES (20000,6)")
            execSQL("INSERT INTO day_modes VALUES (20000,1)")
            execSQL("INSERT INTO food_combos VALUES (8,'组合','unchanged combo JSON')")
            execSQL("INSERT INTO food_preset_plans VALUES (20001,20000,2635,72.4)")
            execSQL("INSERT INTO food_preset_items VALUES (11,20001,7,'预设快照','g',200,2,999,232,52,5.2,0.6)")
            close()
        }
        helper.runMigrationsAndValidate("migration-v11-recipes",12,true,Migrations.MIGRATION_11_12).apply {
            query("SELECT weight,fatPercent,lean,muscle,energy_bmrKcal,energy_expenditureKcal FROM bodies").use {
                assertTrue(it.moveToFirst());assertEquals(72.4,it.getDouble(0),0.0);assertEquals(15.2,it.getDouble(1),0.0)
                assertEquals(61.3952,it.getDouble(2),0.0);assertTrue(it.isNull(3));assertEquals(1700.0,it.getDouble(4),0.0);assertEquals(2635.0,it.getDouble(5),0.0)
            }
            query("SELECT planSnapshot,kcal FROM targets").use {assertTrue(it.moveToFirst());assertEquals("unchanged target snapshot",it.getString(0));assertEquals(2200.0,it.getDouble(1),0.0)}
            query("SELECT isDeleted,aliases,quickAmounts,pricePerUnitYuan FROM foods").use {assertTrue(it.moveToFirst());assertEquals(1,it.getInt(0));assertEquals("[\"常吃的饭\"]",it.getString(1));assertEquals("100,200",it.getString(2));assertEquals(0.01,it.getDouble(3),0.0)}
            query("SELECT name,kcal,source,costYuan FROM intakes WHERE id=9").use {assertTrue(it.moveToFirst());assertEquals("真实历史名",it.getString(0));assertEquals(232.0,it.getDouble(1),0.0);assertEquals("ROUTINE",it.getString(2));assertEquals(2.0,it.getDouble(3),0.0)}
            query("SELECT source,costYuan,kcal FROM intakes WHERE id=10").use {assertTrue(it.moveToFirst());assertEquals("ENERGY",it.getString(0));assertTrue(it.isNull(1));assertEquals(700.0,it.getDouble(2),0.0)}
            query("SELECT items,ratioRules FROM food_combos").use {assertTrue(it.moveToFirst());assertEquals("unchanged combo JSON",it.getString(0));assertEquals("",it.getString(1))}
            query("SELECT referenceDay,referenceExpenditureKcal,referenceWeightKg,ratioRules FROM food_preset_plans").use {assertTrue(it.moveToFirst());assertEquals(20000L,it.getLong(0));assertEquals(2635.0,it.getDouble(1),0.0);assertEquals(72.4,it.getDouble(2),0.0);assertEquals("",it.getString(3))}
            query("SELECT name,appliedIntakeId,costYuan FROM food_preset_items").use {assertTrue(it.moveToFirst());assertEquals("预设快照",it.getString(0));assertEquals(999L,it.getLong(1));assertEquals(2.0,it.getDouble(2),0.0)}
            query("SELECT payload FROM pending_settings").use {assertTrue(it.moveToFirst());assertEquals("unmodified settings recovery journal",it.getString(0))}
            query("SELECT schedule FROM daily_food_templates").use {assertTrue(it.moveToFirst());assertEquals("unchanged schedule JSON",it.getString(0))}
            query("SELECT intakeId FROM daily_food_applications").use {assertTrue(it.moveToFirst());assertEquals(9L,it.getLong(0))}
            query("SELECT templateId FROM daily_food_skips").use {assertTrue(it.moveToFirst());assertEquals(6L,it.getLong(0))}
            query("SELECT isIndulgence FROM day_modes").use {assertTrue(it.moveToFirst());assertEquals(1,it.getInt(0))}
            query("SELECT kcal FROM exercises").use {assertTrue(it.moveToFirst());assertEquals(300.0,it.getDouble(0),0.0)}
            query("SELECT COUNT(*) FROM food_recipes").use {assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))}
            query("PRAGMA integrity_check").use {assertTrue(it.moveToFirst());assertEquals("ok",it.getString(0))}
            close()
        }
    }

    @Test fun nineToTenAddsOnlyEmptyPresetTablesAndPreservesAllPendingSettings() {
        helper.createDatabase("migration-v9-presets",9).apply {
            execSQL("INSERT INTO bodies (day,weight,fatPercent,lean,muscle) VALUES (20000,72.4,15.2,61.3952,NULL)")
            execSQL("INSERT INTO targets (day,kcal,carbs,protein,fat,planSnapshot) VALUES (20000,2000,250,120,57.77,'unchanged target snapshot')")
            execSQL("INSERT INTO foods (id,name,unit,basisAmount,kcal,carbs,protein,fat,step,maximum,allowed,favorite,quickAmounts) VALUES (7,'原食品','g',100,116,26,2.6,0.3,10,500,1,1,'50,100')")
            execSQL("INSERT INTO intakes (id,day,foodId,name,unit,quantity,kcal,carbs,protein,fat,source,costYuan) VALUES (9,20000,7,'旧营养快照','g',200,232,52,5.2,0.6,'FOOD',2.5)")
            execSQL("INSERT INTO food_combos VALUES (3,'原组合','original combo JSON')")
            execSQL("INSERT INTO daily_food_skips VALUES (20000,7)")
            execSQL("INSERT INTO pending_settings VALUES (1,'unchanged settings journal')")
            close()
        }
        helper.runMigrationsAndValidate("migration-v9-presets",10,true,Migrations.MIGRATION_9_10).apply {
            query("SELECT favorite,quickAmounts FROM foods WHERE id=7").use {assertTrue(it.moveToFirst());assertEquals(1,it.getInt(0));assertEquals("50,100",it.getString(1))}
            query("SELECT name,quantity,kcal,costYuan FROM intakes WHERE id=9").use {assertTrue(it.moveToFirst());assertEquals("旧营养快照",it.getString(0));assertEquals(200.0,it.getDouble(1),0.0);assertEquals(232.0,it.getDouble(2),0.0);assertEquals(2.5,it.getDouble(3),0.0)}
            query("SELECT planSnapshot FROM targets").use {assertTrue(it.moveToFirst());assertEquals("unchanged target snapshot",it.getString(0))}
            query("SELECT items FROM food_combos WHERE id=3").use {assertTrue(it.moveToFirst());assertEquals("original combo JSON",it.getString(0))}
            query("SELECT templateId FROM daily_food_skips").use {assertTrue(it.moveToFirst());assertEquals(7L,it.getLong(0))}
            query("SELECT payload FROM pending_settings").use {assertTrue(it.moveToFirst());assertEquals("unchanged settings journal",it.getString(0))}
            query("SELECT COUNT(*) FROM food_preset_plans").use {assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))}
            query("SELECT COUNT(*) FROM food_preset_items").use {assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))}
            query("SELECT COUNT(*) FROM bodies WHERE day=20001").use {assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))}
            close()
        }
    }
    @Test fun releasedEightUpgradesToNineWithoutChangingGramQuotasOrHistoricalSnapshots() {
        helper.createDatabase("migration-v8-pending",8).apply {
            execSQL("INSERT INTO food_zones (id,name,dailyMaximum,recommendationMaximum,sortOrder,limitUnit) VALUES (1,'克数分区',300,100,9,'G')")
            execSQL("INSERT INTO foods (id,name,unit,basisAmount,kcal,carbs,protein,fat,step,maximum,allowed,isDeleted,zoneId,zonePortionSize,gramsPerPortion,pricePerUnitYuan) VALUES (7,'面包','份',1,200,30,10,4,0.5,10,1,1,1,1.5,50,2.25)")
            execSQL("INSERT INTO bodies (day,weight,fatPercent,lean,muscle) VALUES (20000,72.4,15.2,61.3952,NULL)")
            execSQL("INSERT INTO targets (day,kcal,carbs,protein,fat,planSnapshot) VALUES (20000,2000,250,120,57.77,'released frozen plan')")
            execSQL("INSERT INTO intakes (id,day,foodId,name,unit,quantity,kcal,carbs,protein,fat,source,costYuan) VALUES (9,20000,7,'frozen bread','份',2,400,60,20,8,'ROUTINE',4.5)")
            execSQL("INSERT INTO daily_food_templates VALUES (1,7,'份','FIXED',2,1,20000)")
            execSQL("INSERT INTO daily_food_applications VALUES (20000,1,9)")
            execSQL("INSERT INTO exercises VALUES (1,20000,'MANUAL',NULL,NULL,NULL,123,1)")
            execSQL("INSERT INTO pending_settings VALUES (1,'released hotfix journal')")
            close()
        }
        helper.runMigrationsAndValidate("migration-v8-pending",9,true,Migrations.MIGRATION_8_9).apply {
            query("SELECT limitUnit,dailyMaximum,recommendationMaximum,sortOrder,allowMultipleFoods FROM food_zones WHERE id=1").use {
                assertTrue(it.moveToFirst());assertEquals("G",it.getString(0));assertEquals(300.0,it.getDouble(1),0.0);assertEquals(100.0,it.getDouble(2),0.0);assertEquals(9L,it.getLong(3));assertEquals(1,it.getInt(4))
            }
            query("SELECT gramsPerPortion,zonePortionSize,isDeleted,pricePerUnitYuan,favorite,quickAmounts FROM foods WHERE id=7").use {
                assertTrue(it.moveToFirst());assertEquals(50.0,it.getDouble(0),0.0);assertEquals(1.5,it.getDouble(1),0.0);assertEquals(1,it.getInt(2));assertEquals(2.25,it.getDouble(3),0.0);assertEquals(0,it.getInt(4));assertEquals("",it.getString(5))
            }
            query("SELECT name,quantity,kcal,source,costYuan FROM intakes WHERE id=9").use {
                assertTrue(it.moveToFirst());assertEquals("frozen bread",it.getString(0));assertEquals(2.0,it.getDouble(1),0.0);assertEquals(400.0,it.getDouble(2),0.0);assertEquals("ROUTINE",it.getString(3));assertEquals(4.5,it.getDouble(4),0.0)
            }
            query("SELECT planSnapshot FROM targets").use {assertTrue(it.moveToFirst());assertEquals("released frozen plan",it.getString(0))}
            query("SELECT weight,muscle FROM bodies").use {assertTrue(it.moveToFirst());assertEquals(72.4,it.getDouble(0),0.0);assertTrue(it.isNull(1))}
            query("SELECT kcal FROM exercises").use {assertTrue(it.moveToFirst());assertEquals(123.0,it.getDouble(0),0.0)}
            query("SELECT payload FROM pending_settings").use {assertTrue(it.moveToFirst());assertEquals("released hotfix journal",it.getString(0))}
            query("SELECT intakeId FROM daily_food_applications").use {assertTrue(it.moveToFirst());assertEquals(9L,it.getLong(0))}
            query("SELECT schedule FROM daily_food_templates").use {assertTrue(it.moveToFirst());assertEquals("",it.getString(0))}
            close()
        }
    }
    @Test fun v7ThroughNineKeepsFrozenSnapshotsDeletedMarkersAndAddsOnlyFeatureDefaults() {
        helper.createDatabase("migration-v7-features",7).apply {
            execSQL("INSERT INTO food_zones VALUES (1,'old zone',3,2,9)")
            execSQL("INSERT INTO bodies (day,weight,fatPercent,lean,muscle) VALUES (20000,72.4,15.2,61.3952,NULL)")
            execSQL("INSERT INTO foods (id,name,unit,basisAmount,kcal,carbs,protein,fat,step,maximum,allowed,isDeleted,pricePerUnitYuan) VALUES (7,'old rice','g',100,116,26,2.6,0.3,10,500,1,1,0.01)")
            execSQL("INSERT INTO targets (day,kcal,carbs,protein,fat,planSnapshot) VALUES (20000,2000,250,120,57.77,'exact historical plan')")
            execSQL("INSERT INTO intakes (id,day,foodId,name,unit,quantity,kcal,carbs,protein,fat,source,costYuan) VALUES (9,20000,7,'frozen original','g',200,232,52,5.2,0.6,'ROUTINE',2.0)")
            execSQL("INSERT INTO daily_food_templates VALUES (1,7,'g','FIXED',200,1,20000)")
            execSQL("INSERT INTO daily_food_templates VALUES (500,7,'g','FIXED',100,1,20000)")
            execSQL("DELETE FROM daily_food_templates WHERE id=500")
            execSQL("INSERT INTO daily_food_applications VALUES (20000,1,9)")
            execSQL("INSERT INTO daily_food_applications VALUES (20000,500,999)")
            execSQL("INSERT INTO exercises VALUES (1,20000,'MANUAL',NULL,NULL,NULL,123,1)")
            execSQL("INSERT INTO pending_settings VALUES (1,'exact pending preferences')")
            close()
        }
        helper.runMigrationsAndValidate("migration-v7-features",9,true,Migrations.MIGRATION_7_8,Migrations.MIGRATION_8_9).apply {
            query("SELECT name,dailyMaximum,recommendationMaximum,sortOrder,allowMultipleFoods FROM food_zones WHERE id=1").use {
                assertTrue(it.moveToFirst());assertEquals("old zone",it.getString(0));assertEquals(3.0,it.getDouble(1),0.0);assertEquals(2.0,it.getDouble(2),0.0);assertEquals(9L,it.getLong(3));assertEquals(1,it.getInt(4))
            }
            query("SELECT favorite,quickAmounts,isDeleted,pricePerUnitYuan FROM foods WHERE id=7").use {
                assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0));assertEquals("",it.getString(1));assertEquals(1,it.getInt(2));assertEquals(0.01,it.getDouble(3),0.0)
            }
            query("SELECT quantity,schedule FROM daily_food_templates WHERE id=1").use {assertTrue(it.moveToFirst());assertEquals(200.0,it.getDouble(0),0.0);assertEquals("",it.getString(1))}
            query("SELECT source,costYuan,name FROM intakes WHERE id=9").use {assertTrue(it.moveToFirst());assertEquals("ROUTINE",it.getString(0));assertEquals(2.0,it.getDouble(1),0.0);assertEquals("frozen original",it.getString(2))}
            query("SELECT intakeId FROM daily_food_applications WHERE templateId=500").use {assertTrue(it.moveToFirst());assertEquals(999L,it.getLong(0))}
            query("SELECT planSnapshot FROM targets").use {assertTrue(it.moveToFirst());assertEquals("exact historical plan",it.getString(0))}
            query("SELECT payload FROM pending_settings").use {assertTrue(it.moveToFirst());assertEquals("exact pending preferences",it.getString(0))}
            query("SELECT COUNT(*) FROM food_combos").use {assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))}
            query("SELECT COUNT(*) FROM daily_food_skips").use {assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))}
            execSQL("INSERT INTO daily_food_templates (foodId,unit,mode,quantity,enabled,startDay) VALUES (7,'g','FIXED',100,1,20001)")
            query("SELECT MAX(id) FROM daily_food_templates").use {assertTrue(it.moveToFirst());assertEquals(501L,it.getLong(0))}
            close()
        }
    }
    @Test fun v1DataSurvivesRoomMigration() {
        helper.createDatabase("migration-test",1).apply {
            execSQL("INSERT INTO bodies (day,weight,fatPercent,lean,muscle) VALUES (20000,72.4,15.2,61.3952,58.1)")
            execSQL("INSERT INTO foods (id,name,unit,basisAmount,kcal,carbs,protein,fat,step,maximum,allowed) VALUES (7,'米饭','g',100,116,26,2.6,0.3,10,500,1)")
            execSQL("INSERT INTO targets (day,kcal,carbs,protein,fat,planSnapshot) VALUES (20000,2000,250,120,57.77,'original-plan-snapshot')")
            execSQL("INSERT INTO intakes (id,day,foodId,name,unit,quantity,kcal,carbs,protein,fat) VALUES (9,20000,7,'米饭','g',200,232,52,5.2,0.6)")
            close()
        }
        helper.runMigrationsAndValidate("migration-test",13,true,*Migrations.ALL).apply {
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
            execSQL("INSERT INTO bodies (day,weight,fatPercent,lean,muscle) VALUES (20000,72.4,15.2,61.3952,58.1)")
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
            execSQL("INSERT INTO bodies (day,weight,fatPercent,lean,muscle,energy_age,energy_heightCm,energy_sex,energy_birthday,energy_activityFactor,energy_bmrKcal,energy_expenditureKcal) VALUES (20000,72.4,15.2,61.3952,58.1,21,175,'MALE',12000,1.55,1700,2635)")
            execSQL("INSERT INTO foods (id,name,unit,basisAmount,kcal,carbs,protein,fat,step,maximum,allowed,energyInputUnit) VALUES (7,'rice','g',100,116,26,2.6,0.3,10,500,1,'KJ')")
            execSQL("INSERT INTO targets (day,kcal,carbs,protein,fat,planSnapshot) VALUES (20000,2000,250,120,57.77,'original-plan')")
            execSQL("INSERT INTO intakes (id,day,foodId,name,unit,quantity,kcal,carbs,protein,fat) VALUES (9,20000,7,'old-rice-name','g',200,232,52,5.2,0.6)")
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
            execSQL("INSERT INTO bodies (day,weight,fatPercent,lean,muscle,energy_age,energy_heightCm,energy_sex,energy_birthday,energy_activityFactor,energy_bmrKcal,energy_expenditureKcal) VALUES (20000,72.4,15.2,61.3952,58.1,21,175,'MALE',12000,1.55,1700,2635)")
            execSQL("INSERT INTO foods (id,name,unit,basisAmount,kcal,carbs,protein,fat,step,maximum,allowed,energyInputUnit,isDeleted) VALUES (7,'rice','g',100,116,26,2.6,0.3,10,500,1,'KJ',1)")
            execSQL("INSERT INTO targets (day,kcal,carbs,protein,fat,planSnapshot) VALUES (20000,2000,250,120,57.77,'original-plan')")
            execSQL("INSERT INTO intakes (id,day,foodId,name,unit,quantity,kcal,carbs,protein,fat) VALUES (9,20000,7,'old-rice-name','g',200,232,52,5.2,0.6)")
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
            execSQL("INSERT INTO bodies (day,weight,fatPercent,lean,muscle,energy_age,energy_heightCm,energy_sex,energy_birthday,energy_activityFactor,energy_bmrKcal,energy_expenditureKcal) VALUES (20000,72.4,15.2,61.3952,NULL,21,175,'MALE',12000,1.55,1700,2635)")
            execSQL("INSERT INTO foods (id,name,unit,basisAmount,kcal,carbs,protein,fat,step,maximum,allowed,energyInputUnit,isDeleted) VALUES (7,'rice','g',100,116,26,2.6,0.3,10,500,1,'KJ',1)")
            execSQL("INSERT INTO targets (day,kcal,carbs,protein,fat,planSnapshot) VALUES (20000,2000,250,120,57.77,'original-plan')")
            execSQL("INSERT INTO intakes (id,day,foodId,name,unit,quantity,kcal,carbs,protein,fat,source) VALUES (9,20000,7,'old-rice-name','g',200,232,52,5.2,0.6,'FOOD')")
            execSQL("INSERT INTO intakes (id,day,foodId,name,unit,quantity,kcal,carbs,protein,fat,source) VALUES (10,20000,NULL,'直接添加营养','次',1,590,100,25,10,'DIRECT')")
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
}


