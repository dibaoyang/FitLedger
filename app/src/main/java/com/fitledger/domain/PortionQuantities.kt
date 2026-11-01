package com.fitledger.domain

import java.math.BigDecimal
import java.math.BigInteger
import kotlin.math.floor

/** New portion counts are integers. Persisted legacy decimals are never rounded in place. */
object PortionQuantities {
    fun isWhole(unit:String,quantity:Double):Boolean = quantity.isFinite() && quantity>0 && (unit!="份" || quantity==floor(quantity))
    fun requireWhole(unit:String,quantity:Double) {
        require(quantity.isFinite() && quantity>0) {"数量须为大于0的有效数值"}
        require(unit!="份" || quantity==floor(quantity)) {"份数请填写整数；旧的小数份记录保留原值，不会自动取整"}
    }
    /** Least positive INTEGER that is also a whole multiple of the old decimal step. */
    fun recommendStep(step:Double):Double? {
        if(!step.isFinite() || step<=0) return null
        val decimal=BigDecimal.valueOf(step).stripTrailingZeros()
        val numerator=decimal.unscaledValue().abs()
        val denominator=if(decimal.scale()>0) BigInteger.TEN.pow(decimal.scale()) else BigInteger.ONE
        val whole=if(decimal.scale()<0) numerator*BigInteger.TEN.pow(-decimal.scale()) else numerator/numerator.gcd(denominator)
        return whole.toDouble().takeIf {it.isFinite() && it>0 && it==floor(it)}
    }
    fun format(unit:String,quantity:Double):String {
        if(!quantity.isFinite()) return quantity.toString()
        val text=inputText(quantity)
        return if(unit=="份" && quantity!=floor(quantity)) "$text（旧的小数份）" else text
    }
    fun inputText(quantity:Double):String = if(quantity.isFinite()) BigDecimal.valueOf(quantity).stripTrailingZeros().toPlainString() else quantity.toString()
}
