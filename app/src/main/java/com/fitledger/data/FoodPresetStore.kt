package com.fitledger.data

import androidx.room.withTransaction
import com.fitledger.domain.*
import java.time.LocalDate

/** Persistent planning is separate from measured days and consumption. Nothing is applied on startup. */
class FoodPresetStore(private val db:LedgerDatabase) {
    val dao=db.foodPresetDao()
    private val ledger get()=db.dao()
    private fun today()=LocalDate.now().toEpochDay()
    private fun ensureSameDate(expected:Long) {
        require(today()==expected) {"日期已变化，请重新打开预设后操作"}
    }
    private suspend fun requireMeasured(day:Long):BodyRecord {
        val body=requireNotNull(ledger.body(day)) {"请先完成今日身体数据"}
        requireNotNull(ledger.target(day)) {"请先生成今日目标"}
        return body
    }
    private suspend fun reference(day:Long,baseDay:Long):FoodPresetPlan {
        val body=requireMeasured(baseDay)
        val base=TargetPlanning.baseExpenditure(ledger.target(baseDay),body)
        val extra=ledger.allExercises().filter {it.day==baseDay && it.included}.sumOf {it.kcal}
        require(extra.isFinite() && extra>=0) {"参考日期的运动消耗超出计算范围"}
        val expenditure=base?.let {
            require(it.isFinite() && it>0 && (it+extra).isFinite()) {"参考日期的消耗无效或超出计算范围"}
            it+extra
        }
        return FoodPresetPlan(day,baseDay,expenditure,body.weight).also {it.validate()}
    }
    private suspend fun editablePlan(day:Long,now:Long):FoodPresetPlan {
        require(day in now..now+7) {"只能编辑未来1至7天预设或今天已经到期的预设"}
        requireMeasured(now)
        return if(day>now) dao.plan(day)?.also {it.validate()} ?: reference(day,now)
            else requireNotNull(dao.plan(day)) {"今天没有提前保存的预设，请直接记录实际饮食"}.also {it.validate()}
    }
    private fun snapshot(day:Long,food:Food,quantity:Double):FoodPresetItem {
        food.validate()
        require(!food.isDeleted) {"${food.name}已删除，请重新选择食品"}
        require(quantity.isFinite() && quantity>0) {"预设数量须为大于0的有效数值"}
        PortionQuantities.requireWhole(food.unit,quantity)
        val n=food.nutrition*(quantity/food.basisAmount)
        val cost=FoodPricing.cost(food.pricePerUnitYuan,quantity)
        return FoodPresetItem(day=day,foodId=food.id,name=food.name,unit=food.unit,
            quantity=quantity,nutrition=n,costYuan=cost).also {it.validate()}
    }
    suspend fun saveItem(day:Long,foodId:Long,quantity:Double,itemId:Long?=null,expectedUnit:String?=null):Long=db.withTransaction {
        val now=today()
        val plan=editablePlan(day,now)
        val old=itemId?.let {id->
            require(id>0) {"预设条目编号无效"}
            requireNotNull(dao.item(id)) {"预设已删除，请刷新"}.also {
                require(it.day==day && it.appliedIntakeId==null) {"已添加的预设只能查看，实际摄入可在今日单独修改"}
            }
        }
        require(old!=null || dao.itemsForDay(day).size<1000) {"单日预设条目过多，请先整理"}
        val next=snapshot(day,requireNotNull(ledger.food(foodId)) {"食品不存在，请重新选择"},quantity)
        if(old!=null && old.foodId!=foodId) {
            require(WeightRatios.decode(plan.ratioRules).none {it.foodId==old.foodId || it.referenceFoodId==old.foodId}) {"原食品参与重量比例，请先移除对应规则再更换食品"}
        }
        require(expectedUnit==null || expectedUnit==next.unit) {"食品计量单位已变化，请重新选择并输入数量"}
        require(old==null || old.foodId!=foodId || old.unit==next.unit) {"食品计量单位已变化，不能把原数量套用到新单位，请删除该未添加预设后重新选择"}
        ensureSameDate(now)
        dao.savePlan(plan)
        if(old==null) dao.insertItem(next)
        else {check(dao.updateItem(next.copy(id=old.id))==1);old.id}
    }
    suspend fun removeItem(day:Long,itemId:Long)=db.withTransaction {
        val now=today()
        val plan=editablePlan(day,now)
        val item=requireNotNull(dao.item(itemId)) {"预设已删除"}
        require(item.day==day && item.appliedIntakeId==null) {"已添加预设保留标记；请在今日摄入中修改或删除实际记录"}
        ensureSameDate(now)
        check(dao.deleteItem(itemId)==1)
        val remaining=dao.itemsForDay(day)
        if(remaining.isEmpty()) dao.deletePlan(day) else pruneRatios(plan,remaining)
        Unit
    }
    /** Adopted markers outlive deleted consumption and cannot be cleared into an accidental re-apply. */
    suspend fun clear(day:Long)=db.withTransaction {
        val now=today()
        val plan=editablePlan(day,now)
        val items=dao.itemsForDay(day)
        ensureSameDate(now)
        items.filter {it.appliedIntakeId==null}.forEach {check(dao.deleteItem(it.id)==1)}
        if(items.none {it.appliedIntakeId!=null}) dao.deletePlan(day) else pruneRatios(plan,items.filter {it.appliedIntakeId!=null})
        Unit
    }
    suspend fun refreshReference(day:Long)=db.withTransaction {
        val now=today()
        require(day in now+1..now+7) {"只有未来1至7天预设可以用今日消耗重新估算；已经到期的预估保持原快照"}
        val plan=reference(day,now).copy(ratioRules=dao.plan(day)?.ratioRules ?: "")
        ensureSameDate(now)
        dao.savePlan(plan)
    }
    private suspend fun pruneRatios(plan:FoodPresetPlan,items:List<FoodPresetItem>) {
        val ids=items.map {it.foodId}.toSet()
        val encoded=WeightRatios.encode(WeightRatios.decode(plan.ratioRules).filter {it.foodId in ids && it.referenceFoodId in ids})
        if(encoded!=plan.ratioRules) dao.savePlan(plan.copy(ratioRules=encoded))
    }
    /** Stores rules only; quantities are unchanged until their separate preview is confirmed. */
    suspend fun saveRatios(day:Long,encoded:String)=db.withTransaction {
        val now=today();val plan=editablePlan(day,now)
        val rules=WeightRatios.decode(encoded)
        val next=plan.copy(ratioRules=WeightRatios.encode(rules))
        if(rules.isNotEmpty()) FoodPresetRatios.preview(next,dao.itemsForDay(day),ledger.allFoods())
        ensureSameDate(now);dao.savePlan(next)
    }
    suspend fun previewRatios(day:Long):PresetRatioPreview=db.withTransaction {
        val now=today();val plan=editablePlan(day,now)
        FoodPresetRatios.preview(plan,dao.itemsForDay(day),ledger.allFoods()).also {ensureSameDate(now)}
    }
    /** Recomputes using fresh rows before any write; the saved preview cannot change behind the user. */
    suspend fun applyRatios(expected:PresetRatioPreview)=db.withTransaction {
        val now=today();val plan=editablePlan(expected.plan.day,now)
        val fresh=FoodPresetRatios.preview(plan,dao.itemsForDay(plan.day),ledger.allFoods())
        require(fresh==expected) {"预设、比例或食品资料已变化，请重新预览后确认；未修改任何数量"}
        ensureSameDate(now)
        fresh.updated.filter {next->next!=fresh.before.first {it.id==next.id}}.forEach {PortionQuantities.requireWhole(it.unit,it.quantity);check(dao.updateItem(it)==1)}
    }
    suspend fun apply(day:Long,itemIds:List<Long>):Int=db.withTransaction {
        val now=today()
        require(day==now) {"预设日期还未到或已过期，不能提前或补写为今日摄入"}
        requireMeasured(now)
        val plan=requireNotNull(dao.plan(day)) {"当天没有可添加的预设"}.also {it.validate()}
        require(itemIds.isNotEmpty() && itemIds.all {it>0} && itemIds.distinct().size==itemIds.size) {"请选择不重复的待添加预设"}
        val items=itemIds.map {id->
            requireNotNull(dao.item(id)) {"所选预设已删除，请刷新"}.also {
                require(it.day==day && it.appliedIntakeId==null) {"所选预设已经添加或日期已变化，不会重复记录"}
                it.validate()
            }
        }
        if(plan.ratioRules.isNotEmpty()) {
            val rules=WeightRatios.decode(plan.ratioRules)
            val involved=rules.flatMap {listOf(it.foodId,it.referenceFoodId)}.toSet()
            val grouped=dao.itemsForDay(day).groupBy {it.foodId}
            require(involved.all {grouped[it]?.size==1}) {"比例食品缺失或重复，请先核对安排和规则"}
            val foods=ledger.allFoods().associateBy {it.id}
            val inputs=involved.map {id->
                val food=requireNotNull(foods[id]) {"比例食品不存在"}
                require(!food.isDeleted && grouped.getValue(id).single().unit==food.unit) {"比例食品已删除或单位改变，请先处理"}
                WeightRatioInput(food.id,food.name,if(food.unit=="g") 1.0 else food.gramsPerPortion,
                    if(food.unit=="份") requireNotNull(PortionQuantities.recommendStep(food.step)) {"旧份数步长无法按整数使用，请先修改食品调整单位"} else food.step,food.maximum)
            }
            WeightRatios.deviations(involved.associateWith {grouped.getValue(it).single().quantity},rules,inputs,enforceMaximum=false)
        }
        // Re-read all food rows before writing any intake. A changed label requires a new review.
        items.forEach {item->
            val fresh=snapshot(day,requireNotNull(ledger.food(item.foodId)) {"预设食品不存在"},item.quantity)
            require(fresh.name==item.name && fresh.unit==item.unit && fresh.nutrition==item.nutrition && fresh.costYuan==item.costYuan) {
                "${item.name}的食品资料、单位或价格已变化，请保存数量以刷新预设资料，再核对确认"
            }
        }
        FoodPresetEstimates.calculate(null,items.map {it.nutrition})
        ensureSameDate(now)
        items.forEach {item->
            val intakeId=ledger.insertIntake(Intake(day=day,foodId=item.foodId,name=item.name,unit=item.unit,
                quantity=item.quantity,nutrition=item.nutrition,costYuan=item.costYuan))
            check(dao.updateItem(item.copy(appliedIntakeId=intakeId))==1)
        }
        items.size
    }
}
