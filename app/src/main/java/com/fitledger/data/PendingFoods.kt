package com.fitledger.data

import androidx.room.*
import com.fitledger.domain.*
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.util.UUID

/** A saved intention is never an intake. The applied marker survives deletion of the intake. */
@Entity(tableName="pending_foods",indices=[Index("day"),Index("groupId"),Index(value=["appliedIntakeId"],unique=true)])
data class PendingFood(@PrimaryKey(autoGenerate=true) val id:Long=0,val day:Long,val groupId:String,
    val foodId:Long,val name:String,val unit:String,val quantity:Double,@Embedded val nutrition:Nutrition,
    val costYuan:Double?=null,val appliedIntakeId:Long?=null,val createdAt:Long) {
    fun validate() {
        LocalDate.ofEpochDay(day)
        require(id>=0 && foodId>0 && groupId.isNotBlank() && groupId.length<=80 && groupId.none {it.code<32}) {"待吃条目编号或分组无效"}
        require(name.isNotBlank() && name.length<=200 && name.none {it.code<32} && unit in setOf("g","份")) {"待吃食品名称或计量无效"}
        PortionQuantities.requireWhole(unit,quantity)
        require(nutrition.values().all {it.isFinite() && it>=0} && nutrition.kcal>0) {"待吃营养快照无效"}
        require(costYuan==null || (costYuan.isFinite() && costYuan>=0)) {"待吃费用快照无效"}
        require(appliedIntakeId==null || appliedIntakeId>0) {"待吃确认标记无效"}
        require(createdAt>0) {"待吃保存时间无效"}
    }
}

@Dao
interface PendingFoodDao {
    @Query("SELECT * FROM pending_foods ORDER BY day DESC,createdAt DESC,id") fun pendingFoods():Flow<List<PendingFood>>
    @Query("SELECT * FROM pending_foods ORDER BY id") suspend fun allPendingFoods():List<PendingFood>
    @Query("SELECT * FROM pending_foods WHERE id=:id") suspend fun pendingFood(id:Long):PendingFood?
    @Insert suspend fun insertPendingFood(item:PendingFood):Long
    @Update suspend fun updatePendingFood(item:PendingFood):Int
    @Query("DELETE FROM pending_foods WHERE id=:id") suspend fun deletePendingFood(id:Long):Int
    @Query("DELETE FROM pending_foods") suspend fun clearPendingFoodsForRestore()
}

data class PendingFoodUpdate(val before:PendingFood,val food:Food,val after:PendingFood)

object PendingFoodUpdates {
    fun preview(item:PendingFood,food:Food,quantity:Double):PendingFoodUpdate {
        item.validate();food.validate()
        require(item.appliedIntakeId==null && food.id==item.foodId && !food.isDeleted) {"待吃项已经确认或食品不可用"}
        require(food.unit==item.unit) {"食品计量单位已变化，不能套用原数量；请删除待吃项后重新安排"}
        PortionQuantities.requireWhole(food.unit,quantity)
        val updated=item.copy(name=food.name,quantity=quantity,nutrition=food.nutrition*(quantity/food.basisAmount),
            costYuan=FoodPricing.cost(food.pricePerUnitYuan,quantity)).also {it.validate()}
        return PendingFoodUpdate(item,food,updated)
    }

    fun unchanged(item:PendingFood,food:Food?):Boolean=food!=null && runCatching {
        preview(item.copy(appliedIntakeId=null),food,item.quantity).after.copy(appliedIntakeId=item.appliedIntakeId)==item
    }.getOrDefault(false)
}

class PendingFoodStore(private val db:LedgerDatabase) {
    val dao get()=db.pendingFoodDao()
    private val ledger get()=db.dao()
    private fun today()=LocalDate.now().toEpochDay()
    private suspend fun requireToday(day:Long) {
        require(day==today()) {"日期已变化，请重新打开今日待吃清单"}
        require(ledger.body(day)!=null && ledger.target(day)!=null) {"请先完成今日身体数据"}
    }

    /** Revalidates a recommendation before saving its reviewed current food snapshots. */
    suspend fun saveRecommendation(day:Long,quantities:Map<Long,Double>,policy:RecommendationPolicy=RecommendationPolicy(),
        extras:RecommendationExtras=RecommendationExtras()):Int=db.withTransaction {
        requireToday(day)
        require(db.dailyEatingDao().dayMode(day)?.isIndulgence!=true) {"放纵日已暂停推荐，请先恢复今日记录"}
        require(quantities.isNotEmpty() && quantities.size<=1000) {"没有可保存的推荐食品，或条目过多"}
        val foods=ledger.allFoods();val zones=ledger.allZones();val intakes=ledger.allIntakes()
        ExtendedRecommendations.validate(day,foods,zones,intakes,db.featureDao().allCombos(),
            requireNotNull(ledger.target(day)).nutrition,quantities,policy,extras)
        val group=UUID.randomUUID().toString();val time=System.currentTimeMillis()
        val items=quantities.map {(id,q)->
            val food=requireNotNull(foods.find {it.id==id && !it.isDeleted}) {"推荐食品已删除，请重新计算"}
            food.validate();PortionQuantities.requireWhole(food.unit,q)
            PendingFood(day=day,groupId=group,foodId=id,name=food.name,unit=food.unit,quantity=q,
                nutrition=food.nutrition*(q/food.basisAmount),costYuan=FoodPricing.cost(food.pricePerUnitYuan,q),createdAt=time).also {it.validate()}
        }
        requireToday(day)
        items.forEach {dao.insertPendingFood(it)}
        items.size
    }

    /** Manual actual consumption may differ from recommendation bounds. Duplicate taps are safe. */
    suspend fun markEaten(expected:PendingFood):Long=db.withTransaction {
        val item=requireNotNull(dao.pendingFood(expected.id)) {"待吃项已删除，请刷新"}
        item.appliedIntakeId?.let {return@withTransaction it}
        require(item==expected) {"待吃数量或资料已变化，请重新核对"}
        requireToday(item.day);item.validate()
        val food=requireNotNull(ledger.food(item.foodId)) {"食品不存在，请先处理待吃项"}
        require(PendingFoodUpdates.unchanged(item,food)) {"食品名称、营养、单位或价格已改变，请先修改并确认刷新待吃资料"}
        PortionQuantities.requireWhole(item.unit,item.quantity)
        requireToday(item.day)
        val id=ledger.insertIntake(Intake(day=item.day,foodId=item.foodId,name=item.name,unit=item.unit,
            quantity=item.quantity,nutrition=item.nutrition,costYuan=item.costYuan))
        check(dao.updatePendingFood(item.copy(appliedIntakeId=id))==1)
        id
    }

    /** No snapshots or quantities can change between review and confirmation. */
    suspend fun update(expected:PendingFoodUpdate)=db.withTransaction {
        requireToday(expected.before.day)
        require(dao.pendingFood(expected.before.id)==expected.before && expected.before.appliedIntakeId==null) {"待吃项已变化或已吃，请重新核对"}
        val food=requireNotNull(ledger.food(expected.food.id)) {"食品不存在"}
        require(food==expected.food && PendingFoodUpdates.preview(expected.before,food,expected.after.quantity)==expected) {"食品资料或待吃预览已变化，未保存任何修改"}
        requireToday(expected.before.day)
        check(dao.updatePendingFood(expected.after)==1)
    }

    /** An expired unconsumed intention may be discarded, never converted into today's consumption. */
    suspend fun remove(expected:PendingFood)=db.withTransaction {
        val item=requireNotNull(dao.pendingFood(expected.id)) {"待吃项已删除"}
        require(item==expected && item.appliedIntakeId==null) {"条目已变化或已吃，确认标记需要保留；实际摄入可在每日记录中修改或删除"}
        check(dao.deletePendingFood(item.id)==1)
    }
}
