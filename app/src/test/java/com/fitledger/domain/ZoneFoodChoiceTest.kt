package com.fitledger.domain

import org.junit.Assert.*
import org.junit.Test

class ZoneFoodChoiceTest {
    private val powderA=Candidate(1,"蛋白粉 A",Nutrition(20.0,0.0,5.0,0.0),1.0,2.0,true,1)
    private val powderB=Candidate(2,"蛋白粉 B",Nutrition(40.0,0.0,10.0,0.0),1.0,2.0,true,1)
    private val bread=Candidate(3,"碳水面包",Nutrition(40.0,10.0,0.0,0.0),1.0,1.0,true,2)
    private val fatBread=Candidate(4,"脂肪面包",Nutrition(90.0,0.0,0.0,10.0),1.0,1.0,true,2)
    private val foods=listOf(powderA,powderB,bread,fatBread)
    private val target=Nutrition(210.0,10.0,20.0,10.0)
    private val rules=listOf(ZoneLimit(1,"蛋白粉",Double.MAX_VALUE,false),ZoneLimit(2,"面包",Double.MAX_VALUE,true))
    private val exact=RecommendationPolicy(carbs=MacroTolerance(0.0,0.0),protein=MacroTolerance(0.0,0.0),fat=MacroTolerance(0.0,0.0))

    @Test fun legacyCanReplaceAnUnlockedFoodAndOtherZoneStillMixesWithoutAnyQuantityCap() {
        val result=Recommender.recommend(foods,target,emptyMap(),rules)
        assertEquals(mapOf(2L to 2.0,3L to 1.0,4L to 1.0),result.quantities)
        assertEquals(target,result.nutrition)
        Recommender.validateProposal(foods,target,result.quantities,rules)
    }
    @Test fun boundedSearchAlsoSelectsOnePowderWhileAllowingBothBreads() {
        val result=Recommender.recommend(foods,target,emptyMap(),rules,exact)
        assertEquals(mapOf(2L to 2.0,3L to 1.0,4L to 1.0),result.quantities)
        Recommender.validateProposal(foods,target,result.quantities,rules,exact)
        assertTrue(runCatching {Recommender.validateProposal(foods,target,mapOf(1L to 2.0,2L to 1.0,3L to 1.0,4L to 1.0),rules,exact)}.isFailure)
    }
    @Test fun twoLockedPowdersProduceAnExplicitConflictInBothPathsWithoutChangingTheMap() {
        val locks=linkedMapOf(1L to 1.0,2L to 1.0)
        for(policy in listOf(RecommendationPolicy(),exact,RecommendationPolicy(dailyBudgetYuan=1.0))) {
            val failure=runCatching {Recommender.recommend(foods,target,locks,rules,policy)}.exceptionOrNull()
            assertNotNull(failure);assertTrue(failure!!.message.orEmpty().contains("不能同时锁定"))
            assertEquals(linkedMapOf(1L to 1.0,2L to 1.0),locks)
        }
    }
    @Test fun aSingleLockIsNeverReplacedOrGrownToReachTheTarget() {
        val locks=mapOf(1L to 1.0)
        val result=Recommender.recommend(foods,target,locks,rules)
        assertEquals(1.0,result.quantities.getValue(1L),0.0);assertFalse(result.quantities.containsKey(2L))
        // This deliberately off-step lock must be rejected rather than silently rounded.
        assertTrue(runCatching {Recommender.recommend(foods,Nutrition(140.0,10.0,2.5,10.0),mapOf(1L to 0.5),rules)}.isFailure)
    }
    @Test fun oneExactLockAndTheIndependentBreadZoneWorkInBothPaths() {
        for(policy in listOf(RecommendationPolicy(),exact)) {
            val result=Recommender.recommend(foods,target,mapOf(2L to 2.0),rules,policy)
            assertEquals(mapOf(2L to 2.0,3L to 1.0,4L to 1.0),result.quantities)
        }
    }
    @Test fun defaultTrueStillPermitsDifferentFoodsFromOneZone() {
        val mixed=listOf(bread.copy(zoneId=1),fatBread.copy(zoneId=1))
        val desired=Nutrition(130.0,10.0,0.0,10.0)
        for(policy in listOf(RecommendationPolicy(),exact)) {
            val result=Recommender.recommend(mixed,desired,emptyMap(),listOf(ZoneLimit(1,"面包",2.0)),policy)
            assertEquals(mapOf(3L to 1.0,4L to 1.0),result.quantities)
        }
    }
}
