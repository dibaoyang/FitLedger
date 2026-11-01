package com.fitledger.domain

data class FoodPresetEstimate(val nutrition:Nutrition,val referenceExpenditureKcal:Double?) {
    val deficitKcal:Double? get()=referenceExpenditureKcal?.minus(nutrition.kcal)
}

/** Preview only: supplied nutrients never become consumption, and a missing reference stays unknown. */
object FoodPresetEstimates {
    fun calculate(referenceExpenditureKcal:Double?,items:List<Nutrition>):FoodPresetEstimate {
        require(referenceExpenditureKcal==null || (referenceExpenditureKcal.isFinite() && referenceExpenditureKcal>0)) {"预设参考消耗无效"}
        var total=Nutrition()
        items.forEach {n->
            require(n.values().all {it.isFinite() && it>=0}) {"预设营养无效"}
            total+=n
            require(total.values().all {it.isFinite() && it>=0}) {"预设营养总量超出计算范围"}
        }
        return FoodPresetEstimate(total,referenceExpenditureKcal)
    }
}
