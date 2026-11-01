package com.fitledger.data

import androidx.room.*
import com.fitledger.domain.*
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.abs

@Entity(tableName="food_combos")
data class FoodCombo(@PrimaryKey(autoGenerate=true) val id:Long=0,val name:String,val items:String,
    @ColumnInfo(defaultValue="''") val ratioRules:String="") {
    fun validate() {
        require(id>=0 && name.isNotBlank() && name.length<=100 && name.none {it.code<32}) {"组合名称须为 100 字以内的有效文字"}
        val foods=ComboCodec.decode(items).map {it.foodId}.toSet()
        require(WeightRatios.decode(ratioRules).all {it.foodId in foods && it.referenceFoodId in foods}) {"组合比例只能引用组合内食品"}
    }
}
data class ComboItem(val foodId:Long,val unit:String,val quantity:Double)
object ComboCodec {
    fun encode(items:List<ComboItem>):String {
        validate(items)
        return JSONArray(items.map {JSONObject().put("foodId",it.foodId).put("unit",it.unit).put("quantity",it.quantity)}).toString()
    }
    fun decode(text:String):List<ComboItem> {
        require(text.length<=20000) {"组合内容过长"}
        val a=JSONArray(text);require(a.length() in 1..50) {"组合须包含 1 至 50 种食品"}
        val items=(0 until a.length()).map {i ->
            val j=a.getJSONObject(i)
            require(j.keys().asSequence().toSet()==setOf("foodId","unit","quantity")) {"组合字段无效"}
            val id=j.get("foodId");val amount=j.get("quantity");val unit=j.get("unit")
            require(id is Number && id.toDouble().isFinite() && amount is Number && unit is String) {"组合编号、单位或数量无效"}
            val exact=requireNotNull(runCatching {id.toString().toBigDecimal().longValueExact()}.getOrNull()) {"组合食品编号须为整数"}
            ComboItem(exact,unit,amount.toDouble())
        }
        validate(items)
        return items
    }
    private fun validate(items:List<ComboItem>) {
        require(items.size in 1..50 && items.map {it.foodId}.distinct().size==items.size) {"组合须包含 1 至 50 种不重复食品"}
        require(items.all {it.foodId>0 && it.unit in setOf("g","份") && it.quantity.isFinite() && it.quantity>0}) {"组合食品、单位或数量无效"}
    }
}
@Dao
interface FeatureDao {
    @Query("SELECT * FROM food_combos ORDER BY name,id") fun combos():Flow<List<FoodCombo>>
    @Query("SELECT * FROM food_combos ORDER BY id") suspend fun allCombos():List<FoodCombo>
    @Query("SELECT * FROM food_combos WHERE id=:id") suspend fun combo(id:Long):FoodCombo?
    @Upsert suspend fun saveCombo(combo:FoodCombo):Long
    @Query("DELETE FROM food_combos WHERE id=:id") suspend fun deleteCombo(id:Long):Int
    @Query("DELETE FROM food_combos") suspend fun clearCombosForRestore()
}
data class CopyResult(val count:Int,val warnings:List<String>)

/** All historical mutations read fresh rows inside a transaction and leave body/target untouched. */
class FeatureStore(private val db:LedgerDatabase) {
    val dao=db.featureDao()
    private val ledger get()=db.dao()

    private suspend fun requireDay(day:Long):BodyRecord {
        require(day<=LocalDate.now().toEpochDay() && runCatching {LocalDate.ofEpochDay(day)}.isSuccess) {"不能补记未来日期"}
        val body=requireNotNull(ledger.body(day)) {"这天缺少身体记录，无法补记；历史身体数据只读"}
        requireNotNull(ledger.target(day)) {"这天缺少目标快照，无法补记"}
        return body
    }
    private fun validateQuantity(amount:Double) {require(amount.isFinite() && amount>0) {"数量须为大于 0 的有效数值"}}
    private fun snapshot(food:Food,day:Long,amount:Double,expectedUnit:String?=null):Intake {
        validateQuantity(amount);food.validate();PortionQuantities.requireWhole(food.unit,amount)
        require(!food.isDeleted) {"${food.name}已删除，请先从回收站恢复"}
        require(expectedUnit==null || food.unit==expectedUnit) {"${food.name}的单位已变化，请重新选择"}
        val n=food.nutrition*(amount/food.basisAmount)
        require(n.values().all {it.isFinite() && it>=0} && n.kcal>0) {"${food.name}的营养超出计算范围"}
        return Intake(day=day,foodId=food.id,name=food.name,unit=food.unit,quantity=amount,nutrition=n,costYuan=FoodPricing.cost(food.pricePerUnitYuan,amount))
    }
    suspend fun recordFood(day:Long,foodId:Long,quantity:Double):Long=db.withTransaction {
        requireDay(day)
        ledger.insertIntake(snapshot(requireNotNull(ledger.food(foodId)) {"食品不存在"},day,quantity))
    }
    suspend fun editIntake(day:Long,id:Long,quantity:Double)=db.withTransaction {
        requireDay(day);validateQuantity(quantity)
        val saved=requireNotNull(ledger.intake(id)) {"这条摄入已删除"}
        require(saved.day==day && saved.source in setOf("FOOD","ROUTINE")) {"只能修改所选日期的食品摄入量"}
        if(quantity!=saved.quantity) PortionQuantities.requireWhole(saved.unit,quantity)
        validateQuantity(saved.quantity)
        val ratio=quantity/saved.quantity
        val n=saved.nutrition*ratio
        val cost=saved.costYuan?.times(ratio)
        require(n.values().all {it.isFinite() && it>=0} && (cost==null || cost.isFinite() && cost>=0)) {"修改后的营养或费用超出计算范围"}
        check(ledger.updateIntake(saved.copy(quantity=quantity,nutrition=n,costYuan=cost))==1)
        Unit
    }
    suspend fun addDirect(day:Long,carbs:Double,protein:Double,fat:Double):Long=db.withTransaction {
        requireDay(day)
        ledger.insertIntake(Intake(day=day,foodId=null,name="直接添加营养",unit="次",quantity=1.0,nutrition=DirectNutrition.calculate(carbs,protein,fat),source="DIRECT"))
    }
    suspend fun editDirect(day:Long,id:Long,carbs:Double,protein:Double,fat:Double)=db.withTransaction {
        requireDay(day)
        val saved=requireNotNull(ledger.intake(id)) {"这条摄入已删除"}
        require(saved.day==day && saved.source=="DIRECT") {"只能修改所选日期的直接营养记录"}
        check(ledger.updateIntake(saved.copy(nutrition=DirectNutrition.calculate(carbs,protein,fat)))==1)
        Unit
    }
    suspend fun addEnergy(day:Long,kcal:Double,name:String="仅热量记录",costYuan:Double?=null):Long=db.withTransaction {
        requireDay(day)
        ledger.insertIntake(energyIntake(day,kcal,name,costYuan))
    }
    suspend fun editEnergy(day:Long,id:Long,kcal:Double,name:String="仅热量记录",costYuan:Double?=null)=db.withTransaction {
        requireDay(day)
        val old=requireNotNull(ledger.intake(id)) {"这条摄入已删除"}
        require(old.day==day && old.source=="ENERGY") {"只能修改所选日期的仅热量记录"}
        val updated=energyIntake(day,kcal,name,costYuan).copy(id=old.id)
        check(ledger.updateIntake(updated)==1)
        Unit
    }
    /** Requires explicit UI confirmation: completed macro values replace the old kcal estimate. */
    suspend fun completeEnergy(day:Long,id:Long,carbs:Double,protein:Double,fat:Double)=db.withTransaction {
        requireDay(day)
        val old=requireNotNull(ledger.intake(id)) {"这条摄入已删除"}
        require(old.day==day && old.source=="ENERGY") {"只能补全所选日期的仅热量记录"}
        val nutrition=DirectNutrition.calculate(carbs,protein,fat)
        check(ledger.updateIntake(old.copy(nutrition=nutrition,source="DIRECT"))==1)
        Unit
    }
    suspend fun removeIntake(day:Long,id:Long):Intake=db.withTransaction {
        requireDay(day)
        val saved=requireNotNull(ledger.intake(id)) {"这条摄入已删除"};require(saved.day==day) {"摄入日期已变化"}
        ledger.deleteIntake(id,day)
        // The ROUTINE application marker remains, including during the undo window.
        saved
    }
    suspend fun restoreIntake(snapshot:Intake)=db.withTransaction {
        requireDay(snapshot.day)
        require(snapshot.id>0 && ledger.intake(snapshot.id)==null) {"这条摄入已恢复或编号已被占用"}
        validateFrozenIntake(snapshot)
        snapshot.foodId?.let {requireNotNull(ledger.food(it)) {"原食品资料不存在，无法恢复"}}
        if(snapshot.source=="ROUTINE") require(db.dailyEatingDao().allApplications().any {it.day==snapshot.day && it.intakeId==snapshot.id}) {"每日必吃执行标记不存在"}
        ledger.insertIntake(snapshot)
        Unit
    }
    private fun validateFrozenIntake(saved:Intake) {
        validateQuantity(saved.quantity)
        require(saved.name.isNotBlank() && saved.nutrition.values().all {it.isFinite() && it>=0} && (saved.costYuan==null || saved.costYuan.isFinite() && saved.costYuan>=0)) {"摄入快照无效"}
        when(saved.source) {
            "FOOD","ROUTINE" -> require(saved.foodId!=null && saved.foodId>0 && saved.unit in setOf("g","份")) {"食品摄入快照无效"}
            "DIRECT" -> {
                require(saved.foodId==null && saved.unit=="次" && saved.quantity==1.0) {"直接营养快照无效"}
                require(abs(DirectNutrition.calculate(saved.nutrition.carbs,saved.nutrition.protein,saved.nutrition.fat).kcal-saved.nutrition.kcal)<1e-6) {"直接营养快照不一致"}
            }
            "ENERGY" -> IntakeNutritionTotals.validateEnergy(saved)
            else -> error("摄入来源无效")
        }
    }
    suspend fun saveExercise(day:Long,id:Long?=null,type:Cardio?,minutes:Double?,manualKcal:Double?,included:Boolean=true):Long=db.withTransaction {
        val body=requireDay(day)
        if(id!=null) require(requireNotNull(ledger.exercise(id)) {"这条运动已删除"}.day==day) {"运动日期已变化"}
        val saved=if(type==null) Exercise(id=id?:0,day=day,type="MANUAL",minutes=null,met=null,weight=null,kcal=requireNotNull(manualKcal) {"请填写额外消耗"},included=included)
            else Exercise(id=id?:0,day=day,type=type.name,minutes=requireNotNull(minutes) {"请填写运动时间"},met=type.met,weight=body.weight,kcal=ExerciseCalculator.extra(body.weight,minutes,type.met),included=included)
        saved.validate()
        if(id==null) ledger.insertExercise(saved) else {check(ledger.updateExercise(saved)==1);id}
    }
    suspend fun removeExercise(day:Long,id:Long):Exercise=db.withTransaction {
        requireDay(day)
        val saved=requireNotNull(ledger.exercise(id)) {"这条运动已删除"};require(saved.day==day) {"运动日期已变化"}
        ledger.deleteExercise(id,day);saved
    }
    suspend fun restoreExercise(snapshot:Exercise)=db.withTransaction {
        requireDay(snapshot.day);snapshot.validate()
        require(snapshot.id>0 && ledger.exercise(snapshot.id)==null) {"这条运动已恢复或编号已被占用"}
        ledger.insertExercise(snapshot)
        Unit
    }
    suspend fun restoreFood(id:Long):Boolean=db.withTransaction {
        val food=requireNotNull(ledger.food(id)) {"食品不存在"};require(food.isDeleted) {"食品已经恢复"}
        val foods=ledger.allFoods()
        val originalZone=food.zoneId?.let {ledger.zone(it)}
        val movedToUnassigned=food.zoneId!=null && (originalZone==null ||
            !FoodZoneQuotas.matchesUnit(food,originalZone) || FoodZoneQuotas.zoneIssues(originalZone,foods).isNotEmpty() ||
            (originalZone.limitUnit=="PORTION" && food.zonePortionSize!=1.0))
        val restoredZoneId=if(movedToUnassigned) null else food.zoneId
        val max=(if(restoredZoneId==null) foods.filter {!it.isDeleted && it.zoneId==null}.map {it.sortOrder}+ledger.allZones().map {it.sortOrder}
            else foods.filter {!it.isDeleted && it.zoneId==restoredZoneId}.map {it.sortOrder}).maxOrNull()?:-1L
        require(max<Long.MAX_VALUE) {"排序值已达上限，请先调整顺序"}
        ledger.saveFood(food.copy(isDeleted=false,zoneId=restoredZoneId,sortOrder=max+1))
        movedToUnassigned
    }
    suspend fun saveCombo(combo:FoodCombo):Long=db.withTransaction {
        val normalized=combo.copy(name=combo.name.trim());normalized.validate()
        if(combo.id!=0L) requireNotNull(dao.combo(combo.id)) {"组合已删除，请重新添加"}
        val items=ComboCodec.decode(combo.items)
        val foods=items.map {item ->requireNotNull(ledger.food(item.foodId)) {"组合食品不存在"}.also {snapshot(it,0,item.quantity,item.unit)}}
        WeightRatios.deviations(items.associate {it.foodId to it.quantity},WeightRatios.decode(combo.ratioRules),foodRatioInputs(foods),enforceMaximum=false)
        val id=dao.saveCombo(normalized)
        if(normalized.id==0L) id else normalized.id
    }
    suspend fun deleteCombo(id:Long)=db.withTransaction {
        require(id>0 && dao.deleteCombo(id)==1) {"组合已删除"};Unit
    }
    suspend fun recordCombo(day:Long,comboId:Long,quantities:Map<Long,Double>?=null):Int=db.withTransaction {
        requireDay(day)
        val combo=requireNotNull(dao.combo(comboId)) {"组合已删除"}
        val items=ComboCodec.decode(combo.items)
        val selected=quantities?:items.associate {it.foodId to it.quantity}
        require(selected.isNotEmpty() && selected.keys.all {id->items.any {it.foodId==id}}) {"请至少选择一项组合食品，且不能添加组合外的食品"}
        // A combo is a convenience for recording actual food. Template ratios never restrict
        // truthful manual intake; recommendation adoption validates ratios separately.
        val snapshots=items.filter {it.foodId in selected}.map {snapshot(requireNotNull(ledger.food(it.foodId)) {"组合食品不存在"},day,requireNotNull(selected[it.foodId]),it.unit)}
        snapshots.forEach {ledger.insertIntake(it)}
        snapshots.size
    }
    /** FOOD values are native quantities; DIRECT/ENERGY values multiply their saved snapshots. */
    suspend fun copyIntakesToToday(sourceDay:Long,items:Map<Long,Double>):CopyResult=db.withTransaction {
        copySelected(sourceDay,items,emptySet())
    }
    suspend fun copyToToday(sourceDay:Long,intakeIds:Set<Long>,exerciseIds:Set<Long>):CopyResult=db.withTransaction {
        val amounts=intakeIds.associateWith {id ->
            val intake=requireNotNull(ledger.intake(id)) {"原摄入已删除，请重新选择"}
            if(intake.source in setOf("DIRECT","ENERGY")) 1.0 else intake.quantity
        }
        copySelected(sourceDay,amounts,exerciseIds)
    }
    private suspend fun copySelected(sourceDay:Long,items:Map<Long,Double>,exerciseIds:Set<Long>):CopyResult {
        val today=LocalDate.now().toEpochDay();require(sourceDay<today) {"请选择过去的日期复制"}
        requireDay(sourceDay);val body=requireDay(today)
        require(items.isNotEmpty() || exerciseIds.isNotEmpty()) {"请至少选择一条记录"}
        val existing=ledger.allIntakes().filter {it.day==today}
        val warnings=mutableListOf<String>()
        val snapshots=items.map {(id,amount) ->
            validateQuantity(amount)
            val saved=requireNotNull(ledger.intake(id)) {"原摄入已删除，请重新选择"};require(saved.day==sourceDay) {"原摄入日期不一致"};validateFrozenIntake(saved)
            if(existing.any {it.foodId==saved.foodId && it.source==(if(saved.source=="ROUTINE") "FOOD" else saved.source) && it.name==saved.name}) warnings+="${saved.name}今天已有记录，本次仍按所选数量复制"
            if(saved.source=="ENERGY") {
                val kcal=saved.nutrition.kcal*amount
                val cost=saved.costYuan?.let {it*amount}
                energyIntake(today,kcal,saved.name,cost)
            } else if(saved.source=="DIRECT") {
                val n=saved.nutrition*amount
                saved.copy(id=0,day=today,quantity=1.0,nutrition=DirectNutrition.calculate(n.carbs,n.protein,n.fat),costYuan=saved.costYuan?.let {cost->(cost*amount).also {require(it.isFinite() && it>=0) {"费用超出计算范围"}}})
            } else snapshot(requireNotNull(ledger.food(requireNotNull(saved.foodId))) {"原食品不存在"},today,amount,saved.unit)
        }
        val exercises=exerciseIds.map {id ->
            val saved=requireNotNull(ledger.exercise(id)) {"原运动已删除，请重新选择"};require(saved.day==sourceDay) {"原运动日期不一致"};saved.validate()
            if(saved.type=="MANUAL") saved.copy(id=0,day=today)
            else saved.copy(id=0,day=today,weight=body.weight,kcal=ExerciseCalculator.extra(body.weight,requireNotNull(saved.minutes),requireNotNull(saved.met))).also {it.validate()}
        }
        snapshots.forEach {ledger.insertIntake(it)};exercises.forEach {ledger.insertExercise(it)}
        return CopyResult(snapshots.size+exercises.size,warnings.distinct())
    }
}
