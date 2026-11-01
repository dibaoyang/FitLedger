package com.fitledger.domain

import org.junit.Assert.*
import org.junit.Test

class WeightRatiosTest {
    private val rule=WeightRatio(1,2,0.5)
    private fun food(id:Long,mass:Double?=1.0,step:Double=10.0,max:Double=500.0)=WeightRatioInput(id,"食品$id",mass,step,max)
    private val foods=listOf(food(1),food(2),food(3))
    private fun rejects(block:()->Unit) {assertTrue(runCatching(block).isFailure)}

    @Test fun exactRatioUsesWeightsAndPreservesReferenceQuantity() {
        val preview=WeightRatios.round(mapOf(2L to 200.0),listOf(rule),foods)
        assertEquals(mapOf(1L to 100.0,2L to 200.0),preview.quantities)
        assertEquals(100.0,preview.deviations.single().targetGrams,0.0)
        assertEquals(0.0,preview.deviations.single().deltaGrams,0.0)
    }
    @Test fun nearestStepShowsActualTargetAndSignedDeviation() {
        val p=WeightRatios.round(mapOf(2L to 210.0),listOf(rule),foods)
        val d=p.deviations.single()
        assertEquals(110.0,p.quantities[1]!!,0.0)
        assertEquals(105.0,d.targetGrams,0.0)
        assertEquals(110.0,d.actualGrams,0.0)
        assertEquals(5.0,d.deltaGrams,0.0)
        assertEquals(100.0*5/105,d.percent,1e-10)
        rejects {WeightRatios.deviations(mapOf(1L to 100.0,2L to 210.0),listOf(rule),foods)}
    }
    @Test fun portionsRequireKnownMassAndNeverTreatAPortionAsOneGram() {
        val p=WeightRatios.round(mapOf(2L to 200.0),listOf(rule),listOf(food(1,mass=60.0,step=1.0,max=10.0),food(2)))
        assertEquals(2.0,p.quantities[1]!!,0.0)
        assertEquals(120.0,p.deviations.single().actualGrams,0.0)
        rejects {WeightRatios.round(mapOf(2L to 200.0),listOf(rule),listOf(food(1,mass=null),food(2)))}
    }
    @Test fun chainsUseActualRoundedReferenceInDependencyOrder() {
        val rules=listOf(rule,WeightRatio(2,3,0.5))
        val p=WeightRatios.round(mapOf(3L to 210.0),rules,foods)
        assertEquals(110.0,p.quantities[2]!!,0.0)
        assertEquals(60.0,p.quantities[1]!!,0.0)
        assertEquals(55.0,p.deviations.single {it.foodId==1L}.targetGrams,0.0)
    }
    @Test fun locksAreExactAndConflictsNeverSilentlyOverwritten() {
        val locked=100.000000001
        val p=WeightRatios.round(mapOf(1L to locked,2L to 200.0),listOf(rule),foods,mapOf(1L to locked))
        assertEquals(locked,p.quantities[1]!!,0.0)
        rejects {WeightRatios.round(mapOf(1L to 90.0,2L to 200.0),listOf(rule),foods,mapOf(1L to 90.0))}
        rejects {WeightRatios.round(mapOf(2L to 200.0),listOf(rule),foods,mapOf(3L to 10.0))}
    }
    @Test fun maximumIsHardForRecommendationButNotManualPresetPreparation() {
        val small=listOf(food(1,max=50.0),food(2))
        rejects {WeightRatios.round(mapOf(2L to 200.0),listOf(rule),small)}
        assertEquals(100.0,WeightRatios.round(mapOf(2L to 200.0),listOf(rule),small,enforceMaximum=false).quantities[1]!!,0.0)
    }
    @Test fun inactiveRulesDoNotForceFoodsButAnActiveDependentNeedsItsReference() {
        assertEquals(mapOf(3L to 10.0),WeightRatios.round(mapOf(3L to 10.0),listOf(rule),foods).quantities)
        rejects {WeightRatios.round(mapOf(1L to 10.0),listOf(rule),foods)}
    }
    @Test fun cyclesDuplicatesSelfReferencesAndNonfiniteRatiosAreRejected() {
        for(r in listOf(listOf(rule,WeightRatio(2,1,2.0)),listOf(rule,rule),listOf(WeightRatio(1,1,1.0)),listOf(rule.copy(fraction=0.0)),listOf(rule.copy(fraction=Double.NaN))))
            rejects {WeightRatios.validate(r)}
    }
    @Test fun zeroRoundingOverflowMissingFoodsAndMalformedJsonAreRejected() {
        rejects {WeightRatios.round(mapOf(2L to 1.0),listOf(rule),foods)}
        rejects {WeightRatios.round(mapOf(2L to Double.MAX_VALUE),listOf(rule.copy(fraction=4.0)),foods)}
        rejects {WeightRatios.round(mapOf(2L to 200.0),listOf(rule),foods.drop(1))}
        for(encoded in listOf("[{\"foodId\":1.2,\"referenceFoodId\":2,\"fraction\":0.5}]","[{\"foodId\":1,\"referenceFoodId\":2,\"fraction\":\"0.5\"}]","[{\"foodId\":1,\"referenceFoodId\":2,\"fraction\":0.5,\"extra\":1}]")) rejects {WeightRatios.decode(encoded)}
        assertEquals(listOf(rule),WeightRatios.decode(WeightRatios.encode(listOf(rule))))
        assertEquals(emptyList<WeightRatio>(),WeightRatios.decode(""))
    }
    @Test fun manualActualDeviationCanDescribeAnOffRatioOrPartialRecord() {
        val d=WeightRatios.describeActual(mapOf(1L to 130.0,2L to 200.0),listOf(rule),foods).single()
        assertEquals(30.0,d.deltaGrams,0.0)
        assertEquals(30.0,d.percent,0.0)
        assertTrue(WeightRatios.describeActual(mapOf(1L to 130.0),listOf(rule),foods).isEmpty())
    }
}
