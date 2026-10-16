package com.fitledger.domain

enum class Cardio(val label:String,val met:Double) {
    HILL("爬坡 · 6–10% 坡度、中快步行",7.0), STAIRS("爬楼 · 一般速度",6.8), RUN("跑步 · 一般跑步",8.0)
}
object ExerciseCalculator {
    // Net energy above rest, not gross exercise energy. All stored energy uses kcal.
    fun extra(weight:Double,minutes:Double,met:Double):Double {
        require(weight.isFinite() && weight in 20.0..400.0) {"请先记录有效体重"}
        require(minutes.isFinite() && minutes>0 && minutes<=1440) {"运动时间须大于 0 且不超过 1440 分钟"}
        require(met.isFinite() && met>1 && met<=30) {"运动强度无效"}
        return (met-1)*weight*minutes/60.0
    }
    fun total(base:Double?,extra:Double):Double? = base?.plus(extra)
    fun gap(base:Double?,extra:Double,intake:Double):Double? = total(base,extra)?.minus(intake)
}
