package com.fitledger.domain

import org.junit.Assert.*
import org.junit.Test

class RecommendationComparisonTest {
    private val target=Nutrition(80.0,20.0,0.0,0.0)
    private fun food(id:Long=1,price:Double?=1.0,maximum:Double=3.0,zone:Long?=null,portion:Double=1.0,
        step:Double=1.0,allowed:Boolean=true,n:Nutrition=Nutrition(40.0,10.0,0.0,0.0))=
        Candidate(id,"食品$id",n,step,maximum,allowed,zone,portion,price)
    private fun generate(foods:List<Candidate> = listOf(food()),policy:RecommendationPolicy=RecommendationPolicy(),
        consumed:Nutrition=Nutrition(),spent:Double?=0.0,locks:Map<Long,Double> = emptyMap(),zones:List<ZoneLimit> = emptyList(),
        existing:List<ComparisonOption> = emptyList(),limit:Int=10)=
        RecommendationComparison.generate(foods,target,consumed,locks,zones,policy,spent,existing,limit,seed=7)
    private fun option(batch:ComparisonBatch,q:Double)=batch.options.single {it.suggestion.quantities[1]==q}

    @Test fun atLeastRanksAllMeetingRowsEquallyAndPreservesTheActualDailyAmount() {
        val policy=RecommendationPolicy(carbs=MacroTolerance(under=0.0))
        val batch=generate(policy=policy,consumed=Nutrition(20.0,5.0,0.0,0.0))
        val low=option(batch,1.0);val enough=option(batch,2.0);val extra=option(batch,3.0)
        assertEquals(ComparisonMacroMode.AT_LEAST,enough.metrics.carbs.mode)
        assertEquals(25.0,enough.metrics.carbs.actualGrams,0.0)
        assertEquals(5.0,enough.metrics.carbs.differenceGrams,0.0)
        assertFalse(low.metrics.carbs.satisfied)
        assertTrue(enough.metrics.carbs.satisfied)
        assertTrue(extra.metrics.carbs.satisfied)
        assertFalse(ComparisonColumn.CARBS in low.bestColumns)
        assertTrue(ComparisonColumn.CARBS in enough.bestColumns)
        assertTrue(ComparisonColumn.CARBS in extra.bestColumns)
        assertFalse(low.formalPolicySatisfied)
        assertTrue(enough.formalPolicySatisfied)
    }
    @Test fun atMostRanksLowerActualAmountAndKeepsOverLimitRowsMarkedForAdjustment() {
        val batch=generate(policy=RecommendationPolicy(carbs=MacroTolerance(over=0.0)))
        val low=option(batch,1.0);val exact=option(batch,2.0);val high=option(batch,3.0)
        assertEquals(ComparisonMacroMode.AT_MOST,low.metrics.carbs.mode)
        assertTrue(ComparisonColumn.CARBS in low.bestColumns)
        assertFalse(ComparisonColumn.CARBS in exact.bestColumns)
        assertFalse(high.metrics.carbs.satisfied)
        assertFalse(high.formalPolicySatisfied)
        assertTrue(high.violations.any {it.contains("超过允许上限")})
        assertTrue(high.proposedPolicy.carbs.over!!>=10.0)
        assertEquals(0.0,high.proposedPolicy.carbs.over!!-10.0,1e-12)
    }
    @Test fun otherModesUseAbsoluteDifferenceAndBothZeroMeansExact() {
        val closest=generate(policy=RecommendationPolicy(carbs=MacroTolerance(under=5.0,over=5.0)))
        assertEquals(ComparisonMacroMode.CLOSEST,option(closest,2.0).metrics.carbs.mode)
        assertTrue(ComparisonColumn.CARBS in option(closest,2.0).bestColumns)
        val exact=generate(policy=RecommendationPolicy(carbs=MacroTolerance(0.0,0.0)))
        assertEquals(ComparisonMacroMode.EXACT,option(exact,2.0).metrics.carbs.mode)
        assertTrue(option(exact,2.0).formalPolicySatisfied)
        assertFalse(option(exact,1.0).formalPolicySatisfied)
        assertFalse(option(exact,3.0).formalPolicySatisfied)
    }
    @Test fun unknownCostsNeverBecomeZeroOrWinAnUnknownColumn() {
        val batch=generate(foods=listOf(food(price=null)))
        assertTrue(batch.options.isNotEmpty())
        batch.options.forEach {
            assertNull(it.metrics.currentCostYuan)
            assertNull(it.metrics.dailyCostYuan)
            assertNull(it.suggestion.costYuan)
            assertFalse(ComparisonColumn.CURRENT_COST in it.bestColumns)
            assertFalse(ComparisonColumn.DAILY_COST in it.bestColumns)
        }
        val priorUnknown=generate(spent=null)
        assertTrue(priorUnknown.options.any {ComparisonColumn.CURRENT_COST in it.bestColumns})
        assertTrue(priorUnknown.options.none {ComparisonColumn.DAILY_COST in it.bestColumns})
        val budget=generate(foods=listOf(food(price=null)),policy=RecommendationPolicy(dailyBudgetYuan=10.0))
        assertTrue(budget.options.all {!it.formalPolicySatisfied && it.proposedPolicy.dailyBudgetYuan==null})
    }
    @Test fun freeFoodCanWinCostsAndCurrentAndWholeDayRemainDistinct() {
        val batch=generate(foods=listOf(food(price=0.0),food(id=2,price=2.0,maximum=1.0)),spent=5.0)
        batch.options.filter {it.suggestion.quantities.keys==setOf(1L)}.forEach {
            assertEquals(0.0,it.metrics.currentCostYuan!!,0.0)
            assertEquals(5.0,it.metrics.dailyCostYuan!!,0.0)
            assertTrue(ComparisonColumn.CURRENT_COST in it.bestColumns)
            assertTrue(ComparisonColumn.DAILY_COST in it.bestColumns)
        }
    }
    @Test fun exactLocksStepsMaximaCapsAndExclusiveZonesRemainHardEvenForAdjustedRows() {
        val foods=listOf(food(1,maximum=2.0,zone=1),food(2,maximum=2.0,zone=1),
            food(3,maximum=2.0,zone=2,portion=2.0,step=0.5),food(4,allowed=false))
        val zones=listOf(ZoneLimit(1,"互斥",2.0,false),ZoneLimit(2,"共享",0.75,true))
        val policy=RecommendationPolicy(dailyBudgetYuan=0.0,carbs=MacroTolerance(0.0,0.0))
        val locks=mapOf(1L to 1.0)
        val batch=generate(foods,policy,locks=locks,zones=zones)
        assertTrue(batch.options.isNotEmpty())
        batch.options.forEach {row->
            assertEquals(1.0,row.suggestion.quantities[1]!!,0.0)
            assertFalse(2L in row.suggestion.quantities)
            assertFalse(4L in row.suggestion.quantities)
            assertTrue((row.suggestion.quantities[3] ?: 0.0)<=1.5)
            assertEquals(row.suggestion.quantities.size,row.metrics.foodCount)
            Recommender.validateProposal(foods,target,row.suggestion.quantities,zones,RecommendationPolicy(),0.0)
            Recommender.validateProposal(foods,target,row.suggestion.quantities,zones,row.proposedPolicy,0.0)
        }
    }
    @Test fun incompatibleOrNonStepLocksAreRejectedRatherThanSilentlyChanged() {
        val foods=listOf(food(1,zone=1),food(2,zone=1))
        val zones=listOf(ZoneLimit(1,"互斥",10.0,false))
        assertTrue(runCatching {generate(foods,locks=mapOf(1L to 1.0,2L to 1.0),zones=zones)}.isFailure)
        assertTrue(runCatching {generate(locks=mapOf(1L to 0.5))}.isFailure)
        assertTrue(runCatching {generate(locks=mapOf(99L to 1.0))}.isFailure)
        assertTrue(runCatching {generate(locks=mapOf(1L to 4.0))}.isFailure)
    }
    @Test fun sharedZoneAmountsAreAddedAcrossFoodIdentities() {
        val foods=listOf(food(1,zone=1),food(2,zone=1))
        val batch=generate(foods,zones=listOf(ZoneLimit(1,"共享",2.0,true)))
        assertTrue(batch.options.any {it.suggestion.quantities.keys.size==2})
        batch.options.forEach {assertTrue(it.suggestion.quantities.values.sum()<=2.0)}
    }
    @Test fun proposedPolicyOnlyLoosensViolatedDirectionsAndPassesFreshValidation() {
        val policy=RecommendationPolicy(dailyBudgetYuan=1.0,carbs=MacroTolerance(0.0,0.0),protein=MacroTolerance(over=2.0))
        val foods=listOf(food())
        val batch=generate(foods,policy,spent=2.0)
        batch.options.forEach {row->
            assertFalse(row.formalPolicySatisfied)
            assertEquals(policy.protein,row.proposedPolicy.protein)
            assertEquals(policy.fat,row.proposedPolicy.fat)
            assertTrue(row.proposedPolicy.dailyBudgetYuan==null || row.proposedPolicy.dailyBudgetYuan!!>=2.0+row.metrics.currentCostYuan!!)
            Recommender.validateProposal(foods,target,row.suggestion.quantities,emptyList(),row.proposedPolicy,2.0)
        }
    }
    @Test fun repeatedGenerationKeepsUniqueRowsCapsAtFiftyAndDoesNotFillWithEmptyRows() {
        val foods=listOf(food(maximum=100.0))
        var batch=generate(foods)
        assertEquals(10,batch.options.size)
        assertTrue(batch.canGenerateMore)
        repeat(5) {batch=generate(foods,existing=batch.options)}
        assertEquals(50,batch.options.size)
        assertFalse(batch.canGenerateMore)
        assertEquals(50,batch.options.map {it.id}.toSet().size)
        assertTrue(batch.options.all {it.suggestion.quantities.isNotEmpty() && it.suggestion.quantities.values.all {q->q>0}})
        val only=generate(locks=mapOf(1L to 1.0))
        assertEquals(1,only.options.size)
        assertFalse(only.canGenerateMore)
        assertTrue(generate(foods=emptyList()).options.isEmpty())
    }
    @Test fun staleExistingRowsAreRecomputedAndCannotSmuggleAlteredLockedAmounts() {
        val old=generate().options
        val batch=generate(foods=listOf(food(price=4.0)),locks=mapOf(1L to 2.0),existing=old)
        assertEquals(1,batch.options.size)
        assertEquals(2.0,batch.options.single().suggestion.quantities[1]!!,0.0)
        assertEquals(8.0,batch.options.single().metrics.currentCostYuan!!,0.0)
    }
    @Test fun largeFiniteSpentCostDoesNotBreakMinimalBudgetAdjustment() {
        val foods=listOf(food(maximum=1.0))
        val policy=RecommendationPolicy(dailyBudgetYuan=1e17)
        val batch=generate(foods,policy,spent=1e17)
        val row=batch.options.single()
        assertFalse(row.formalPolicySatisfied)
        Recommender.validateProposal(foods,target,row.suggestion.quantities,emptyList(),row.proposedPolicy,1e17)
    }
    @Test fun fractionalMaximumUsesTheLastValidStepWithoutFloatingPointOvershoot() {
        val foods=listOf(food(maximum=0.3,step=0.1))
        val batch=generate(foods)
        assertEquals(3,batch.options.size)
        assertTrue(batch.options.any {it.suggestion.quantities[1]==0.3})
        batch.options.forEach {Recommender.validateProposal(foods,target,it.suggestion.quantities,emptyList(),RecommendationPolicy(),0.0)}
    }
}
