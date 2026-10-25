package com.fitledger.data

import com.fitledger.domain.Nutrition
import org.junit.Assert.*
import org.junit.Test

class FoodZoneQuotasTest {
    private val day=20000L
    private val zone=FoodZone(1,"面包",3.0)
    private val bread=Food(7,"面包一","份",1.0,Nutrition(100.0,20.0,3.0,1.0),1.0,10.0,true,zoneId=1)
    private val gramBread=bread.copy(id=8,name="面包二",unit="g",basisAmount=50.0,step=25.0,maximum=500.0,zonePortionSize=50.0)
    private fun intake(food:Food,quantity:Double,date:Long=day)=Intake(food.id,date,food.id,food.name,food.unit,quantity,food.nutrition*(quantity/food.basisAmount))

    @Test fun mixedFoodUnitsShareOneDailyBudgetAndBlankRecommendationFollowsDaily() {
        val history=listOf(intake(bread,1.0),intake(gramBread,50.0),intake(bread,100.0,day-1))
        val summary=FoodZoneQuotas.summaries(listOf(zone),listOf(bread,gramBread),history,day).single()
        assertEquals(2.0,summary.consumedPortions,0.0)
        assertEquals(1.0,summary.remainingDaily!!,0.0)
        assertEquals(1.0,summary.effectiveRecommendationMaximum!!,0.0)
        assertFalse(summary.incompatibleUnits)
        FoodZoneQuotas.validateRecommendation(mapOf(7L to 0.5,8L to 25.0),listOf(bread,gramBread),listOf(zone),history,day)
        assertTrue(runCatching {FoodZoneQuotas.validateRecommendation(mapOf(7L to 1.0,8L to 25.0),listOf(bread,gramBread),listOf(zone),history,day)}.isFailure)
    }
    @Test fun recommendationCapIsIndependentAndDailyRemainingAlwaysWins() {
        fun cap(daily:Double?,batch:Double?,eaten:Double)=FoodZoneQuotas.summaries(listOf(zone.copy(dailyMaximum=daily,recommendationMaximum=batch)),listOf(bread),listOf(intake(bread,eaten)),day).single()
        assertEquals(1.0,cap(5.0,1.0,1.0).effectiveRecommendationMaximum!!,0.0)
        assertEquals(1.0,cap(3.0,8.0,2.0).effectiveRecommendationMaximum!!,0.0)
        assertEquals(2.0,cap(null,2.0,10.0).effectiveRecommendationMaximum!!,0.0)
        assertNull(cap(null,null,10.0).effectiveRecommendationMaximum)
        assertEquals(0.0,cap(3.0,null,7.0).effectiveRecommendationMaximum!!,0.0)
        assertEquals(0.0,cap(3.0,0.0,1.0).effectiveRecommendationMaximum!!,0.0)
    }
    @Test fun softDeletedAndDisallowedFoodStillCountsButDirectNutritionAndOtherZonesDoNot() {
        val deleted=bread.copy(isDeleted=true,allowed=false)
        val direct=Intake(88,day,null,"直接添加营养","次",1.0,Nutrition(900.0),"DIRECT")
        val other=gramBread.copy(zoneId=2)
        val summary=FoodZoneQuotas.summaries(listOf(zone),listOf(deleted,other),listOf(intake(deleted,2.0),intake(other,100.0),direct),day).single()
        assertEquals(2.0,summary.consumedPortions,0.0)
        assertEquals(1.0,summary.effectiveRecommendationMaximum!!,0.0)
    }
    @Test fun dailyTemplateIntakesShareDailyZoneBudgetWithManualFoodIntakes() {
        val history=listOf(intake(bread,1.0),intake(gramBread,50.0).copy(source="ROUTINE"))
        val summary=FoodZoneQuotas.summaries(listOf(zone),listOf(bread,gramBread),history,day).single()
        assertEquals(2.0,summary.consumedPortions,0.0)
        assertEquals(1.0,summary.effectiveRecommendationMaximum!!,0.0)
    }
    @Test fun currentMembershipAndConversionApplyWithoutModifyingPastIntakes() {
        val past=intake(gramBread,100.0)
        val changed=gramBread.copy(zonePortionSize=100.0)
        val summary=FoodZoneQuotas.summaries(listOf(zone),listOf(changed),listOf(past),day).single()
        assertEquals(1.0,summary.consumedPortions,0.0)
        assertEquals(100.0,past.quantity,0.0)
        val unassigned=FoodZoneQuotas.summaries(listOf(zone),listOf(changed.copy(zoneId=null)),listOf(past),day).single()
        assertEquals(0.0,unassigned.consumedPortions,0.0)
    }
    @Test fun changedUnitBlocksOnlyTheDailyLimitedZoneInsteadOfGuessingAnAmount() {
        val past=intake(bread,2.0)
        val changed=bread.copy(unit="g",zonePortionSize=50.0)
        val summaries=FoodZoneQuotas.summaries(listOf(zone,FoodZone(2,"其他",4.0)),listOf(changed),listOf(past),day)
        assertTrue(summaries.first().incompatibleUnits)
        assertEquals(0.0,summaries.first().effectiveRecommendationMaximum!!,0.0)
        assertEquals(4.0,summaries.last().effectiveRecommendationMaximum!!,0.0)
        assertTrue(runCatching {FoodZoneQuotas.validateRecommendation(mapOf(7L to 1.0),listOf(changed),listOf(zone),listOf(past),day)}.isFailure)
        val batchOnly=FoodZoneQuotas.summaries(listOf(zone.copy(dailyMaximum=null,recommendationMaximum=2.0)),listOf(changed),listOf(past),day).single()
        assertEquals(2.0,batchOnly.effectiveRecommendationMaximum!!,0.0)
    }
    @Test fun zeroReallyDisablesAndInvalidQuantitiesNeverBypassLimits() {
        val zero=zone.copy(dailyMaximum=0.0)
        assertTrue(runCatching {FoodZoneQuotas.validateRecommendation(mapOf(7L to 1e-8),listOf(bread),listOf(zero),emptyList(),day)}.isFailure)
        for(n in listOf(0.0,-1.0,Double.NaN,Double.POSITIVE_INFINITY))
            assertTrue(runCatching {FoodZoneQuotas.validateRecommendation(mapOf(7L to n),listOf(bread),listOf(zone),emptyList(),day)}.isFailure)
        assertTrue(runCatching {FoodZoneQuotas.validateRecommendation(mapOf(7L to 1.0),listOf(bread),emptyList(),emptyList(),day)}.isFailure)
        for(n in listOf(0.0,-1.0,Double.NaN,Double.POSITIVE_INFINITY)) assertTrue(runCatching {bread.copy(zonePortionSize=n).validate()}.isFailure)
        for(n in listOf(-1.0,Double.NaN,Double.POSITIVE_INFINITY)) assertTrue(runCatching {zone.copy(dailyMaximum=n).validate()}.isFailure)
    }
    @Test fun decimalPortionSumsCanBeAdoptedRegardlessOfSummationOrder() {
        val foods=listOf(bread.copy(id=1,zonePortionSize=10.0),bread.copy(id=2,zonePortionSize=5.0),bread.copy(id=3,zonePortionSize=10.0/3.0))
        val limit=zone.copy(dailyMaximum=0.6)
        // Sorted admission adds .1+.2+.3; generation may have used .3+.2+.1.
        FoodZoneQuotas.validateRecommendation(linkedMapOf(3L to 1.0,2L to 1.0,1L to 1.0),foods,listOf(limit),emptyList(),day)
        assertTrue(runCatching {FoodZoneQuotas.validateRecommendation(mapOf(1L to 1.0,2L to 1.0,3L to 1.000001),foods,listOf(limit),emptyList(),day)}.isFailure)
        // A rounding allowance for positive limits must never enable a zero-limit group.
        assertTrue(runCatching {FoodZoneQuotas.validateRecommendation(mapOf(1L to 1e-300),foods,listOf(limit.copy(dailyMaximum=0.0)),emptyList(),day)}.isFailure)
    }
}
