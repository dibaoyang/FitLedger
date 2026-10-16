package com.fitledger.data

import androidx.room.*
import com.fitledger.domain.*
import kotlin.math.abs

@Entity(tableName="exercises",indices=[Index("day")])
data class Exercise(@PrimaryKey(autoGenerate=true) val id:Long=0,val day:Long,val type:String,
    val minutes:Double?,val met:Double?,val weight:Double?,val kcal:Double,val included:Boolean=true) {
    fun validate() {
        require(kcal.isFinite() && kcal>0 && kcal<=100000) {"请填写有效额外消耗"}
        if(type=="MANUAL") require(minutes==null && met==null && weight==null) {"手填运动数据无效"}
        else {
            Cardio.valueOf(type)
            val expected=ExerciseCalculator.extra(requireNotNull(weight),requireNotNull(minutes),requireNotNull(met))
            require(abs(expected-kcal)<0.000001) {"运动快照不一致"}
        }
    }
}
