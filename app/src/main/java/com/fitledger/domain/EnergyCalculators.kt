package com.fitledger.domain

import java.time.LocalDate
import java.time.Period
import kotlin.math.abs

enum class Sex(val label:String) { MALE("男性"), FEMALE("女性") }
enum class ActivityLevel(val label:String,val factor:Double) {
    SEDENTARY("久坐不动 · 很少运动",1.2), LIGHT("轻度 · 每周 1～3 天",1.375),
    MODERATE("中度 · 每周 3～5 天",1.55), HEAVY("重度 · 每周 6～7 天",1.725),
    EXTREME("极重度 · 剧烈运动/高强度体力工作",1.9)
}
data class BodyProfile(val sex:Sex,val heightCm:Double,val birthday:Long,val activityFactor:Double) {
    fun validate(day:Long) {
        require(heightCm.isFinite() && heightCm in 50.0..260.0) { "身高请输入 50～260 cm" }
        AgeCalculator.age(birthday,day)
        require(activityFactor.isFinite() && activityFactor>0 && activityFactor<=5) { "活动系数须大于 0，且不超过 5" }
    }
}
object AgeCalculator {
    fun age(birthday:Long,day:Long):Int {
        val birth=LocalDate.ofEpochDay(birthday);val date=LocalDate.ofEpochDay(day)
        require(!birth.isAfter(date)) { "生日不能晚于计算日期" }
        return Period.between(birth,date).years.also {require(it in 0..120) { "请检查生日，年龄应在 0～120 周岁内" }}
    }
}
object BmrCalculator {
    fun calculate(weightKg:Double,heightCm:Double,sex:Sex,age:Int):Double {
        require(weightKg.isFinite() && weightKg in 20.0..400.0 && heightCm.isFinite() && heightCm in 50.0..260.0 && age in 0..120) { "BMR 计算参数无效" }
        val bmr=when(sex) {
            Sex.MALE -> 88.362+13.397*weightKg+4.799*heightCm-5.677*age
            Sex.FEMALE -> 447.593+9.247*weightKg+3.098*heightCm-4.330*age
        }
        require(bmr.isFinite() && bmr>0) { "当前资料计算出的 BMR 无效，请检查资料" }
        return bmr
    }
}
/** All energy is stored in kcal. Missing legacy snapshots stay null in Room. */
data class DailyEnergySnapshot(val age:Int,val heightCm:Double,val sex:String,val birthday:Long,
    val activityFactor:Double,val bmrKcal:Double,val expenditureKcal:Double) {
    fun validate(weight:Double,day:Long) {
        val expected=EnergyExpenditureCalculator.snapshot(weight,BodyProfile(Sex.valueOf(sex),heightCm,birthday,activityFactor),day)
        require(age==expected.age && bmrKcal.isFinite() && expenditureKcal.isFinite() && abs(bmrKcal-expected.bmrKcal)<0.0001 && abs(expenditureKcal-expected.expenditureKcal)<0.0001) { "每日能量快照不一致" }
    }
}
object EnergyExpenditureCalculator {
    fun calculate(bmrKcal:Double,factor:Double):Double {
        require(bmrKcal.isFinite() && bmrKcal>0 && factor.isFinite() && factor>0 && factor<=5) { "消耗计算参数无效" }
        return (bmrKcal*factor).also {require(it.isFinite() && it>0)}
    }
    fun snapshot(weight:Double,profile:BodyProfile,day:Long):DailyEnergySnapshot {
        profile.validate(day)
        val age=AgeCalculator.age(profile.birthday,day)
        val bmr=BmrCalculator.calculate(weight,profile.heightCm,profile.sex,age)
        return DailyEnergySnapshot(age,profile.heightCm,profile.sex.name,profile.birthday,profile.activityFactor,bmr,calculate(bmr,profile.activityFactor))
    }
}
enum class IntakeMode(val label:String) { FIXED("直接设置目标摄入"), EXPENDITURE_ADJUSTMENT("估算消耗 + 缺口/盈余调整") }
data class MacroRatio(val carbs:Double=5.0,val protein:Double=3.0,val fat:Double=2.0) {
    fun fractions():List<Double> {
        val values=listOf(carbs,protein,fat)
        require(values.all {it.isFinite() && it>=0}) { "比例必须是非负有限数，支持小数" }
        val max=values.max()
        require(max>0) { "三项比例不能全部为 0" }
        // Scaling avoids overflow when summing large but finite ratios.
        val scaled=values.map {it/max}; val total=scaled.sum()
        return scaled.map {it/total}
    }
}
object MacroRatioCalculator {
    fun calculate(targetKcal:Double,ratio:MacroRatio):Nutrition {
        require(targetKcal.isFinite() && targetKcal>0) { "目标摄入必须大于 0；请调整热量缺口或检查资料" }
        val f=ratio.fractions()
        return Nutrition(targetKcal,targetKcal*f[0]/4,targetKcal*f[1]/4,targetKcal*f[2]/9)
    }
}
