package com.fitledger.domain

/** Yuan per the food's native g or portion; no rounding is applied to stored calculations. */
object FoodPricing {
    fun perUnit(totalYuan:Double,packages:Double,quantityPerPackage:Double):Double {
        require(totalYuan.isFinite() && totalYuan>=0) {"总价格须为不小于 0 的有效数值"}
        require(packages.isFinite() && packages>0 && quantityPerPackage.isFinite() && quantityPerPackage>0) {"包数和每包数量须大于 0"}
        val quantity=packages*quantityPerPackage
        require(quantity.isFinite() && quantity>0) {"购买总量超出计算范围"}
        return (totalYuan/quantity).also {require(it.isFinite()) {"单价超出计算范围"}}
    }
    fun cost(pricePerUnitYuan:Double?,quantity:Double):Double? {
        require(quantity.isFinite() && quantity>0) {"数量须大于 0"}
        return pricePerUnitYuan?.let {price->
            require(price.isFinite() && price>=0) {"食品单价无效"}
            (price*quantity).also {require(it.isFinite()) {"费用超出计算范围"}}
        }
    }
}
