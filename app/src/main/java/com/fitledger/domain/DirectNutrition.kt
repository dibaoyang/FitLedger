package com.fitledger.domain

/** A direct intake is a nutrition snapshot, never a synthetic food or a recommended candidate. */
object DirectNutrition {
    fun calculate(carbs:Double,protein:Double,fat:Double):Nutrition {
        require(listOf(carbs,protein,fat).all {it.isFinite() && it>=0}) { "碳水、蛋白质和脂肪请输入非负数字" }
        val kcal=carbs*4+protein*4+fat*9
        require(kcal.isFinite() && kcal>0) { "至少填写一项大于 0 的营养量" }
        return Nutrition(kcal,carbs,protein,fat)
    }
    fun parse(carbs:String,protein:String,fat:String):Nutrition {
        fun number(value:String)=if(value.isBlank()) 0.0 else value.trim().toDoubleOrNull() ?: Double.NaN
        return calculate(number(carbs),number(protein),number(fat))
    }
}
