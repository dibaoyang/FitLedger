package com.fitledger.data

import androidx.room.*
import com.fitledger.domain.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "bodies")
data class BodyRecord(@PrimaryKey val day: Long, val weight: Double, val fatPercent: Double, val lean: Double, val muscle: Double?,
    @Embedded(prefix="energy_") val energy:DailyEnergySnapshot?=null)
@Entity(tableName = "targets")
data class DailyTarget(@PrimaryKey val day: Long, @Embedded val nutrition: Nutrition, val planSnapshot: String)
@Entity(tableName = "food_zones")
data class FoodZone(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String,
    val dailyMaximum: Double? = null, val recommendationMaximum: Double? = null,
    @ColumnInfo(defaultValue = "0") val sortOrder:Long = 0) {
    fun validate() {
        require(id >= 0 && name.isNotBlank() && name.length <= 100 && name.none { it.code < 32 }) { "请填写 100 字以内的分区名称" }
        require(listOfNotNull(dailyMaximum,recommendationMaximum).all { it.isFinite() && it >= 0 }) { "分区上限须为不小于 0 的有效份数" }
        require(sortOrder>=0) {"分区排序无效"}
    }
}
@Entity(tableName = "foods")
data class Food(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val unit: String,
    val basisAmount: Double, @Embedded val nutrition: Nutrition, val step: Double, val maximum: Double, val allowed: Boolean,
    @ColumnInfo(defaultValue = "'KCAL'") val energyInputUnit: String = "KCAL",
    @ColumnInfo(defaultValue = "0") val isDeleted: Boolean = false,
    val zoneId: Long? = null, @ColumnInfo(defaultValue = "1.0") val zonePortionSize: Double = 1.0,
    @ColumnInfo(defaultValue = "0") val sortOrder:Long = 0,
    @ColumnInfo(defaultValue = "''") val nutritionTags:String = "", val pricePerUnitYuan:Double? = null) {
    fun candidate() = Candidate(id, name, nutrition*(1/basisAmount), step, maximum, allowed && !isDeleted,zoneId,zonePortionSize,pricePerUnitYuan)
    fun validate() {
        require(name.isNotBlank()) { "请填写食品名称" }
        require(energyInputUnit in EnergyUnit.entries.map { it.name }) { "热量单位无效" }
        require(unit == "g" || unit == "份")
        require(zoneId == null || zoneId > 0) { "食品分区无效" }
        require(sortOrder>=0) {"食品排序无效"}
        val tags=if(nutritionTags.isEmpty()) emptyList() else nutritionTags.split(',')
        require(tags.distinct().size==tags.size && tags.all {it in setOf("CARBS","PROTEIN","FAT")}) {"食品营养分区无效"}
        require(pricePerUnitYuan==null || (pricePerUnitYuan.isFinite() && pricePerUnitYuan>=0 && (pricePerUnitYuan*maximum).isFinite())) {"食品单价无效或最大推荐费用超出计算范围"}
        require(zonePortionSize.isFinite() && zonePortionSize > 0) { "每分区份的食品数量须大于 0" }
        require(zoneId==null || (maximum/zonePortionSize).isFinite()) { "每分区份的食品数量过小，无法计算推荐量" }
        require(basisAmount.isFinite() && basisAmount > 0) { "营养标注基准量必须大于 0" }
        require(nutrition.values().all { it.isFinite() && it >= 0 } && nutrition.kcal > 0) { "请填写有效营养值，热量须大于 0" }
        require(step.isFinite() && step > 0 && maximum.isFinite() && maximum >= step && maximum/step <= 2000) { "推荐量须大于等于调整单位，且最多 2000 个单位" }
    }
}
@Entity(tableName = "intakes", indices = [Index("day")])
data class Intake(@PrimaryKey(autoGenerate = true) val id: Long = 0, val day: Long, val foodId: Long?,
    val name: String, val unit: String, val quantity: Double, @Embedded val nutrition: Nutrition,
    @ColumnInfo(defaultValue = "'FOOD'") val source: String = "FOOD", val costYuan:Double? = null)

/** Durable outbox: Room commit and DataStore completion survive process death. */
@Entity(tableName = "pending_settings")
data class PendingSettings(@PrimaryKey val id: Int = 1, val payload: String)

@Dao
interface LedgerDao {
    @Query("SELECT * FROM food_zones ORDER BY sortOrder,name,id") fun zones(): Flow<List<FoodZone>>
    @Query("SELECT * FROM food_zones ORDER BY id") suspend fun allZones(): List<FoodZone>
    @Query("SELECT * FROM food_zones WHERE id=:id") suspend fun zone(id:Long): FoodZone?
    @Upsert suspend fun saveZone(zone:FoodZone)
    @Query("DELETE FROM food_zones WHERE id=:id") suspend fun deleteZone(id:Long): Int
    @Query("UPDATE foods SET zoneId=NULL WHERE zoneId=:id") suspend fun unassignZone(id:Long)
    @Query("UPDATE foods SET sortOrder=:order WHERE id=:id") suspend fun setFoodSortOrder(id:Long,order:Long):Int
    @Query("UPDATE food_zones SET sortOrder=:order WHERE id=:id") suspend fun setZoneSortOrder(id:Long,order:Long):Int
    @Query("DELETE FROM food_zones") suspend fun clearZonesForRestore()
    @Query("SELECT * FROM exercises ORDER BY id DESC") fun exercises(): Flow<List<Exercise>>
    @Query("SELECT * FROM exercises ORDER BY id") suspend fun allExercises():List<Exercise>
    @Insert suspend fun insertExercise(exercise:Exercise)
    @Query("DELETE FROM exercises WHERE id=:id AND day=:day") suspend fun deleteExercise(id:Long,day:Long)
    @Query("DELETE FROM exercises") suspend fun clearExercisesForRestore()
    @Query("SELECT * FROM bodies ORDER BY day") fun bodies(): Flow<List<BodyRecord>>
    @Query("SELECT * FROM targets ORDER BY day") fun targets(): Flow<List<DailyTarget>>
    @Query("SELECT * FROM foods WHERE isDeleted=0 ORDER BY sortOrder,name,id") fun foods(): Flow<List<Food>>
    @Query("SELECT * FROM foods ORDER BY id") fun allFoodsFlow():Flow<List<Food>>
    @Query("SELECT * FROM intakes ORDER BY id DESC") fun intakes(): Flow<List<Intake>>
    @Query("SELECT * FROM bodies WHERE day=:day") suspend fun body(day: Long): BodyRecord?
    @Query("SELECT * FROM targets WHERE day=:day") suspend fun target(day: Long): DailyTarget?
    @Query("SELECT * FROM foods WHERE id=:id") suspend fun food(id: Long): Food?
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertBody(body: BodyRecord)
    @Update suspend fun updateBody(body:BodyRecord):Int
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertTarget(target: DailyTarget)
    @Update suspend fun updateTarget(target: DailyTarget): Int
    @Upsert suspend fun saveFood(food: Food)
    // Keep the original row/identity for intake snapshots and complete backups.
    @Query("UPDATE foods SET isDeleted=1 WHERE id=:id AND isDeleted=0") suspend fun markFoodDeleted(id: Long): Int
    @Insert suspend fun insertIntake(intake: Intake):Long
    @Query("SELECT * FROM intakes WHERE id=:id") suspend fun intake(id:Long):Intake?
    @Update suspend fun updateIntake(intake:Intake):Int
    @Query("DELETE FROM intakes WHERE id=:id AND day=:day") suspend fun deleteIntake(id: Long, day: Long)
    @Query("SELECT * FROM bodies ORDER BY day") suspend fun allBodies(): List<BodyRecord>
    @Query("SELECT * FROM targets ORDER BY day") suspend fun allTargets(): List<DailyTarget>
    @Query("SELECT * FROM foods ORDER BY id") suspend fun allFoods(): List<Food>
    @Query("SELECT * FROM intakes ORDER BY id") suspend fun allIntakes(): List<Intake>
    @Query("SELECT * FROM pending_settings WHERE id=1") suspend fun pendingSettings(): PendingSettings?
    @Upsert suspend fun stageSettings(settings: PendingSettings)
    @Query("DELETE FROM pending_settings WHERE id=1") suspend fun completeSettings()
    // These replacement methods are used ONLY after explicit restore confirmation, inside one transaction.
    @Query("DELETE FROM intakes") suspend fun clearIntakesForRestore()
    @Query("DELETE FROM targets") suspend fun clearTargetsForRestore()
    @Query("DELETE FROM bodies") suspend fun clearBodiesForRestore()
    @Query("DELETE FROM foods") suspend fun clearFoodsForRestore()
}
@Database(entities = [BodyRecord::class, DailyTarget::class, Food::class, Intake::class, PendingSettings::class, Exercise::class, FoodZone::class, DailyFoodTemplate::class, DailyFoodApplication::class, DayMode::class], version = Migrations.DATABASE_VERSION, exportSchema = true)
abstract class LedgerDatabase: RoomDatabase() {
    abstract fun dao(): LedgerDao
    abstract fun dailyEatingDao():DailyEatingDao
}

