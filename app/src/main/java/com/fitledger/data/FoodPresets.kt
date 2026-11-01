package com.fitledger.data

import androidx.room.*
import com.fitledger.domain.*
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A future estimate is a separate snapshot, never a future body/target or an intake. */
@Entity(tableName="food_preset_plans")
data class FoodPresetPlan(@PrimaryKey val day:Long,val referenceDay:Long,
    val referenceExpenditureKcal:Double?,val referenceWeightKg:Double,
    @ColumnInfo(defaultValue="''") val ratioRules:String="") {
    fun validate() {
        val date=LocalDate.ofEpochDay(day)
        require(ChronoUnit.DAYS.between(LocalDate.ofEpochDay(referenceDay),date) in 1L..7L) {"预设日期须为参考日期之后1至7天"}
        require(referenceExpenditureKcal==null || (referenceExpenditureKcal.isFinite() && referenceExpenditureKcal>0)) {"预设参考消耗无效"}
        require(referenceWeightKg.isFinite() && referenceWeightKg>0) {"预设参考体重无效"}
        WeightRatios.decode(ratioRules)
    }
}

/** Contains the reviewed source snapshots, so confirmation cannot accept a stale calculation. */
data class PresetRatioPreview(val plan:FoodPresetPlan,val before:List<FoodPresetItem>,val foods:List<Food>,
    val updated:List<FoodPresetItem>,val rounding:WeightRatioPreview)

object FoodPresetRatios {
    fun preview(plan:FoodPresetPlan,items:List<FoodPresetItem>,foods:List<Food>):PresetRatioPreview {
        plan.validate()
        require(items.all {it.day==plan.day} && items.map {it.id}.distinct().size==items.size) {"预设项目或日期无效"}
        items.forEach {it.validate()}
        val rules=WeightRatios.decode(plan.ratioRules)
        require(rules.isNotEmpty()) {"请先保存重量比例规则"}
        val involved=rules.flatMap {listOf(it.foodId,it.referenceFoodId)}.toSet()
        val groups=items.groupBy {it.foodId}
        involved.forEach {id->
            require(groups[id]?.size==1) {"参与比例的每种食品须只有一条预设，请先合并重复项或补齐参考食品"}
            require(groups.getValue(id).single().appliedIntakeId==null) {"比例涉及已添加的预设，不能改写已添加安排；可移除相关规则后如实记录"}
        }
        val byId=foods.associateBy {it.id}
        val sources=involved.sorted().map {id->
            requireNotNull(byId[id]) {"比例食品不存在"}.also {food->
                food.validate()
                require(!food.isDeleted && food.unit==groups.getValue(id).single().unit) {"比例食品已删除或单位改变，请先重新选择"}
            }
        }
        val inputs=sources.map {WeightRatioInput(it.id,it.name,if(it.unit=="g") 1.0 else it.gramsPerPortion,
            if(it.unit=="份") requireNotNull(PortionQuantities.recommendStep(it.step)) {"旧份数步长无法按整数使用，请先修改食品调整单位"} else it.step,it.maximum)}
        val rounding=WeightRatios.round(items.filter {it.foodId in involved}.associate {it.foodId to it.quantity},rules,inputs,enforceMaximum=false)
        val updated=items.sortedBy {it.id}.map {item->
            if(item.foodId !in involved) item else {
                val food=byId.getValue(item.foodId);val q=rounding.quantities.getValue(item.foodId)
                PortionQuantities.requireWhole(item.unit,q)
                item.copy(name=food.name,quantity=q,nutrition=food.nutrition*(q/food.basisAmount),
                    costYuan=FoodPricing.cost(food.pricePerUnitYuan,q)).also {it.validate()}
            }
        }
        FoodPresetEstimates.calculate(plan.referenceExpenditureKcal,updated.map {it.nutrition})
        return PresetRatioPreview(plan,items.sortedBy {it.id},sources,updated,rounding)
    }
}

@Entity(tableName="food_preset_items",indices=[Index("day"),Index(value=["appliedIntakeId"],unique=true)])
data class FoodPresetItem(@PrimaryKey(autoGenerate=true) val id:Long=0,val day:Long,val foodId:Long,
    val name:String,val unit:String,val quantity:Double,@Embedded val nutrition:Nutrition,
    val costYuan:Double?=null,val appliedIntakeId:Long?=null) {
    fun validate() {
        LocalDate.ofEpochDay(day)
        require(id>=0 && foodId>0 && name.isNotBlank() && unit in setOf("g","份")) {"预设食品资料无效"}
        require(quantity.isFinite() && quantity>0) {"预设数量须为大于0的有效数值"}
        require(nutrition.values().all {it.isFinite() && it>=0} && nutrition.kcal>0) {"预设营养快照无效"}
        require(costYuan==null || (costYuan.isFinite() && costYuan>=0)) {"预设费用快照无效"}
        require(appliedIntakeId==null || appliedIntakeId>0) {"预设添加标记无效"}
    }
}

@Dao
interface FoodPresetDao {
    @Query("SELECT * FROM food_preset_plans ORDER BY day") fun plans():Flow<List<FoodPresetPlan>>
    @Query("SELECT * FROM food_preset_items ORDER BY day,id") fun items():Flow<List<FoodPresetItem>>
    @Query("SELECT * FROM food_preset_plans ORDER BY day") suspend fun allPlans():List<FoodPresetPlan>
    @Query("SELECT * FROM food_preset_items ORDER BY id") suspend fun allItems():List<FoodPresetItem>
    @Query("SELECT * FROM food_preset_plans WHERE day=:day") suspend fun plan(day:Long):FoodPresetPlan?
    @Query("SELECT * FROM food_preset_items WHERE day=:day ORDER BY id") suspend fun itemsForDay(day:Long):List<FoodPresetItem>
    @Query("SELECT * FROM food_preset_items WHERE id=:id") suspend fun item(id:Long):FoodPresetItem?
    @Upsert suspend fun savePlan(plan:FoodPresetPlan)
    @Insert suspend fun insertItem(item:FoodPresetItem):Long
    @Update suspend fun updateItem(item:FoodPresetItem):Int
    @Query("DELETE FROM food_preset_items WHERE id=:id") suspend fun deleteItem(id:Long):Int
    @Query("DELETE FROM food_preset_items WHERE day=:day") suspend fun deleteItemsForDay(day:Long)
    @Query("DELETE FROM food_preset_plans WHERE day=:day") suspend fun deletePlan(day:Long)
    @Query("DELETE FROM food_preset_items") suspend fun clearItemsForRestore()
    @Query("DELETE FROM food_preset_plans") suspend fun clearPlansForRestore()
}
