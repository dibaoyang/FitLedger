package com.fitledger.domain

/** Allowed deviation in grams from the complete daily target; null means unlimited. */
data class MacroTolerance(val under: Double? = null, val over: Double? = null) {
    fun validate() {
        require(listOfNotNull(under, over).all { it.isFinite() && it >= 0 }) {
            "营养素允许少于或超过目标的克数须为不小于 0 的有效数值"
        }
    }
}

/** Applies only to recommendations. Existing/manual intakes remain truthful and unrestricted. */
data class RecommendationPolicy(
    val dailyBudgetYuan: Double? = null,
    val carbs: MacroTolerance = MacroTolerance(),
    val protein: MacroTolerance = MacroTolerance(),
    val fat: MacroTolerance = MacroTolerance()
) {
    fun validate() {
        require(dailyBudgetYuan == null || (dailyBudgetYuan.isFinite() && dailyBudgetYuan >= 0)) {
            "每日推荐总预算须为不小于 0 的有效金额"
        }
        carbs.validate(); protein.validate(); fat.validate()
    }
}
