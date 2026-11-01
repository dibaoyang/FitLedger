package com.fitledger.data

import com.fitledger.domain.Nutrition

/**
 * ENERGY rows record no macro observation. The old non-null numeric columns hold zero sentinels,
 * never observed zero grams; every macro consumer must use this completeness contract.
 */
val Intake.macrosKnown:Boolean get()=source!="ENERGY"

data class IntakeNutritionSummary(
    val knownNutrition:Nutrition,
    val unknownMacroCount:Int,
    val count:Int
) {
    val kcal:Double get()=knownNutrition.kcal
    val carbs:Double? get()=if(unknownMacroCount==0) knownNutrition.carbs else null
    val protein:Double? get()=if(unknownMacroCount==0) knownNutrition.protein else null
    val fat:Double? get()=if(unknownMacroCount==0) knownNutrition.fat else null
    val completeNutrition:Nutrition? get()=if(unknownMacroCount==0) knownNutrition else null
}

object IntakeNutritionTotals {
    const val INCOMPLETE_MESSAGE="存在仅热量记录，三大营养摄入不完整，请补全或移除这类记录后再按剩余营养推荐"

    fun energy(kcal:Double):Nutrition {
        require(kcal.isFinite() && kcal>0) {"热量须为大于0的有效数值"}
        return Nutrition(kcal,0.0,0.0,0.0)
    }

    fun calculate(entries:List<Intake>):IntakeNutritionSummary {
        var total=Nutrition()
        var unknown=0
        entries.forEach {entry->
            val n=entry.nutrition
            require(n.values().all {it.isFinite() && it>=0}) {"摄入营养无效或超出计算范围"}
            if(entry.macrosKnown) total+=n
            else {
                validateEnergy(entry)
                total+=Nutrition(n.kcal)
                unknown++
            }
            require(total.values().all {it.isFinite() && it>=0}) {"摄入合计超出计算范围"}
        }
        return IntakeNutritionSummary(total,unknown,entries.size)
    }

    fun requireKnown(entries:List<Intake>):Nutrition {
        val summary=calculate(entries)
        return requireNotNull(summary.completeNutrition) {INCOMPLETE_MESSAGE}
    }

    fun validateEnergy(entry:Intake) {
        energy(entry.nutrition.kcal)
        require(entry.source=="ENERGY" && entry.foodId==null && entry.unit=="次" && entry.quantity==1.0 &&
            entry.nutrition.carbs==0.0 && entry.nutrition.protein==0.0 && entry.nutrition.fat==0.0) {"仅热量记录的未知营养标记无效"}
        require(entry.name.isNotBlank() && entry.name.length<=100 && entry.name.none {it.code<32}) {"仅热量记录名称无效"}
        require(entry.costYuan==null || (entry.costYuan.isFinite() && entry.costYuan>=0)) {"费用须为不小于0的有效数值，或留空未计价"}
    }
}

internal fun energyIntake(day:Long,kcal:Double,name:String,costYuan:Double?):Intake {
    val record=Intake(day=day,foodId=null,name=name.trim().ifBlank {"仅热量记录"},unit="次",quantity=1.0,
        nutrition=IntakeNutritionTotals.energy(kcal),source="ENERGY",costYuan=costYuan)
    IntakeNutritionTotals.validateEnergy(record)
    return record
}
