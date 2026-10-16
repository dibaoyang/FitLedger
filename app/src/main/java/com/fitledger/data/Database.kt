package com.fitledger.data

import androidx.room.*
import com.fitledger.domain.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "bodies")
data class BodyRecord(@PrimaryKey val day: Long, val weight: Double, val fatPercent: Double, val lean: Double, val muscle: Double?,
    @Embedded(prefix="energy_") val energy:DailyEnergySnapshot?=null)
@Entity(tableName = "targets")
data class DailyTarget(@PrimaryKey val day: Long, @Embedded val nutrition: Nutrition, val planSnapshot: String)
@Entity(tableName = "foods")
data class Food(@PrimaryKey(autoGenerate = true) val id: Long = 0, val name: String, val unit: String,
    val basisAmount: Double, @Embedded val nutrition: Nutrition, val step: Double, val maximum: Double, val allowed: Boolean,
    @ColumnInfo(defaultValue = "'KCAL'") val energyInputUnit: String = "KCAL",
    @ColumnInfo(defaultValue = "0") val isDeleted: Boolean = false) {
    fun candidate() = Candidate(id, name, nutrition*(1/basisAmount), step, maximum, allowed && !isDeleted)
    fun validate() {
        require(name.isNotBlank()) { "请填写食品名称" }
        require(energyInputUnit in EnergyUnit.entries.map { it.name }) { "热量单位无效" }
        require(unit == "g" || unit == "份")
        require(basisAmount.isFinite() && basisAmount > 0) { "营养标注基准量必须大于 0" }
        require(nutrition.values().all { it.isFinite() && it >= 0 } && nutrition.kcal > 0) { "请填写有效营养值，热量须大于 0" }
        require(step.isFinite() && step > 0 && maximum.isFinite() && maximum >= step && maximum/step <= 2000) { "推荐量须大于等于调整单位，且最多 2000 个单位" }
    }
}
@Entity(tableName = "intakes", indices = [Index("day")])
data class Intake(@PrimaryKey(autoGenerate = true) val id: Long = 0, val day: Long, val foodId: Long?,
    val name: String, val unit: String, val quantity: Double, @Embedded val nutrition: Nutrition,
    @ColumnInfo(defaultValue = "'FOOD'") val source: String = "FOOD")

/** Durable outbox: Room commit and DataStore completion survive process death. */
@Entity(tableName = "pending_settings")
data class PendingSettings(@PrimaryKey val id: Int = 1, val payload: String)

@Dao
interface LedgerDao {
    @Query("SELECT * FROM exercises ORDER BY id DESC") fun exercises(): Flow<List<Exercise>>
    @Query("SELECT * FROM exercises ORDER BY id") suspend fun allExercises():List<Exercise>
    @Insert suspend fun insertExercise(exercise:Exercise)
    @Query("DELETE FROM exercises WHERE id=:id AND day=:day") suspend fun deleteExercise(id:Long,day:Long)
    @Query("DELETE FROM exercises") suspend fun clearExercisesForRestore()
    @Query("SELECT * FROM bodies ORDER BY day") fun bodies(): Flow<List<BodyRecord>>
    @Query("SELECT * FROM targets ORDER BY day") fun targets(): Flow<List<DailyTarget>>
    @Query("SELECT * FROM foods WHERE isDeleted=0 ORDER BY name") fun foods(): Flow<List<Food>>
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
    @Insert suspend fun insertIntake(intake: Intake)
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
@Database(entities = [BodyRecord::class, DailyTarget::class, Food::class, Intake::class, PendingSettings::class, Exercise::class], version = Migrations.DATABASE_VERSION, exportSchema = true)
abstract class LedgerDatabase: RoomDatabase() { abstract fun dao(): LedgerDao }

