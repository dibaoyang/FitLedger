package com.fitledger.data

import androidx.room.*
import com.fitledger.domain.Nutrition
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max

/** Empty weights means the batch has not been split into boxes. Otherwise the entire yield is allocated. */
object RecipeBoxes {
    fun validate(weights:List<Double>,finishedWeightG:Double) {
        require(finishedWeightG.isFinite() && finishedWeightG>0 && weights.size<=1000) {"成品重量或分装盒数无效"}
        require(weights.all {it.isFinite() && it>0}) {"每盒重量须为大于0的有效克数"}
        if(weights.isNotEmpty()) {
            val total=weights.sum()
            require(total.isFinite() && abs(total-finishedWeightG)<=max(1e-7,finishedWeightG*1e-8)) {"各盒重量合计必须等于本批成品总重量；未分装部分也请单独列出"}
        }
    }
    fun encode(weights:List<Double>,finishedWeightG:Double):String {
        validate(weights,finishedWeightG);return JSONArray(weights).toString()
    }
    fun decode(text:String,finishedWeightG:Double):List<Double> {
        require(text.length<=50000) {"分装资料过长"}
        val array=JSONArray(text);require(array.length()<=1000) {"分装盒数过多"}
        return (0 until array.length()).map {i->val n=array.get(i);require(n is Number) {"每盒重量格式无效"};n.toDouble()}.also {validate(it,finishedWeightG)}
    }
}

/** Provenance recipeId may outlive a deleted recipe; the ingredients and totals are self-contained. */
@Entity(tableName="recipe_batches",indices=[Index(value=["foodId"],unique=true)])
data class RecipeBatch(@PrimaryKey(autoGenerate=true) val id:Long=0,val recipeId:Long?,val name:String,
    val createdDay:Long,val ingredients:String,val finishedWeightG:Double,val boxWeights:String,
    @Embedded val nutrition:Nutrition,val costYuan:Double?,val foodId:Long?=null) {
    fun validate() {
        require(id>=0 && (recipeId==null || recipeId>0) && (foodId==null || foodId>0)) {"配方批次编号无效"}
        LocalDate.ofEpochDay(createdDay)
        require(name.isNotBlank() && name==name.trim() && name.length<=100 && name.none {it.code<32 || it.code==127}) {"批次名称须为100字以内的有效文字"}
        val items=RecipeIngredients.decode(ingredients)
        require(items.none {it.foodId==foodId}) {"批次食品不能成为自己的食材"}
        val result=RecipeCalculator.calculate(items,finishedWeightG)
        fun near(a:Double,b:Double)=abs(a-b)<=max(1e-8,max(abs(a),abs(b))*1e-8)
        require(nutrition.kcal>0 && nutrition.values().all {it.isFinite() && it>=0} && nutrition.values().zip(result.totalNutrition.values()).all {(a,b)->near(a,b)}) {"批次营养快照与食材不一致"}
        val totalCost=result.totalCostYuan
        require((costYuan==null && totalCost==null) || (costYuan!=null && totalCost!=null && costYuan.isFinite() && costYuan>=0 && near(costYuan,totalCost))) {"批次费用快照与食材不一致，未知费用不能按0保存"}
        RecipeBoxes.decode(boxWeights,finishedWeightG)
    }
    fun summary():RecipeSummary {validate();return RecipeCalculator.calculate(RecipeIngredients.decode(ingredients),finishedWeightG)}
    fun boxNutrition():List<Nutrition> {validate();return RecipeBoxes.decode(boxWeights,finishedWeightG).map {nutrition*(it/finishedWeightG)}}
}

@Dao interface RecipeBatchDao {
    @Query("SELECT * FROM recipe_batches ORDER BY createdDay DESC,id DESC") fun batches():Flow<List<RecipeBatch>>
    @Query("SELECT * FROM recipe_batches ORDER BY id") suspend fun allBatches():List<RecipeBatch>
    @Query("SELECT * FROM recipe_batches WHERE id=:id") suspend fun batch(id:Long):RecipeBatch?
    @Insert suspend fun insertBatch(batch:RecipeBatch):Long
    @Query("DELETE FROM recipe_batches WHERE id=:id") suspend fun deleteBatch(id:Long):Int
    @Query("DELETE FROM recipe_batches") suspend fun clearBatchesForRestore()
}

class RecipeBatchStore(private val db:LedgerDatabase) {
    val dao get()=db.recipeBatchDao()
    suspend fun save(batch:RecipeBatch,expectedRecipe:FoodRecipe,publishFood:Boolean):Long=db.withTransaction {
        batch.validate()
        require(batch.id==0L && batch.foodId==null && batch.recipeId==expectedRecipe.id && expectedRecipe.id>0) {"新批次不能覆盖已有批次或关联旧食品"}
        require(batch.createdDay==LocalDate.now().toEpochDay()) {"日期已变化，请重新预览本批次"}
        require(db.recipeDao().recipe(expectedRecipe.id)==expectedRecipe) {"原配方已修改或删除，请重新选择并核对"}
        require(batch.ingredients==expectedRecipe.ingredients) {"批次须使用已核对配方的食材快照；请先保存修改后的配方"}
        val summary=batch.summary()
        var next=batch
        if(publishFood) {
            val maximum=(db.dao().allFoods().filter {it.zoneId==null}.map {it.sortOrder}+db.dao().allZones().map {it.sortOrder}).maxOrNull()?:-1L
            require(maximum<Long.MAX_VALUE) {"食品排序超出范围"}
            val food=Food(name=batch.name,unit="g",basisAmount=100.0,nutrition=summary.per100g,step=10.0,maximum=2000.0,
                allowed=true,pricePerUnitYuan=summary.pricePerGramYuan,sortOrder=maximum+1)
            food.validate()
            require((food.nutrition*(food.maximum/food.basisAmount)).values().all {it.isFinite() && it>=0}) {"本批食品营养超出范围，请核对成品重量"}
            next=next.copy(foodId=db.recipeDao().insertFood(food))
        }
        next.validate();dao.insertBatch(next)
    }
    suspend fun delete(expected:RecipeBatch)=db.withTransaction {
        require(dao.batch(expected.id)==expected) {"批次已变化，请重新确认"}
        check(dao.deleteBatch(expected.id)==1)
        // Independent generated food and every historical intake remain untouched.
    }
}
