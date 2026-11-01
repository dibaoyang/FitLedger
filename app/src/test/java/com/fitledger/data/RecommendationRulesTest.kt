package com.fitledger.data

import com.fitledger.domain.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class RecommendationRulesTest {
    private val first=LocalDate.of(2026,10,29).toEpochDay()
    private fun food(id:Long=1,unit:String="g",zone:Long?=null,minimum:Double=0.0,prep:String="")=
        Food(id,"食品$id",unit,1.0,Nutrition(4.0,1.0,0.0,0.0),if(unit=="g")10.0 else 1.0,if(unit=="g")300.0 else 10.0,true,
            zoneId=zone,pricePerUnitYuan=0.01,minimum=minimum,preparationTags=prep)
    private fun intake(day:Long,id:Long=1,q:Double=20.0,unit:String="g")=
        Intake(day=day,foodId=id,name="已吃",unit=unit,quantity=q,nutrition=Nutrition(4*q,q),costYuan=0.01*q)
    private fun rejects(block:()->Unit) {assertTrue(runCatching(block).isFailure)}
    private val target=Nutrition(400.0,100.0,0.0,0.0)

    @Test fun customCycleStartsOnFirstActualDayAndCountsEatingDaysNotIndividualRecords() {
        val f=food();val rule=FoodCycleRule(1,7,3,100.0)
        val entries=listOf(intake(first),intake(first,q=10.0),intake(first+2))
        val s=FoodCycles.summary(first+3,f,entries,rule)
        assertEquals(first,s.anchorDay);assertEquals(first,s.startDay);assertEquals(first+6,s.endDay)
        assertEquals(2,s.usedDays);assertEquals(50.0,s.usedQuantity,0.0);assertEquals(1,s.remainingDays);assertEquals(50.0,s.remainingQuantity!!,0.0)
    }
    @Test fun customPeriodBoundariesResetContinuouslyWithoutMondayOrRollingLookback() {
        val rule=FoodCycleRule(1,5,2)
        val entries=listOf(intake(first),intake(first+4),intake(first+5),intake(first+6))
        val last=FoodCycles.summary(first+4,food(),entries,rule)
        val next=FoodCycles.summary(first+5,food(),entries,rule)
        assertEquals(first,last.startDay);assertEquals(2,last.usedDays)
        assertEquals(first+5,next.startDay);assertEquals(first+9,next.endDay);assertEquals(1,next.usedDays)
    }
    @Test fun noActualRecordUsesAnUncommittedPreviewAndDoesNotInventAnAnchor() {
        val s=FoodCycles.summary(first,food(),emptyList(),FoodCycleRule(1,4,2))
        assertNull(s.anchorDay);assertEquals(first,s.startDay);assertEquals(first+3,s.endDay);assertEquals(0,s.usedDays)
        assertNull(FoodCycles.summary(first+2,food(),emptyList(),FoodCycleRule(1,4,2)).anchorDay)
    }
    @Test fun historicalCorrectionsUseTheSavedEarliestActualDateAndIgnoreFutureRecords() {
        val entries=listOf(intake(first+2),intake(first+20),intake(first-3))
        assertEquals(first-3,FoodCycles.summary(first+3,food(),entries,FoodCycleRule(1,7,3)).anchorDay)
        assertEquals(first+2,FoodCycles.summary(first+3,food(),entries.dropLast(1),FoodCycleRule(1,7,3)).anchorDay)
    }
    @Test fun sameDayCanAddMoreWithoutConsumingAnotherDayButStillSharesQuantityCap() {
        val rules=RecommendationRules(cycleRules=listOf(FoodCycleRule(1,7,1,100.0)))
        val context=RecommendationRuleContext(first,listOf(food()),emptyList(),listOf(intake(first,q=60.0)),rules)
        context.validate(mapOf(1L to 40.0))
        rejects {context.validate(mapOf(1L to 50.0))}
        val tomorrow=RecommendationRuleContext(first+1,listOf(food()),emptyList(),listOf(intake(first,q=60.0)),rules)
        rejects {tomorrow.validate(mapOf(1L to 10.0))}
        assertFalse(tomorrow.candidates(listOf(food().candidate())).single().allowed)
    }
    @Test fun mixedHistoricalUnitsCannotBeCountedAsCurrentNativeQuantity() {
        val old=listOf(intake(first,q=1.0,unit="份"))
        rejects {FoodCycles.summary(first,food(),old,FoodCycleRule(1,7,maximumQuantity=100.0))}
        val byDays=FoodCycles.summary(first,food(),old,FoodCycleRule(1,7,maximumDays=2))
        assertEquals(1,byDays.usedDays)
    }
    @Test fun minimumQuantityAppliesWithoutAnyEnabledExtrasAndDoesNotChangeExactLocks() {
        val f=food(minimum=100.0)
        val batch=ExtendedRecommendations.generate(first,listOf(f),emptyList(),emptyList(),emptyList(),target,emptyMap(),RecommendationPolicy(),RecommendationExtras())
        assertTrue(batch.options.isNotEmpty())
        assertTrue(batch.options.all {it.suggestion.quantities[1]?.let {q->q>=100}==true})
        rejects {ExtendedRecommendations.validate(first,listOf(f),emptyList(),emptyList(),emptyList(),target,mapOf(1L to 90.0),RecommendationPolicy(),RecommendationExtras())}
        val locked=ExtendedRecommendations.generate(first,listOf(f),emptyList(),emptyList(),emptyList(),target,mapOf(1L to 100.0),RecommendationPolicy(),RecommendationExtras())
        assertTrue(locked.options.all {it.suggestion.quantities[1]==100.0})
        assertTrue(ExtendedRecommendations.generate(first,listOf(f),emptyList(),emptyList(),emptyList(),target,mapOf(1L to 90.0),RecommendationPolicy(),RecommendationExtras()).options.isEmpty())
    }
    @Test fun minimumRoundsUpToTheNextNativeAdjustmentStep() {
        val f=food(minimum=105.0)
        val context=RecommendationRuleContext(first,listOf(f),emptyList(),emptyList(),RecommendationRules())
        val q=mutableMapOf(1L to 10.0)
        context.repair(q,listOf(f.candidate()),emptyMap())
        assertEquals(110.0,q[1]!!,0.0)
        rejects {context.repair(mutableMapOf(1L to 10.0),listOf(f.candidate()),mapOf(1L to 10.0))}
    }
    @Test fun preparationLabelsAreCustomEditableAndFilterByAnySelectedLabel() {
        val f=food(prep="宿舍微波,即食");val other=food(2,prep="自家蒸锅")
        val rules=RecommendationRules(availablePreparations=setOf("宿舍微波","无需器具"))
        val context=RecommendationRuleContext(first,listOf(f,other),emptyList(),emptyList(),rules)
        val candidates=context.candidates(listOf(f.candidate(),other.candidate()))
        assertTrue(candidates[0].allowed);assertFalse(candidates[1].allowed)
        context.validate(mapOf(1L to 10.0));rejects {context.validate(mapOf(2L to 10.0))}
        assertEquals("宿舍微波,即食",PreparationTags.parseInput(" 宿舍微波，\n即食 "))
        rejects {PreparationTags.parseInput("即食,即食")};rejects {PreparationTags.decode(" a")}
        rejects {PreparationTags.decode("x".repeat(41))}
    }
    @Test fun unclassifiedFoodsDoNotSilentlyPassAnEnabledPreparationFilter() {
        val f=food()
        val filtered=RecommendationRuleContext(first,listOf(f),emptyList(),emptyList(),RecommendationRules(availablePreparations=setOf("即食")))
        assertFalse(filtered.candidates(listOf(f.candidate())).single().allowed)
        assertTrue(RecommendationRuleContext(first,listOf(f),emptyList(),emptyList(),RecommendationRules()).candidates(listOf(f.candidate())).single().allowed)
    }
    @Test fun conditionalFoodPairRequiresAnAvailableFoodFromTheSpecifiedZone() {
        val foods=listOf(food(zone=5),food(2,zone=6))
        val zones=listOf(FoodZone(5,"主食",limitUnit="G"),FoodZone(6,"搭配",limitUnit="G"))
        val rules=RecommendationRules(conditions=listOf(ConditionalFoodRule("r","FOOD",1,"REQUIRE_ZONE",6)))
        val context=RecommendationRuleContext(first,foods,zones,emptyList(),rules)
        rejects {context.validate(mapOf(1L to 10.0))}
        context.validate(mapOf(1L to 10.0,2L to 10.0))
        val batch=ExtendedRecommendations.generate(first,foods,zones,emptyList(),emptyList(),target,mapOf(1L to 20.0),RecommendationPolicy(),RecommendationExtras(rules=rules))
        assertTrue(batch.options.isNotEmpty())
        batch.options.forEach {assertEquals(20.0,it.suggestion.quantities[1]!!,0.0);assertTrue(2L in it.suggestion.quantities)}
    }
    @Test fun conditionsIncludeAlreadyEatenFoodsAndNativeZoneExclusionIsHard() {
        val foods=listOf(food(zone=5),food(2,zone=6))
        val zones=listOf(FoodZone(5,"主食",limitUnit="G"),FoodZone(6,"其他",limitUnit="G"))
        val exclude=RecommendationRules(conditions=listOf(ConditionalFoodRule("x","ZONE",5,"EXCLUDE_ZONE",6)))
        val context=RecommendationRuleContext(first,foods,zones,listOf(intake(first)),exclude)
        rejects {context.validate(mapOf(2L to 10.0))};context.validate(mapOf(1L to 10.0))
        val require=RecommendationRules(conditions=listOf(ConditionalFoodRule("r","FOOD",1,"REQUIRE_ZONE",6)))
        RecommendationRuleContext(first,foods,zones,listOf(intake(first,id=2)),require).validate(mapOf(1L to 10.0))
    }
    @Test fun conditionalRequirementsNeverRelaxZoneMaximumsOrPreparationRules() {
        val foods=listOf(food(zone=5,prep="即食"),food(2,zone=6,prep="下锅"))
        val zones=listOf(FoodZone(5,"主食",limitUnit="G"),FoodZone(6,"搭配",dailyMaximum=0.0,limitUnit="G"))
        val rules=RecommendationRules(conditions=listOf(ConditionalFoodRule("r","FOOD",1,"REQUIRE_ZONE",6)),availablePreparations=setOf("即食"))
        val batch=ExtendedRecommendations.generate(first,foods,zones,emptyList(),emptyList(),target,mapOf(1L to 20.0),RecommendationPolicy(),RecommendationExtras(rules=rules))
        assertTrue(batch.options.isEmpty())
    }
    @Test fun cycleCapacityIsRecheckedFromFreshActualRecordsAtAdoption() {
        val f=food();val extras=RecommendationExtras(rules=RecommendationRules(cycleRules=listOf(FoodCycleRule(1,7,maximumQuantity=100.0))))
        ExtendedRecommendations.validate(first+2,listOf(f),emptyList(),listOf(intake(first,q=60.0)),emptyList(),target,mapOf(1L to 40.0),RecommendationPolicy(),extras)
        rejects {ExtendedRecommendations.validate(first+2,listOf(f),emptyList(),listOf(intake(first,q=80.0)),emptyList(),target,mapOf(1L to 40.0),RecommendationPolicy(),extras)}
    }
    @Test fun conditionalChainsCanIntroduceAnAtomicBundleWithoutSplittingIt() {
        val foods=listOf(food(zone=5),food(2,zone=6),food(3,zone=7),food(4,zone=8))
        val zones=(5L..8L).map {FoodZone(it,"分区$it",limitUnit="G")}
        val combo=FoodCombo(9,"搭配整套",ComboCodec.encode(listOf(ComboItem(2,"g",10.0),ComboItem(3,"g",20.0))))
        val rules=RecommendationRules(conditions=listOf(ConditionalFoodRule("a","FOOD",1,"REQUIRE_ZONE",6),ConditionalFoodRule("b","FOOD",3,"REQUIRE_ZONE",8)))
        val extras=RecommendationExtras(comboIds=setOf(9),rules=rules)
        val batch=ExtendedRecommendations.generate(first,foods,zones,emptyList(),listOf(combo),target,mapOf(1L to 10.0),RecommendationPolicy(),extras)
        assertTrue(batch.options.isNotEmpty())
        batch.options.forEach {o->
            assertEquals(10.0,o.suggestion.quantities[1]!!,0.0)
            assertEquals(o.suggestion.quantities[2]!!*2.0,o.suggestion.quantities[3]!!,1e-7)
            assertTrue(4L in o.suggestion.quantities)
        }
    }
    @Test fun legacyFractionalPortionStepsStillGenerateOnlyWholePortionCounts() {
        val f=food(unit="份").copy(step=0.5)
        f.validate()
        val extras=RecommendationExtras(varietyDays=1)
        val batch=ExtendedRecommendations.generate(first,listOf(f),emptyList(),emptyList(),emptyList(),target,emptyMap(),RecommendationPolicy(),extras)
        assertTrue(batch.options.isNotEmpty())
        assertTrue(batch.options.all {o->o.suggestion.quantities.values.all {it==kotlin.math.floor(it)}})
        rejects {ExtendedRecommendations.validate(first,listOf(f),emptyList(),emptyList(),emptyList(),target,mapOf(1L to 0.5),RecommendationPolicy(),extras)}
        assertEquals(0.5,f.step,0.0)
    }
    @Test fun temporarilyUnavailableOptionalComboDoesNotBlockOtherEligibleFoods() {
        val foods=listOf(food(prep="即食"),food(2,prep="下锅"),food(3,prep="即食"))
        val combo=FoodCombo(5,"需下锅组合",ComboCodec.encode(listOf(ComboItem(1,"g",10.0),ComboItem(2,"g",10.0))))
        val extras=RecommendationExtras(comboIds=setOf(5),rules=RecommendationRules(availablePreparations=setOf("即食")))
        val batch=ExtendedRecommendations.generate(first,foods,emptyList(),emptyList(),listOf(combo),target,emptyMap(),RecommendationPolicy(),extras)
        assertTrue(batch.options.isNotEmpty())
        assertTrue(batch.options.all {it.suggestion.quantities.keys==setOf(3L)})
    }
    @Test fun rulesCodecStrictlyRejectsBadIdsTypesAndMissingFields() {
        val rules=RecommendationRules(listOf(FoodCycleRule(1,9,3,200.0)),listOf(ConditionalFoodRule("a","FOOD",1,"REQUIRE_ZONE",5)),setOf("微波"))
        assertEquals(rules,RecommendationRulesCodec.decode(RecommendationRulesCodec.encode(rules)))
        fun bad(change:(JSONObject)->Unit) {val j=RecommendationRulesCodec.encode(rules);change(j);rejects {RecommendationRulesCodec.decode(j)}}
        bad {it.remove("conditions")};bad {it.put("extra",1)};bad {it.getJSONArray("cycleRules").getJSONObject(0).put("periodDays",7.5)}
        bad {it.getJSONArray("cycleRules").getJSONObject(0).put("maximumQuantity","200")}
        bad {it.getJSONArray("conditions").getJSONObject(0).put("triggerKind","MISSING")}
        bad {it.put("availablePreparations",JSONArray(listOf("a","a")))}
        rejects {FoodCycleRule(1,0,1).validate()};rejects {FoodCycleRule(1,7).validate()};rejects {FoodCycleRule(1,7,8).validate()}
    }
    @Test fun legacyExtrasWithoutRulesRetainOnlySafeEmptyDefaults() {
        val old=RecommendationExtras(100.0,true,7)
        val json=RecommendationExtrasCodec.encode(old).apply {remove("rules")}
        assertEquals(old,RecommendationExtrasCodec.decode(json))
        assertFalse(RecommendationExtrasCodec.decode(json).rules.enabled)
    }
    @Test fun profilesRoundTripTheirOwnIndependentPoliciesAndAllExtendedRules() {
        val a=RecommendationProfile("a","宿舍日",RecommendationPolicy(dailyBudgetYuan=20.0),RecommendationExtras(rules=RecommendationRules(availablePreparations=setOf("微波"))))
        val b=RecommendationProfile("b","外出日",RecommendationPolicy(protein=MacroTolerance(under=0.0)),RecommendationExtras(rules=RecommendationRules(cycleRules=listOf(FoodCycleRule(1,8,3)))))
        val profiles=listOf(a,b)
        assertEquals(profiles,RecommendationProfilesCodec.decode(RecommendationProfilesCodec.encode(profiles)))
        assertEquals(20.0,a.policy.dailyBudgetYuan!!,0.0);assertNull(b.policy.dailyBudgetYuan)
        rejects {RecommendationProfilesCodec.encode(listOf(a,a))}
        val raw=RecommendationProfilesCodec.encode(profiles).apply {getJSONObject(0).put("unknown",true)}
        rejects {RecommendationProfilesCodec.decode(raw)}
    }
    @Test fun referenceChecksRejectInvalidPortionCapsAndMissingFoodOrZone() {
        rejects {RecommendationRuleContext.validateReferences(listOf(food(unit="份")),emptyList(),RecommendationRules(cycleRules=listOf(FoodCycleRule(1,7,maximumQuantity=1.5))))}
        rejects {RecommendationRuleContext.validateReferences(listOf(food()),emptyList(),RecommendationRules(cycleRules=listOf(FoodCycleRule(999,7,2))))}
        rejects {RecommendationRuleContext.validateReferences(listOf(food()),emptyList(),RecommendationRules(conditions=listOf(ConditionalFoodRule("r","FOOD",1,"REQUIRE_ZONE",99))))}
        rejects {ConditionalFoodRule("x","ZONE",5,"EXCLUDE_ZONE",5).validate()}
    }
}
