package com.fitledger.data

import com.fitledger.domain.*
import org.json.JSONObject

object ProfileCodec {
    fun encode(p:BodyProfile)=JSONObject().put("sex",p.sex.name).put("heightCm",p.heightCm).put("birthday",p.birthday).put("activityFactor",p.activityFactor)
    fun decode(j:JSONObject)=BodyProfile(Sex.valueOf(j.getString("sex")),j.getDouble("heightCm"),j.getLong("birthday"),j.getDouble("activityFactor"))
}
object EnergySnapshotCodec {
    fun encode(e:DailyEnergySnapshot)=JSONObject().put("age",e.age).put("heightCm",e.heightCm).put("sex",e.sex).put("birthday",e.birthday)
        .put("activityFactor",e.activityFactor).put("bmrKcal",e.bmrKcal).put("expenditureKcal",e.expenditureKcal)
    fun decode(j:JSONObject)=DailyEnergySnapshot(j.getInt("age"),j.getDouble("heightCm"),j.getString("sex"),j.getLong("birthday"),j.getDouble("activityFactor"),j.getDouble("bmrKcal"),j.getDouble("expenditureKcal"))
}
