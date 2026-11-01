package com.fitledger.data

import com.fitledger.domain.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class RecommendationExtrasTest {
    private val monday=LocalDate.of(2026,10,26).toEpochDay()
    private val day=monday+2
    private val target=Nutrition(80.0,20.0,0.0,0.0)
    private fun food(id:Long=1,price:Double?=1.0,zone:Long?=null,maximum:Double=3.0,allowed:Boolean=true)=
        Food(id,"食品$id","g",1.0,Nutrition(40.0,10.0,0.0,0.0),1.0,maximum,allowed,zoneId=zone,pricePerUnitYuan=price)
    private fun intake(day:Long=this.day,cost:Double?=1.0,foodId:Long?=1)=Intake(day=day,foodId=foodId,name="旧食品",unit="g",quantity=1.0,nutrition=Nutrition(40.0,10.0,0.0,0.0),costYuan=cost)
    private fun rejects(block:()->Unit) {assertTrue(runCatching(block).isFailure)}
    private fun generate(foods:List<Food> = listOf(food()),extras:RecommendationExtras=RecommendationExtras(varietyDays=3),
        combos:List<FoodCombo> = emptyList(),policy:RecommendationPolicy=RecommendationPolicy(),intakes:List<Intake> = emptyList(),locks:Map<Long,Double> = emptyMap())=
        ExtendedRecommendations.generate(day,foods,emptyList(),intakes,combos,target,locks,policy,extras,limit=50,seed=7)

    @Test fun disabledExtrasAndDormantSharePreferenceDelegateExactlyToTheLegacyEngine() {
        val foods=listOf(food())
        val old=RecommendationComparison.generate(foods.map {it.candidate()},target,Nutrition(),emptyMap(),emptyList(),RecommendationPolicy(),0.0,limit=50,seed=7)
        assertEquals(old,generate(foods,RecommendationExtras()))
        assertEquals(old,generate(foods,RecommendationExtras(shareWeekRemainder=true)))
        val suggestion=Recommender.recommend(foods.map {it.candidate()},target,emptyMap())
        assertEquals(suggestion,ExtendedRecommendations.recommend(day,foods,emptyList(),emptyList(),emptyList(),target,emptyMap(),RecommendationPolicy(),RecommendationExtras()))
    }
    @Test fun weeklyBudgetUsesMondayToSundayActualCostsAndUnknownNeverBecomesZero() {
        val extras=RecommendationExtras(weeklyBudgetYuan=100.0)
        val entries=listOf(intake(monday-1,900.0),intake(monday,30.0),intake(day,10.0),intake(monday+7,null))
        val summary=ExtendedRecommendations.weeklyBudget(day,entries,extras)!!
        assertEquals(monday,summary.startDay);assertEquals(monday+6,summary.endDay)
        assertEquals(40.0,summary.knownCostYuan,0.0);assertEquals(60.0,summary.remainingYuan!!,0.0)
        assertEquals(70.0,ExtendedRecommendations.effectivePolicy(day,entries,RecommendationPolicy(),extras).dailyBudgetYuan!!,0.0)
        val unknown=entries+intake(monday+1,null)
        assertNull(ExtendedRecommendations.weeklyBudget(day,unknown,extras)!!.remainingYuan)
        rejects {ExtendedRecommendations.effectivePolicy(day,unknown,RecommendationPolicy(),extras)}
    }
    @Test fun weeklyRemainderMayBeSharedAcrossRemainingDaysAndIntersectsDailyCap() {
        val extras=RecommendationExtras(weeklyBudgetYuan=100.0,shareWeekRemainder=true)
        val entries=listOf(intake(monday,30.0),intake(day,10.0))
        assertEquals(12.0,ExtendedRecommendations.weeklyBudget(day,entries,extras)!!.currentRecommendationAllowance!!,0.0)
        assertEquals(22.0,ExtendedRecommendations.effectivePolicy(day,entries,RecommendationPolicy(),extras).dailyBudgetYuan!!,0.0)
        assertEquals(20.0,ExtendedRecommendations.effectivePolicy(day,entries,RecommendationPolicy(dailyBudgetYuan=20.0),extras).dailyBudgetYuan!!,0.0)
    }
    @Test fun alreadyExceededWeeklyBudgetRequiresExplicitSettingChange() {
        val extras=RecommendationExtras(weeklyBudgetYuan=10.0)
        val entries=listOf(intake(monday,11.0))
        rejects {ExtendedRecommendations.effectivePolicy(day,entries,RecommendationPolicy(),extras)}
        rejects {generate(extras=extras,intakes=entries)}
        assertEquals(1.0,ExtendedRecommendations.effectivePolicy(day,entries,RecommendationPolicy(),extras.copy(weeklyBudgetYuan=12.0)).dailyBudgetYuan!!,0.0)
    }
    @Test fun weeklyCapDoesNotBecomeAPersistedDailyBudgetWhenAdjustingMacros() {
        val policy=RecommendationPolicy(protein=MacroTolerance(under=0.0),carbs=MacroTolerance(over=0.0))
        val extras=RecommendationExtras(weeklyBudgetYuan=2.0)
        val batch=generate(extras=extras,policy=policy)
        assertEquals(policy,batch.basePolicy)
        val large=batch.options.single {it.suggestion.quantities[1]==3.0}
        assertFalse(large.formalPolicySatisfied)
        assertNull(large.proposedPolicy.dailyBudgetYuan)
        assertTrue(large.violations.any {it.contains("周预算")})
        rejects {ExtendedRecommendations.validate(day,listOf(food()),emptyList(),emptyList(),emptyList(),target,large.suggestion.quantities,large.proposedPolicy,extras)}
    }
    @Test fun adoptionRechecksOtherDaysCostAndUnknownPriceUnderTheWeeklyBudget() {
        val extras=RecommendationExtras(weeklyBudgetYuan=3.0)
        val old=listOf(intake(monday,2.0))
        ExtendedRecommendations.validate(day,listOf(food()),emptyList(),old,emptyList(),target,mapOf(1L to 1.0),RecommendationPolicy(),extras)
        rejects {ExtendedRecommendations.validate(day,listOf(food()),emptyList(),old+intake(monday+1,1.0),emptyList(),target,mapOf(1L to 1.0),RecommendationPolicy(),extras)}
        rejects {ExtendedRecommendations.validate(day,listOf(food(price=null)),emptyList(),old,emptyList(),target,mapOf(1L to 1.0),RecommendationPolicy(),extras)}
    }
    @Test fun varietyFavorsAComparableFoodNotEatenInTheRecentWindow() {
        val foods=listOf(food(),food(2))
        val result=ExtendedRecommendations.recommend(day,foods,emptyList(),listOf(intake(day-1,1.0)),emptyList(),target,emptyMap(),RecommendationPolicy(),RecommendationExtras(varietyDays=3))
        assertEquals(mapOf(2L to 2.0),result.quantities)
        val locked=ExtendedRecommendations.recommend(day,foods,emptyList(),listOf(intake(day-1,1.0)),emptyList(),target,mapOf(1L to 2.0),RecommendationPolicy(),RecommendationExtras(varietyDays=3))
        assertEquals(2.0,locked.quantities[1]!!,0.0)
    }
    @Test fun valuePreferenceNeverUsesUnknownCostAndChoosesCheaperNutritionEquivalent() {
        val foods=listOf(food(price=null),food(2,price=5.0),food(3,price=1.0))
        val q=ExtendedRecommendations.recommend(day,foods,emptyList(),emptyList(),emptyList(),target,emptyMap(),RecommendationPolicy(),RecommendationExtras(varietyDays=1),preferValue=true)
        assertEquals(mapOf(3L to 2.0),q.quantities)
        assertEquals(2.0,q.costYuan!!,0.0)
        rejects {ExtendedRecommendations.recommend(day,listOf(food(price=null)),emptyList(),emptyList(),emptyList(),target,emptyMap(),RecommendationPolicy(),RecommendationExtras(varietyDays=1),preferValue=true)}
    }
    @Test fun ratioRecommendationsRespectBothNativeStepsAndExactLocks() {
        val extras=RecommendationExtras(ratios=listOf(WeightRatio(1,2,0.5)))
        val batch=generate(listOf(food(),food(2)),extras=extras,locks=mapOf(2L to 2.0))
        assertTrue(batch.options.isNotEmpty())
        batch.options.forEach {option->
            assertEquals(2.0,option.suggestion.quantities[2]!!,0.0)
            assertEquals(1.0,option.suggestion.quantities[1]!!,0.0)
            assertEquals(0.0,ExtendedRecommendations.deviations(listOf(food(),food(2)),emptyList(),extras,option.suggestion.quantities).single().deltaGrams,0.0)
        }
        assertTrue(generate(listOf(food(),food(2)),extras=extras,locks=mapOf(1L to 3.0,2L to 2.0)).options.isEmpty())
    }
    @Test fun wholeCombosCannotBeSplitAndMustShareOneScale() {
        val foods=listOf(food(maximum=4.0),food(2,maximum=4.0))
        val combo=FoodCombo(5,"整套",ComboCodec.encode(listOf(ComboItem(1,"g",1.0),ComboItem(2,"g",2.0))))
        val extras=RecommendationExtras(comboIds=setOf(5))
        val batch=generate(foods,extras,listOf(combo))
        assertTrue(batch.options.isNotEmpty())
        batch.options.forEach {assertEquals(it.suggestion.quantities[1]!!*2,it.suggestion.quantities[2]!!,0.0)}
        rejects {ExtendedRecommendations.validate(day,foods,emptyList(),emptyList(),listOf(combo),target,mapOf(1L to 1.0),RecommendationPolicy(),extras)}
        rejects {ExtendedRecommendations.validate(day,foods,emptyList(),emptyList(),listOf(combo),target,mapOf(1L to 1.0,2L to 3.0),RecommendationPolicy(),extras)}
    }
    @Test fun zoneQuotasAndExclusivityRemainHardForAtomicCombosAndRatios() {
        val foods=listOf(food(zone=10),food(2,zone=10))
        val extras=RecommendationExtras(ratios=listOf(WeightRatio(1,2,0.5)))
        val zone=FoodZone(10,"克数区",dailyMaximum=2.0,limitUnit="G")
        rejects {ExtendedRecommendations.validate(day,foods,listOf(zone),emptyList(),emptyList(),target,mapOf(1L to 1.0,2L to 2.0),RecommendationPolicy(),extras)}
        val exclusive=zone.copy(dailyMaximum=10.0,allowMultipleFoods=false)
        val batch=ExtendedRecommendations.generate(day,foods,listOf(exclusive),emptyList(),emptyList(),target,emptyMap(),RecommendationPolicy(),extras)
        assertTrue(batch.options.isEmpty())
    }
    @Test fun configurationRejectsDeletedDisallowedUnknownMassCyclesAndOverlappingCombos() {
        val foods=listOf(food(),food(2))
        val ratio=RecommendationExtras(ratios=listOf(WeightRatio(1,2,0.5)))
        ExtendedRecommendations.validateConfiguration(foods,emptyList(),ratio)
        rejects {ExtendedRecommendations.validateConfiguration(listOf(food(allowed=false),food(2)),emptyList(),ratio)}
        rejects {ExtendedRecommendations.validateConfiguration(listOf(food().copy(isDeleted=true),food(2)),emptyList(),ratio)}
        rejects {ExtendedRecommendations.validateConfiguration(listOf(food().copy(unit="份"),food(2)),emptyList(),ratio)}
        rejects {ExtendedRecommendations.validateConfiguration(foods,emptyList(),RecommendationExtras(comboIds=setOf(999)))}
        val combo=FoodCombo(5,"A",ComboCodec.encode(listOf(ComboItem(1,"g",1.0),ComboItem(2,"g",2.0))),WeightRatios.encode(listOf(WeightRatio(2,1,2.0))))
        rejects {ExtendedRecommendations.validateConfiguration(foods,listOf(combo),ratio.copy(comboIds=setOf(5)))}
        rejects {ExtendedRecommendations.validateConfiguration(foods,listOf(combo,combo.copy(id=6)),RecommendationExtras(comboIds=setOf(5,6)))}
        rejects {ExtendedRecommendations.validateConfiguration(listOf(food(maximum=1e12),food(2)),emptyList(),ratio)}
    }
    @Test fun projectionDoesNotPrematurelyDisableAnotherSearchAndNeverAddsDuplicateRows() {
        val foods=listOf(food(),food(2))
        val extras=RecommendationExtras(ratios=listOf(WeightRatio(1,2,0.5)))
        val first=generate(foods,extras)
        assertTrue(first.options.size in 1..49)
        assertTrue(first.canGenerateMore)
        val next=ExtendedRecommendations.generate(day,foods,emptyList(),emptyList(),emptyList(),target,emptyMap(),RecommendationPolicy(),extras,first.options,10,seed=11)
        assertEquals(next.options.size,next.options.map {it.id}.distinct().size)
        assertTrue(next.options.size<=50)
        assertTrue(first.options.all {old->next.options.any {it.id==old.id}})
    }
    @Test fun aSingleStructurallyPossibleComboHasNoMoreRowsToInvent() {
        val foods=listOf(food(maximum=1.0),food(2,maximum=1.0))
        val combo=FoodCombo(5,"仅一份",ComboCodec.encode(listOf(ComboItem(1,"g",1.0),ComboItem(2,"g",1.0))))
        val batch=generate(foods,RecommendationExtras(comboIds=setOf(5)),listOf(combo))
        assertEquals(1,batch.options.size)
        assertFalse(batch.canGenerateMore)
    }
    @Test fun extrasCodecIsStrictAndPreservesEveryOptionalSetting() {
        val extras=RecommendationExtras(100.0,true,7,setOf(3,1),listOf(WeightRatio(1,2,0.5)))
        assertEquals(extras,RecommendationExtrasCodec.decode(RecommendationExtrasCodec.encode(extras)))
        fun bad(change:(JSONObject)->Unit) {val j=RecommendationExtrasCodec.encode(extras);change(j);rejects {RecommendationExtrasCodec.decode(j)}}
        bad {it.put("varietyDays",2.5)};bad {it.put("shareWeekRemainder","true")};bad {it.put("weeklyBudgetYuan","20")}
        bad {it.remove("ratios")};bad {it.put("unrecognized",1)};bad {it.put("comboIds",org.json.JSONArray(listOf(1,1)))}
    }
}
