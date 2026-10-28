package com.fitledger.data

import com.fitledger.domain.ZoneLimit
import com.fitledger.domain.QuotaMath
import com.fitledger.domain.Candidate

data class FoodZoneSummary(val zone:FoodZone,val consumedPortions:Double,val remainingDaily:Double?,
    val effectiveRecommendationMaximum:Double?,val incompatibleUnits:Boolean)

/** A zone contains one native food unit. Legacy membership is retained until explicitly reviewed. */
object FoodZoneQuotas {
    fun unitFor(zone:FoodZone):String=when(zone.limitUnit) {"G"->"g";"PORTION"->"份";else->error("分区单位无效")}
    fun matchesUnit(food:Food,zone:FoodZone):Boolean=food.unit==unitFor(zone)
    fun zoneIssues(zone:FoodZone,allFoods:List<Food>):List<Food> =
        allFoods.filter {it.zoneId==zone.id && !it.isDeleted &&
            (!matchesUnit(it,zone) || (zone.limitUnit=="PORTION" && it.zonePortionSize!=1.0))}

    /** One gram is one gram; one food portion is one portion. No cross-unit conversion. */
    fun quotaSize(food:Food,zone:FoodZone):Double? {
        if(!matchesUnit(food,zone) || (zone.limitUnit=="PORTION" && food.zonePortionSize!=1.0)) return null
        return 1.0.takeIf {food.maximum.isFinite() && food.maximum>0}
    }

    /** A missing conversion affects recommendations only; the food remains available for manual intake. */
    fun candidates(allFoods:List<Food>,zones:List<FoodZone>):List<Candidate> {
        val byId=zones.associateBy {it.id}
        val invalidZones=zones.filter {zoneIssues(it,allFoods).isNotEmpty()}.map {it.id}.toSet()
        return allFoods.mapNotNull {food ->
            val zoneId=food.zoneId ?: return@mapNotNull food.candidate()
            val zone=byId[zoneId] ?: return@mapNotNull null
            if(zoneId in invalidZones) return@mapNotNull null
            val size=quotaSize(food,zone) ?: return@mapNotNull null
            food.candidate().copy(zonePortionSize=size)
        }
    }

    fun summaries(zones:List<FoodZone>,allFoods:List<Food>,intakes:List<Intake>,day:Long):List<FoodZoneSummary> {
        val foods=allFoods.associateBy {it.id}
        val today=intakes.filter {it.day==day && it.source in setOf("FOOD","ROUTINE")}
        return zones.map {zone ->
            zone.validate()
            var incompatible=zoneIssues(zone,allFoods).isNotEmpty()
            var consumed=0.0
            today.forEach {intake ->
                val food=foods[intake.foodId]
                if(food?.zoneId==zone.id) {
                    val size=quotaSize(food,zone)
                    if(food.unit!=intake.unit || size==null ||
                        !intake.quantity.isFinite() || intake.quantity<=0) incompatible=true
                    else {
                        val amount=intake.quantity/size
                        if(!amount.isFinite() || amount<=0 || !(consumed+amount).isFinite()) incompatible=true
                        else consumed+=amount
                    }
                }
            }
            val remaining=zone.dailyMaximum?.let {if(incompatible) 0.0 else (it-consumed).coerceAtLeast(0.0)}
            val perBatch=zone.recommendationMaximum?:zone.dailyMaximum
            val maximum=if(incompatible) 0.0 else listOfNotNull(remaining,perBatch).minOrNull()
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
            val summary=requireNotNull(summaries[zoneId]) {"食品分区已变化，请重新推荐"}
            val size=requireNotNull(quotaSize(food,summary.zone)) {"食品单位与分区不符，或旧分区换算尚未确认，请在食品库调整"}
            val amount=quantity/size
            val total=(used[zoneId]?:0.0)+amount
            require(amount.isFinite() && amount>0 && total.isFinite()) {"食品分区换算无效，请检查每分区份的食品数量"}
            used[zoneId]=total
        }
        used.forEach {(id,amount) ->
            val summary=summaries.getValue(id)
            require(!summary.incompatibleUnits) {"${summary.zone.name}的食品或今日记录单位不一致，或旧份数规则尚未确认，请先在食品库调整"}
            val maximum=summary.effectiveRecommendationMaximum
            require(maximum==null || QuotaMath.within(amount,maximum)) {"${summary.zone.name}超出每日剩余或本次推荐上限，请重新推荐"}
        }
    }
}
