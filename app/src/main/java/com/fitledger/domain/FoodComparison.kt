package com.fitledger.domain

enum class ComparisonBasis(val label:String) {
    GRAMS_100("每 100 g"), PORTION("每份"), KCAL_100("每 100 kcal"),
    PROTEIN_20("每 20 g 蛋白质"), CARBS_20("每 20 g 碳水"), FAT_20("每 20 g 脂肪")
}
data class ComparisonFood(val id:Long,val name:String,val unit:String,val perUnit:Nutrition,val pricePerUnitYuan:Double?)
enum class ComparisonIssue(val label:String) {
    UNIT("单位不适用"), ZERO_NUTRIENT("该营养量为 0"), UNKNOWN_PRICE("未填写价格"), INVALID("数据无法计算")
}
data class ComparisonQuote(val food:ComparisonFood,val quantity:Double?,val costYuan:Double?,val issues:Set<ComparisonIssue>)

/** Compare the cost of a specified amount, without treating portions as grams. */
object FoodComparison {
    fun quote(food:ComparisonFood,basis:ComparisonBasis):ComparisonQuote {
        val issues=linkedSetOf<ComparisonIssue>()
        if(food.pricePerUnitYuan==null) issues+=ComparisonIssue.UNKNOWN_PRICE
        else if(!food.pricePerUnitYuan.isFinite() || food.pricePerUnitYuan<0) issues+=ComparisonIssue.INVALID
        val quantity=when(basis) {
            ComparisonBasis.GRAMS_100 -> if(food.unit=="g") 100.0 else {issues+=ComparisonIssue.UNIT;null}
            ComparisonBasis.PORTION -> if(food.unit=="份") 1.0 else {issues+=ComparisonIssue.UNIT;null}
            else -> {
                val nutrient=when(basis) {
                    ComparisonBasis.KCAL_100 -> food.perUnit.kcal
                    ComparisonBasis.PROTEIN_20 -> food.perUnit.protein
                    ComparisonBasis.CARBS_20 -> food.perUnit.carbs
                    ComparisonBasis.FAT_20 -> food.perUnit.fat
                    else -> error("Unreachable basis")
                }
                when {
                    !nutrient.isFinite() || nutrient<0 -> {issues+=ComparisonIssue.INVALID;null}
                    nutrient==0.0 -> {issues+=ComparisonIssue.ZERO_NUTRIENT;null}
                    else -> ((if(basis==ComparisonBasis.KCAL_100) 100.0 else 20.0)/nutrient).takeIf {it.isFinite() && it>0}
                        .also {if(it==null) issues+=ComparisonIssue.INVALID}
                }
            }
        }
        val cost=if(issues.isEmpty() && quantity!=null) (food.pricePerUnitYuan!!*quantity).takeIf {it.isFinite()}
            .also {if(it==null) issues+=ComparisonIssue.INVALID} else null
        return ComparisonQuote(food,quantity,cost,issues)
    }
}
