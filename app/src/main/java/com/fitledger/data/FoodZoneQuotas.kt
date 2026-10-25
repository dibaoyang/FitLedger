package com.fitledger.data

import com.fitledger.domain.ZoneLimit
import com.fitledger.domain.QuotaMath

data class FoodZoneSummary(val zone:FoodZone,val consumedPortions:Double,val remainingDaily:Double?,
    val effectiveRecommendationMaximum:Double?,val incompatibleUnits:Boolean)

/** Current membership and conversions are intentional: editing a zone immediately affects today's budget. */
object FoodZoneQuotas {
    fun summaries(zones:List<FoodZone>,allFoods:List<Food>,intakes:List<Intake>,day:Long):List<FoodZoneSummary> {
        val foods=allFoods.associateBy {it.id}
        val today=intakes.filter {it.day==day && it.source in setOf("FOOD","ROUTINE")}
        return zones.map {zone ->
            zone.validate()
            var incompatible=false
            var consumed=0.0
            today.forEach {intake ->
                val food=foods[intake.foodId]
                if(food?.zoneId==zone.id) {
                    if(food.unit!=intake.unit || !food.zonePortionSize.isFinite() || food.zonePortionSize<=0 ||
                        !intake.quantity.isFinite() || intake.quantity<=0) incompatible=true
                    else {
                        val amount=intake.quantity/food.zonePortionSize
                        if(!amount.isFinite() || !(consumed+amount).isFinite()) incompatible=true
                        else consumed+=amount
                    }
                }
            }
            val remaining=zone.dailyMaximum?.let {(it-consumed).coerceAtLeast(0.0)}
            val perBatch=zone.recommendationMaximum?:zone.dailyMaximum
            val maximum=if(incompatible && zone.dailyMaximum!=null) 0.0 else listOfNotNull(remaining,perBatch).minOrNull()
            FoodZoneSummary(zone,consumed,remaining,maximum,incompatible)
        }
    }

    fun limits(zones:List<FoodZone>,allFoods:List<Food>,intakes:List<Intake>,day:Long):List<ZoneLimit> =
        summaries(zones,allFoods,intakes,day).mapNotNull {summary ->
            summary.effectiveRecommendationMaximum?.let {ZoneLimit(summary.zone.id,summary.zone.name,it)}
        }

    /** Called inside the same Room transaction as recommendation adoption, against fresh intake data. */
    fun validateRecommendation(quantities:Map<Long,Double>,foods:List<Food>,zones:List<FoodZone>,intakes:List<Intake>,day:Long) {
        val byId=foods.associateBy {it.id}
        val summaries=summaries(zones,foods,intakes,day).associateBy {it.zone.id}
        val used=mutableMapOf<Long,Double>()
        quantities.toSortedMap().forEach {(id,quantity) ->
            require(quantity.isFinite() && quantity>0) {"摄入量须大于 0"}
            val food=requireNotNull(byId[id]) {"食品不存在，请重新推荐"}
            val zoneId=food.zoneId?:return@forEach
            require(summaries.containsKey(zoneId)) {"食品分区已变化，请重新推荐"}
            val amount=quantity/food.zonePortionSize
            val total=(used[zoneId]?:0.0)+amount
            require(amount.isFinite() && amount>0 && total.isFinite()) {"食品分区换算无效，请检查每分区份的食品数量"}
            used[zoneId]=total
        }
        used.forEach {(id,amount) ->
            val summary=summaries.getValue(id)
            require(!(summary.incompatibleUnits && summary.zone.dailyMaximum!=null)) {"${summary.zone.name}中有今日记录的计量单位已变更，请先核对食品分区与换算"}
            val maximum=summary.effectiveRecommendationMaximum
            require(maximum==null || QuotaMath.within(amount,maximum)) {"${summary.zone.name}超出每日剩余或本次推荐上限，请重新推荐"}
        }
    }
}
