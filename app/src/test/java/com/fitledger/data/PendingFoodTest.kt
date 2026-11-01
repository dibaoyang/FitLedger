package com.fitledger.data

import com.fitledger.domain.*
import org.junit.Assert.*
import org.junit.Test

class PendingFoodTest {
    private val rice=Food(7,"米饭","g",100.0,Nutrition(116.0,26.0,2.6,0.3),10.0,500.0,true,pricePerUnitYuan=0.01)
    private fun item(food:Food=rice,q:Double=200.0)=PendingFood(12,20000,"group-1",food.id,food.name,food.unit,q,
        food.nutrition*(q/food.basisAmount),FoodPricing.cost(food.pricePerUnitYuan,q),createdAt=1700000000000)
    private fun rejects(block:()->Unit)=assertTrue(runCatching(block).isFailure)

    @Test fun pendingUpdatePreservesIdentityAndUsesAnExplicitReviewedFreshFoodSnapshot() {
        val before=item()
        val current=rice.copy(name="改名的米饭",nutrition=rice.nutrition*2.0,pricePerUnitYuan=0.03)
        val p=PendingFoodUpdates.preview(before,current,125.0)
        assertEquals(before,p.before);assertEquals(current,p.food)
        assertEquals(before.copy(name=current.name,quantity=125.0,nutrition=current.nutrition*1.25,costYuan=3.75),p.after)
        assertEquals(200.0,before.quantity,0.0);assertEquals(2.0,before.costYuan!!,0.0)
    }

    @Test fun changingPreparationOrRecommendationPermissionsDoesNotRewriteManualConsumptionSnapshots() {
        val before=item()
        assertTrue(PendingFoodUpdates.unchanged(before,rice.copy(allowed=false,step=20.0,maximum=100.0,favorite=true)))
        assertFalse(PendingFoodUpdates.unchanged(before,rice.copy(pricePerUnitYuan=0.02)))
        assertFalse(PendingFoodUpdates.unchanged(before,rice.copy(nutrition=rice.nutrition*2.0)))
        assertFalse(PendingFoodUpdates.unchanged(before,rice.copy(name="另一个名称")))
    }

    @Test fun unknownCostsRemainUnknownAndFreeIsAnExplicitZero() {
        val unknown=rice.copy(pricePerUnitYuan=null)
        val before=item(unknown)
        assertNull(before.costYuan);assertNull(PendingFoodUpdates.preview(before,unknown,100.0).after.costYuan)
        assertFalse(PendingFoodUpdates.unchanged(before,rice.copy(pricePerUnitYuan=0.0)))
        assertEquals(0.0,PendingFoodUpdates.preview(before,rice.copy(pricePerUnitYuan=0.0),100.0).after.costYuan!!,0.0)
    }

    @Test fun portionsAreWholeForEveryNewPendingItemAndUpdateWhileGramsKeepPrecision() {
        val bread=rice.copy(id=8,name="面包",unit="份",basisAmount=1.0,step=1.0,maximum=10.0,pricePerUnitYuan=3.0)
        val before=item(bread,2.0)
        before.validate();assertEquals(3.0,PendingFoodUpdates.preview(before,bread,3.0).after.quantity,0.0)
        rejects {item(bread,0.5).validate()};rejects {PendingFoodUpdates.preview(before,bread,1.5)}
        assertEquals(123.456,PendingFoodUpdates.preview(item(),rice,123.456).after.quantity,0.0)
    }

    @Test fun unavailableOrChangedUnitsAndAlreadyAppliedItemsCannotBeRefreshed() {
        val before=item()
        rejects {PendingFoodUpdates.preview(before,rice.copy(isDeleted=true),100.0)}
        rejects {PendingFoodUpdates.preview(before,rice.copy(unit="份"),100.0)}
        rejects {PendingFoodUpdates.preview(before,rice.copy(id=999),100.0)}
        rejects {PendingFoodUpdates.preview(before.copy(appliedIntakeId=8),rice,100.0)}
        assertFalse(PendingFoodUpdates.unchanged(before,null))
        assertFalse(PendingFoodUpdates.unchanged(before,rice.copy(isDeleted=true)))
    }

    @Test fun pendingValidationRejectsMalformedOrOverflowingSnapshotsInsteadOfCreatingConsumption() {
        val before=item()
        for(invalid in listOf(before.copy(id=-1),before.copy(groupId=""),before.copy(foodId=0),before.copy(createdAt=0),
            before.copy(appliedIntakeId=0),before.copy(unit="kg"),before.copy(quantity=0.0),before.copy(quantity=Double.NaN),
            before.copy(costYuan=-1.0),before.copy(nutrition=Nutrition(Double.POSITIVE_INFINITY)))) rejects {invalid.validate()}
        rejects {PendingFoodUpdates.preview(before,rice,Double.MAX_VALUE)}
    }

    @Test fun anAppliedSnapshotStillComparesItsFoodCorrectlyWithoutLosingItsMarker() {
        val applied=item().copy(appliedIntakeId=35)
        assertTrue(PendingFoodUpdates.unchanged(applied,rice))
        assertEquals(35L,applied.appliedIntakeId!!)
    }
}
