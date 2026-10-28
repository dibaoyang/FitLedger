package com.fitledger.data

import com.fitledger.domain.*
import org.junit.Assert.*
import org.junit.Test

class FoodZoneQuotasTest {
    private val day=20000L
    private val zone=FoodZone(1,"面包",3.0)
    private val bread=Food(7,"面包一","份",1.0,Nutrition(100.0,20.0,3.0,1.0),1.0,10.0,true,zoneId=1)
    private val second=bread.copy(id=9,name="面包二")
    private val gramBread=bread.copy(id=8,name="克重面包",unit="g",basisAmount=50.0,step=25.0,maximum=500.0,zonePortionSize=50.0)
    private fun intake(food:Food,quantity:Double,date:Long=day)=Intake(food.id,date,food.id,food.name,food.unit,quantity,food.nutrition*(quantity/food.basisAmount))
    private fun reject(block:()->Unit)=assertTrue(runCatching(block).isFailure)

    @Test fun portionZoneAddsFoodPortionsDirectlyAndBlankRecommendationFollowsDaily() {
        val history=listOf(intake(bread,1.0),intake(second,0.5),intake(bread,100.0,day-1))
        val summary=FoodZoneQuotas.summaries(listOf(zone),listOf(bread,second),history,day).single()
        assertEquals(1.5,summary.consumedPortions,0.0);assertEquals(1.5,summary.remainingDaily!!,0.0)
        assertEquals(1.5,summary.effectiveRecommendationMaximum!!,0.0);assertFalse(summary.incompatibleUnits)
        FoodZoneQuotas.validateRecommendation(mapOf(7L to 1.0,9L to 0.5),listOf(bread,second),listOf(zone),history,day)
        reject {FoodZoneQuotas.validateRecommendation(mapOf(7L to 1.0,9L to 1.0),listOf(bread,second),listOf(zone),history,day)}
    }
    @Test fun gramZoneAddsOnlyNativeGramsWithoutLegacyConversion() {
        val grams=zone.copy(dailyMaximum=200.0,recommendationMaximum=120.0,limitUnit="G")
        val other=gramBread.copy(id=9,zonePortionSize=999.0)
        assertEquals(1.0,FoodZoneQuotas.quotaSize(gramBread,grams)!!,0.0)
        val history=listOf(intake(gramBread,80.0),intake(other,25.0).copy(source="ROUTINE"))
        val summary=FoodZoneQuotas.summaries(listOf(grams),listOf(gramBread,other),history,day).single()
        assertEquals(105.0,summary.consumedPortions,0.0);assertEquals(95.0,summary.remainingDaily!!,0.0)
        assertEquals(95.0,summary.effectiveRecommendationMaximum!!,0.0)
        FoodZoneQuotas.validateRecommendation(mapOf(8L to 50.0,9L to 45.0),listOf(gramBread,other),listOf(grams),history,day)
        reject {FoodZoneQuotas.validateRecommendation(mapOf(8L to 50.0,9L to 50.0),listOf(gramBread,other),listOf(grams),history,day)}
    }
    @Test fun crossUnitFoodsAreNeverConvertedEvenWhenNetWeightIsPresent() {
        val grams=zone.copy(limitUnit="G")
        assertNull(FoodZoneQuotas.quotaSize(bread.copy(gramsPerPortion=40.0),grams))
        assertNull(FoodZoneQuotas.quotaSize(gramBread,zone))
        assertFalse(FoodZoneQuotas.matchesUnit(bread,grams));assertFalse(FoodZoneQuotas.matchesUnit(gramBread,zone))
        reject {FoodZoneQuotas.validateRecommendation(mapOf(7L to 1.0),listOf(bread.copy(gramsPerPortion=40.0)),listOf(grams),emptyList(),day)}
    }
    @Test fun anyActiveMismatchedMemberBlocksWholeZoneEvenWithoutCapsOrIntakes() {
        for(z in listOf(zone.copy(dailyMaximum=null),zone.copy(dailyMaximum=null,recommendationMaximum=2.0),zone)) {
            val foods=listOf(bread,gramBread.copy(allowed=false))
            assertEquals(listOf(8L),FoodZoneQuotas.zoneIssues(z,foods).map {it.id})
            assertTrue(FoodZoneQuotas.candidates(foods,listOf(z)).isEmpty())
            val summary=FoodZoneQuotas.summaries(listOf(z),foods,emptyList(),day).single()
            assertTrue(summary.incompatibleUnits);assertEquals(0.0,summary.effectiveRecommendationMaximum!!,0.0)
            reject {FoodZoneQuotas.validateRecommendation(mapOf(7L to 1.0),foods,listOf(z),emptyList(),day)}
        }
    }
    @Test fun oldNonNativePortionRuleRequiresReviewRatherThanReinterpretingOldLimit() {
        val legacy=bread.copy(zonePortionSize=2.0)
        assertNull(FoodZoneQuotas.quotaSize(legacy,zone))
        assertEquals(listOf(legacy),FoodZoneQuotas.zoneIssues(zone,listOf(legacy)))
        assertTrue(FoodZoneQuotas.candidates(listOf(legacy,second),listOf(zone)).isEmpty())
        val summary=FoodZoneQuotas.summaries(listOf(zone),listOf(legacy,second),emptyList(),day).single()
        assertTrue(summary.incompatibleUnits);assertEquals(0.0,summary.effectiveRecommendationMaximum!!,0.0)
        assertEquals(2.0,legacy.zonePortionSize,0.0);assertEquals(3.0,zone.dailyMaximum!!,0.0)
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
    @Test fun deletedAndDisallowedIntakesCountButDirectNutritionAndOtherZonesDoNot() {
        val deleted=bread.copy(isDeleted=true,allowed=false)
        val direct=Intake(88,day,null,"直接添加营养","次",1.0,Nutrition(900.0),"DIRECT")
        val other=gramBread.copy(zoneId=2)
        val summary=FoodZoneQuotas.summaries(listOf(zone),listOf(deleted,other),listOf(intake(deleted,2.0),intake(other,100.0),direct),day).single()
        assertEquals(2.0,summary.consumedPortions,0.0);assertEquals(1.0,summary.effectiveRecommendationMaximum!!,0.0)
    }
    @Test fun dailyTemplatesAndManualRecordsUseTheSameNativePortionBudget() {
        val history=listOf(intake(bread,1.0),intake(second,1.0).copy(source="ROUTINE"))
        val summary=FoodZoneQuotas.summaries(listOf(zone),listOf(bread,second),history,day).single()
        assertEquals(2.0,summary.consumedPortions,0.0);assertEquals(1.0,summary.effectiveRecommendationMaximum!!,0.0)
    }
    @Test fun currentMembershipAppliesWithoutModifyingPastIntakes() {
        val grams=zone.copy(dailyMaximum=200.0,limitUnit="G")
        val past=intake(gramBread,100.0)
        val summary=FoodZoneQuotas.summaries(listOf(grams),listOf(gramBread),listOf(past),day).single()
        assertEquals(100.0,summary.consumedPortions,0.0);assertEquals(100.0,past.quantity,0.0)
        val unassigned=FoodZoneQuotas.summaries(listOf(grams),listOf(gramBread.copy(zoneId=null)),listOf(past),day).single()
        assertEquals(0.0,unassigned.consumedPortions,0.0)
    }
    @Test fun historicalUnitMismatchBlocksZoneWithOrWithoutDailyCap() {
        val past=intake(bread,2.0).copy(unit="g")
        for(z in listOf(zone,zone.copy(dailyMaximum=null,recommendationMaximum=2.0),zone.copy(dailyMaximum=null))) {
            val summary=FoodZoneQuotas.summaries(listOf(z),listOf(bread),listOf(past),day).single()
            assertTrue(summary.incompatibleUnits);assertEquals(0.0,summary.effectiveRecommendationMaximum!!,0.0)
            reject {FoodZoneQuotas.validateRecommendation(mapOf(7L to 1.0),listOf(bread),listOf(z),listOf(past),day)}
        }
    }
    @Test fun zeroReallyDisablesAndInvalidQuantitiesNeverBypassLimits() {
        val zero=zone.copy(dailyMaximum=0.0)
        reject {FoodZoneQuotas.validateRecommendation(mapOf(7L to 1e-8),listOf(bread),listOf(zero),emptyList(),day)}
        for(n in listOf(0.0,-1.0,Double.NaN,Double.POSITIVE_INFINITY)) {
            reject {FoodZoneQuotas.validateRecommendation(mapOf(7L to n),listOf(bread),listOf(zone),emptyList(),day)}
            reject {bread.copy(zonePortionSize=n).validate()}
        }
        for(n in listOf(-1.0,Double.NaN,Double.POSITIVE_INFINITY)) reject {zone.copy(dailyMaximum=n).validate()}
    }
    @Test fun nativeDecimalPortionSumsDoNotRequireCrossFoodConversion() {
        val foods=listOf(bread.copy(id=1),bread.copy(id=2),bread.copy(id=3))
        val limit=zone.copy(dailyMaximum=0.6)
        FoodZoneQuotas.validateRecommendation(linkedMapOf(3L to 0.3,2L to 0.2,1L to 0.1),foods,listOf(limit),emptyList(),day)
        reject {FoodZoneQuotas.validateRecommendation(mapOf(1L to 0.1,2L to 0.2,3L to 0.300001),foods,listOf(limit),emptyList(),day)}
        reject {FoodZoneQuotas.validateRecommendation(mapOf(1L to 1e-300),foods,listOf(limit.copy(dailyMaximum=0.0)),emptyList(),day)}
    }
    @Test fun lockedGramFoodsUseNativeGramQuotaDuringGenerationAndAdoption() {
        val grams=zone.copy(dailyMaximum=90.0,recommendationMaximum=80.0,limitUnit="G")
        val foods=listOf(gramBread.copy(step=10.0),gramBread.copy(id=9,step=10.0))
        val candidates=FoodZoneQuotas.candidates(foods,listOf(grams))
        val limits=FoodZoneQuotas.limits(listOf(grams),foods,emptyList(),day)
        val result=Recommender.recommend(candidates,Nutrition(2000.0,250.0,120.0,60.0),mapOf(8L to 30.0),limits)
        assertEquals(30.0,result.quantities.getValue(8L),0.0);assertTrue(QuotaMath.within(result.quantities.values.sum(),80.0))
        FoodZoneQuotas.validateRecommendation(result.quantities,foods,listOf(grams),emptyList(),day)
        reject {Recommender.recommend(candidates,Nutrition(2000.0),mapOf(8L to 80.0,9L to 10.0),limits)}
    }
    @Test fun missingZoneNeverCreatesAnUnconstrainedCandidate() {
        assertTrue(FoodZoneQuotas.candidates(listOf(bread),emptyList()).isEmpty())
        reject {FoodZoneQuotas.validateRecommendation(mapOf(7L to 1.0),listOf(bread),emptyList(),emptyList(),day)}
        assertNull(FoodZoneQuotas.quotaSize(bread.copy(maximum=Double.POSITIVE_INFINITY),zone))
        reject {zone.copy(limitUnit="KG").validate()}
    }
    @Test fun deletedWrongUnitDoesNotBlockActiveMembershipUnlessItsIntakeIsCounted() {
        val deleted=gramBread.copy(isDeleted=true)
        assertTrue(FoodZoneQuotas.zoneIssues(zone,listOf(bread,deleted)).isEmpty())
        assertFalse(FoodZoneQuotas.summaries(listOf(zone),listOf(bread,deleted),emptyList(),day).single().incompatibleUnits)
        assertTrue(FoodZoneQuotas.summaries(listOf(zone),listOf(bread,deleted),listOf(intake(deleted,10.0)),day).single().incompatibleUnits)
    }
    @Test fun invalidZoneCannotAffectOtherZonesOrUnassignedFoods() {
        val healthy=zone.copy(id=2)
        val unassigned=gramBread.copy(id=10,zoneId=null)
        val foods=listOf(bread,gramBread,second.copy(zoneId=2),unassigned)
        assertEquals(listOf(9L,10L),FoodZoneQuotas.candidates(foods,listOf(zone,healthy)).map {it.id})
        assertEquals("g",FoodZoneQuotas.unitFor(zone.copy(limitUnit="G")));assertEquals("份",FoodZoneQuotas.unitFor(zone))
    }
}
