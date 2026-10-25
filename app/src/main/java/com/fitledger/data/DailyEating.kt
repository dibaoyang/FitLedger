package com.fitledger.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import androidx.room.withTransaction
import com.fitledger.domain.FoodPricing
import com.fitledger.domain.Nutrition
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/** Quantity uses the food's native unit, or g/kg of today's stored weight in PER_WEIGHT mode. */
@Entity(tableName = "daily_food_templates")
data class DailyFoodTemplate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val foodId: Long,
    val unit: String,
    val mode: String,
    val quantity: Double,
    val enabled: Boolean = true,
    val startDay: Long = LocalDate.now().toEpochDay()
) {
    fun validate() {
        require(id >= 0 && foodId > 0) { "每日必吃食品编号无效" }
        require(unit == "g" || unit == "份") { "每日必吃食品单位无效" }
        require(mode == "FIXED" || mode == "PER_WEIGHT") { "每日必吃数量方式无效" }
        require(mode != "PER_WEIGHT" || unit == "g") { "按体重添加只适用于以 g 记录的食品" }
        require(quantity.isFinite() && quantity > 0) { "每日必吃数量须为大于 0 的有效数值" }
        require(startDay >= 0) { "每日必吃开始日期无效" }
        require(runCatching { LocalDate.ofEpochDay(startDay) }.isSuccess) { "每日必吃开始日期无效" }
    }

    fun quantityForWeight(weightKg: Double): Double {
        validate()
        val amount = if (mode == "PER_WEIGHT") {
            require(weightKg.isFinite() && weightKg > 0) { "当天体重无效，无法按体重添加" }
            quantity * weightKg
        } else quantity
        require(amount.isFinite() && amount > 0) { "每日必吃数量超出可计算范围" }
        return amount
    }
}

/** Markers deliberately outlive deleted intakes and templates, suppressing accidental re-adds. */
@Entity(tableName = "daily_food_applications", primaryKeys = ["day", "templateId"], indices = [Index(value = ["intakeId"], unique = true)])
data class DailyFoodApplication(val day: Long, val templateId: Long, val intakeId: Long)

@Entity(tableName = "day_modes")
data class DayMode(@PrimaryKey val day: Long, val isIndulgence: Boolean)

@Dao
interface DailyEatingDao {
    @Query("SELECT * FROM daily_food_templates ORDER BY id")
    fun templates(): Flow<List<DailyFoodTemplate>>

    @Query("SELECT * FROM daily_food_applications ORDER BY day,templateId")
    fun applications(): Flow<List<DailyFoodApplication>>

    @Query("SELECT * FROM day_modes ORDER BY day")
    fun dayModes(): Flow<List<DayMode>>

    @Query("SELECT * FROM daily_food_templates ORDER BY id")
    suspend fun allTemplates(): List<DailyFoodTemplate>

    @Query("SELECT * FROM daily_food_applications ORDER BY day,templateId")
    suspend fun allApplications(): List<DailyFoodApplication>

    @Query("SELECT * FROM day_modes ORDER BY day")
    suspend fun allDayModes(): List<DayMode>

    @Query("SELECT * FROM daily_food_templates WHERE id=:id")
    suspend fun template(id: Long): DailyFoodTemplate?

    @Query("SELECT * FROM daily_food_applications WHERE day=:day AND templateId=:templateId")
    suspend fun application(day: Long, templateId: Long): DailyFoodApplication?

    @Query("SELECT * FROM day_modes WHERE day=:day")
    suspend fun dayMode(day: Long): DayMode?

    @Upsert
    suspend fun saveTemplate(template: DailyFoodTemplate): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertApplication(application: DailyFoodApplication)

    @Upsert
    suspend fun saveDayMode(mode: DayMode)

    @Query("DELETE FROM daily_food_templates WHERE id=:id")
    suspend fun deleteTemplate(id: Long): Int

    // Restore calls these only after explicit confirmation, in the Room replacement transaction.
    @Query("DELETE FROM daily_food_templates")
    suspend fun clearTemplatesForRestore()

    @Query("DELETE FROM daily_food_applications")
    suspend fun clearApplicationsForRestore()

    @Query("DELETE FROM day_modes")
    suspend fun clearDayModesForRestore()
}

class DailyEatingStore(private val db: LedgerDatabase) {
    val dao = db.dailyEatingDao()
    private val ledger get() = db.dao()

    /** Existing applications keep their frozen quantity/nutrition when a template changes. */
    suspend fun saveTemplate(template: DailyFoodTemplate, day: Long): List<String> = db.withTransaction {
        requireToday(day)
        template.validate()
        if (template.id != 0L) {
            requireNotNull(dao.template(template.id)) { "这项每日必吃设置已删除，请重新添加" }
        }
        val food = requireNotNull(ledger.food(template.foodId)) { "食品不存在，请重新选择" }
        require(!food.isDeleted) { "食品已删除，请重新选择" }
        require(food.unit == template.unit) { "食品单位已改变，请重新选择数量和单位" }
        // Validate the resulting snapshot when possible, before saving an unusable template.
        val amount = template.quantityForWeight(ledger.body(day)?.weight ?: 400.0)
        nutrition(food, amount)
        FoodPricing.cost(food.pricePerUnitYuan, amount)
        dao.saveTemplate(template)
        apply(day)
    }

    suspend fun deleteTemplate(id: Long) = db.withTransaction {
        require(id > 0 && dao.deleteTemplate(id) == 1) { "这项每日必吃设置已删除" }
        // Neither the historical snapshot nor its marker belongs to the template's lifetime.
        Unit
    }

    /** Creates each template at most once today; a manually deleted entry stays deleted. */
    suspend fun apply(day: Long): List<String> = db.withTransaction {
        requireToday(day)
        val body = ledger.body(day) ?: return@withTransaction emptyList()
        if (ledger.target(day) == null || dao.dayMode(day)?.isIndulgence == true) {
            return@withTransaction emptyList()
        }
        val warnings = mutableListOf<String>()
        dao.allTemplates().filter { it.enabled && it.startDay <= day }.forEach { template ->
            if (dao.application(day, template.id) != null) return@forEach
            val food = ledger.food(template.foodId)
            if (food == null || food.isDeleted) {
                warnings += "每日必吃食品已删除，已跳过该项设置"
                return@forEach
            }
            if (food.unit != template.unit) {
                warnings += "${food.name}的单位已变化，已跳过每日必吃；请修改设置后重试"
                return@forEach
            }
            // Only a bad template/food snapshot is skipped. Database failures still roll back.
            val snapshot = try {
                val quantity = template.quantityForWeight(body.weight)
                Intake(day = day, foodId = food.id, name = food.name, unit = food.unit,
                    quantity = quantity, nutrition = nutrition(food, quantity), source = "ROUTINE",
                    costYuan = FoodPricing.cost(food.pricePerUnitYuan, quantity))
            } catch (invalid: IllegalArgumentException) {
                warnings += "${food.name}未自动添加：${invalid.message ?: "设置数据无效"}"
                return@forEach
            }
            val intakeId = ledger.insertIntake(snapshot)
            check(intakeId > 0) { "每日必吃记录保存失败" }
            dao.insertApplication(DailyFoodApplication(day, template.id, intakeId))
        }
        warnings
    }

    suspend fun setIndulgence(day: Long, enabled: Boolean): List<String> = db.withTransaction {
        requireTodayAndSnapshots(day)
        dao.saveDayMode(DayMode(day, enabled))
        // Switching the presentation never removes the day's actual records or target.
        if (enabled) emptyList() else apply(day)
    }

    /** Scale the saved intake snapshot, never today's possibly edited food library values. */
    suspend fun editIntakeQuantity(day: Long, id: Long, quantity: Double) = db.withTransaction {
        requireTodayAndSnapshots(day)
        require(quantity.isFinite() && quantity > 0) { "摄入量须为大于 0 的有效数值" }
        val saved = requireNotNull(ledger.intake(id)) { "这条摄入记录已删除" }
        require(saved.day == day && saved.source in listOf("FOOD", "ROUTINE")) { "仅能修改今日的食品摄入量" }
        require(saved.quantity.isFinite() && saved.quantity > 0) { "原摄入量无效，无法修改" }
        val updated = saved.nutrition * (quantity / saved.quantity)
        require(updated.values().all { it.isFinite() && it >= 0 }) { "修改后的营养超出可计算范围" }
        val updatedCost = saved.costYuan?.let { cost ->
            require(cost.isFinite() && cost >= 0) { "原摄入费用无效" }
            (cost * (quantity / saved.quantity)).also { require(it.isFinite() && it >= 0) { "修改后的费用超出可计算范围" } }
        }
        check(ledger.updateIntake(saved.copy(quantity = quantity, nutrition = updated, costYuan = updatedCost)) == 1)
        Unit
    }

    private suspend fun requireTodayAndSnapshots(day: Long) {
        requireToday(day)
        require(ledger.body(day) != null && ledger.target(day) != null) { "请先完成今日身体数据" }
    }

    private fun requireToday(day: Long) {
        require(day == LocalDate.now().toEpochDay()) { "日期已变化，请重新进入今日" }
    }

    private fun nutrition(food: Food, quantity: Double): Nutrition {
        require(food.name.isNotBlank() && food.unit in listOf("g", "份")) { "食品资料无效" }
        require(food.basisAmount.isFinite() && food.basisAmount > 0) { "食品营养标注基准量无效" }
        require(quantity.isFinite() && quantity > 0) { "食品数量无效" }
        require(food.nutrition.values().all { it.isFinite() && it >= 0 } && food.nutrition.kcal > 0) { "食品营养资料无效" }
        val result = food.nutrition * (quantity / food.basisAmount)
        require(result.values().all { it.isFinite() && it >= 0 } && result.kcal > 0) { "食品营养超出可计算范围" }
        return result
    }
}
