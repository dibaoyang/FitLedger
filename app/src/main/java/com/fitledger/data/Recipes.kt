package com.fitledger.data

import androidx.room.*
import com.fitledger.domain.FoodPricing
import com.fitledger.domain.Nutrition
import com.fitledger.domain.PortionQuantities
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

/** Ingredient nutrients and price are frozen TOTALS for quantity, never live links to current food values. */
data class RecipeIngredient(val foodId:Long,val name:String,val unit:String,val quantity:Double,
    val nutrition:Nutrition,val costYuan:Double?) {
    fun validate() {
        require(foodId>0 && name.isNotBlank() && name.length<=200 && name.none {it.code<32 || it.code==127}) {"配方食材名称或编号无效"}
        require(unit in setOf("g","份") && quantity.isFinite() && quantity>0) {"食材数量须为大于0的有效克数或份数"}
        require(nutrition.values().all {it.isFinite() && it>=0} && nutrition.kcal>0) {"配方食材营养无效"}
        require(costYuan==null || (costYuan.isFinite() && costYuan>=0)) {"配方食材费用无效"}
    }
    fun resized(amount:Double):RecipeIngredient {
        validate();require(amount.isFinite() && amount>0) {"食材数量须大于0"}
        val factor=amount/quantity
        require(factor.isFinite() && factor>0) {"配方食材换算超出范围"}
        return copy(quantity=amount,nutrition=nutrition*factor,costYuan=costYuan?.times(factor)).also {it.validate()}
    }
}

object RecipeIngredients {
    private val fields=setOf("foodId","name","unit","quantity","nutrition","costYuan")
    private val nutrientFields=setOf("kcal","carbs","protein","fat")
    private const val MAX_CHARS=100000
    fun validate(items:List<RecipeIngredient>) {
        require(items.size in 1..50 && items.map {it.foodId}.distinct().size==items.size) {"配方需包含1至50种不重复食材"}
        items.forEach {it.validate()}
    }
    fun fromFood(food:Food,amount:Double):RecipeIngredient {
        food.validate();require(food.id>0 && !food.isDeleted) {"食材已删除或尚未保存，请重新选择"}
        require(amount.isFinite() && amount>0) {"食材数量须大于0"}
        return RecipeIngredient(food.id,food.name,food.unit,amount,food.nutrition*(amount/food.basisAmount),
            FoodPricing.cost(food.pricePerUnitYuan,amount)).also {it.validate()}
    }
    fun encode(items:List<RecipeIngredient>):String {
        validate(items)
        return JSONArray(items.map {item->JSONObject().put("foodId",item.foodId).put("name",item.name).put("unit",item.unit)
            .put("quantity",item.quantity).put("costYuan",item.costYuan ?: JSONObject.NULL)
            .put("nutrition",JSONObject().put("kcal",item.nutrition.kcal).put("carbs",item.nutrition.carbs)
                .put("protein",item.nutrition.protein).put("fat",item.nutrition.fat))}).toString().also {
            require(it.length<=MAX_CHARS) {"配方资料过长"}
        }
    }
    fun decode(text:String):List<RecipeIngredient> {
        require(text.length<=MAX_CHARS) {"配方资料过长"}
        val array=JSONArray(text);require(array.length() in 1..50) {"配方需包含1至50种食材"}
        fun number(j:JSONObject,key:String):Double {
            val value=j.get(key);require(value is Number && value.toDouble().isFinite()) {"配方数值无效"};return value.toDouble()
        }
        return (0 until array.length()).map {index->
            val item=array.getJSONObject(index)
            require(item.keys().asSequence().toSet()==fields) {"配方食材字段不完整"}
            val id=item.get("foodId")
            require(id is Number && id.toDouble().isFinite()) {"配方食材编号无效"}
            val foodId=requireNotNull(runCatching {id.toString().toBigDecimal().longValueExact()}.getOrNull()) {"配方食材编号须为整数"}
            val name=item.get("name");val unit=item.get("unit")
            require(name is String && unit is String) {"配方食材名称或单位无效"}
            val n=item.getJSONObject("nutrition")
            require(n.keys().asSequence().toSet()==nutrientFields) {"配方营养字段不完整"}
            RecipeIngredient(foodId,name,unit,number(item,"quantity"),
                Nutrition(number(n,"kcal"),number(n,"carbs"),number(n,"protein"),number(n,"fat")),
                if(item.isNull("costYuan")) null else number(item,"costYuan"))
        }.also(::validate)
    }
}

data class RecipeSummary(val totalNutrition:Nutrition,val per100g:Nutrition,val knownCostYuan:Double,
    val missingPriceCount:Int,val finishedWeightG:Double) {
    val totalCostYuan:Double? get()=if(missingPriceCount==0) knownCostYuan else null
    val pricePerGramYuan:Double? get()=totalCostYuan?.div(finishedWeightG)
    val costPer100gYuan:Double? get()=pricePerGramYuan?.times(100.0)
}
object RecipeCalculator {
    fun calculate(items:List<RecipeIngredient>,finishedWeightG:Double):RecipeSummary {
        RecipeIngredients.validate(items)
        require(finishedWeightG.isFinite() && finishedWeightG>0) {"请填写大于0的成品实际总重量"}
        val total=items.fold(Nutrition()) {sum,item->sum+item.nutrition}
        val per100=total*(100.0/finishedWeightG)
        val known=items.sumOf {it.costYuan ?: 0.0}
        require(total.values().all {it.isFinite() && it>=0} && per100.values().all {it.isFinite() && it>=0} && per100.kcal>0 &&
            known.isFinite() && (known/finishedWeightG).isFinite() && (known/finishedWeightG*100.0).isFinite()) {"配方营养或费用合计超出计算范围"}
        return RecipeSummary(total,per100,known,items.count {it.costYuan==null},finishedWeightG)
    }
}

@Entity(tableName="food_recipes",indices=[Index(value=["foodId"],unique=true)])
data class FoodRecipe(@PrimaryKey(autoGenerate=true) val id:Long=0,val name:String,val ingredients:String,
    val finishedWeightG:Double,val foodId:Long?=null) {
    fun validate() {
        require(id>=0 && name.isNotBlank() && name==name.trim() && name.length<=100 && name.none {it.code<32 || it.code==127}) {"配方名称须为100字以内的非空文字"}
        require(foodId==null || foodId>0) {"配方关联食品编号无效"}
        val items=RecipeIngredients.decode(ingredients)
        require(items.none {it.foodId==foodId}) {"配方不能使用自己生成的食品作为食材"}
        RecipeCalculator.calculate(items,finishedWeightG)
    }
}
@Dao
interface RecipeDao {
    @Query("SELECT * FROM food_recipes ORDER BY name,id") fun recipes():Flow<List<FoodRecipe>>
    @Query("SELECT * FROM food_recipes ORDER BY id") suspend fun allRecipes():List<FoodRecipe>
    @Query("SELECT * FROM food_recipes WHERE id=:id") suspend fun recipe(id:Long):FoodRecipe?
    @Insert suspend fun insertRecipe(recipe:FoodRecipe):Long
    @Update suspend fun updateRecipe(recipe:FoodRecipe):Int
    @Query("DELETE FROM food_recipes WHERE id=:id") suspend fun deleteRecipe(id:Long):Int
    @Query("DELETE FROM food_recipes") suspend fun clearRecipesForRestore()
    @Insert suspend fun insertFood(food:Food):Long
}

/** Changes only definitions and explicitly published foods; historical targets/intakes remain frozen. */
class RecipeStore(private val db:LedgerDatabase) {
    val dao get()=db.recipeDao()
    private fun sameSnapshot(first:RecipeIngredient,second:RecipeIngredient):Boolean {
        fun near(a:Double,b:Double)=abs(a-b)<=1e-8*max(1.0,max(abs(a),abs(b)))
        return first.foodId==second.foodId && first.name==second.name && first.unit==second.unit && near(first.quantity,second.quantity) &&
            first.nutrition.values().zip(second.nutrition.values()).all {(a,b)->near(a,b)} &&
            ((first.costYuan==null && second.costYuan==null) || (first.costYuan!=null && second.costYuan!=null && near(first.costYuan,second.costYuan)))
    }
    suspend fun save(recipe:FoodRecipe,publishFood:Boolean,expected:FoodRecipe?=null,expectedFood:Food?=null):Long=db.withTransaction {
        recipe.validate()
        val saved=if(recipe.id==0L) {
            require(expected==null && recipe.foodId==null) {"新配方不能关联已有食品"};null
        } else requireNotNull(dao.recipe(recipe.id)) {"配方已删除，请重新打开"}.also {
            require(it==expected && recipe.foodId==it.foodId) {"配方已变化，请重新打开核对"}
        }
        val oldItems=saved?.let {RecipeIngredients.decode(it.ingredients).associateBy {item->item.foodId}}.orEmpty()
        val items=RecipeIngredients.decode(recipe.ingredients)
        for(item in items) {
            val previous=oldItems[item.foodId]
            if(previous==null || previous.quantity!=item.quantity || previous.unit!=item.unit) PortionQuantities.requireWhole(item.unit,item.quantity)
            val current=requireNotNull(db.dao().food(item.foodId)) {"配方食材不存在，请重新选择"}
            val frozen=oldItems[item.foodId]?.let {runCatching {it.resized(item.quantity)}.getOrNull()}
            if(frozen==null || !sameSnapshot(item,frozen)) {
                require(sameSnapshot(item,RecipeIngredients.fromFood(current,item.quantity))) {"食材资料已变化，请刷新预览后再保存"}
            }
        }
        var updated=recipe
        if(publishFood) {
            val result=RecipeCalculator.calculate(items,recipe.finishedWeightG)
            val linked=saved?.foodId?.let {id->requireNotNull(db.dao().food(id)) {"关联食品不存在"}.also {
                require(it==expectedFood && !it.isDeleted && it.unit=="g") {"关联食品已修改、删除或改变单位，请重新核对；可只保存配方"}
            }}
            val food=if(linked==null) {
                require(expectedFood==null) {"新食品预览无效"}
                val order=(db.dao().allFoods().filter {it.zoneId==null}.map {it.sortOrder}+db.dao().allZones().map {it.sortOrder}).maxOrNull() ?: -1L
                require(order<Long.MAX_VALUE) {"食品排序超出范围"}
                Food(name=recipe.name,unit="g",basisAmount=100.0,nutrition=result.per100g,step=10.0,maximum=2000.0,
                    allowed=true,pricePerUnitYuan=result.pricePerGramYuan,sortOrder=order+1)
            } else linked.copy(name=recipe.name,basisAmount=100.0,nutrition=result.per100g,pricePerUnitYuan=result.pricePerGramYuan)
            food.validate()
            require((food.nutrition*(food.maximum/food.basisAmount)).values().all {it.isFinite() && it>=0}) {"生成食品在最大推荐量下超出计算范围，请核对成品总重量"}
            val foodId=if(linked==null) dao.insertFood(food) else {db.dao().saveFood(food);food.id}
            updated=updated.copy(foodId=foodId)
        }
        updated.validate()
        if(saved==null) dao.insertRecipe(updated) else {check(dao.updateRecipe(updated)==1);saved.id}
    }
    suspend fun delete(expected:FoodRecipe)=db.withTransaction {
        require(dao.recipe(expected.id)==expected) {"配方已变化，请重新确认"}
        check(dao.deleteRecipe(expected.id)==1)
        // The generated food remains available and its historical references retain their identities.
    }
}

data class FoodBatchPatch(val favorite:Boolean?=null,val allowed:Boolean?=null,val nutritionTags:String?=null) {
    fun validate() {
        require(favorite!=null || allowed!=null || nutritionTags!=null) {"请至少选择一个要批量修改的项目"}
        nutritionTags?.let {text->
            val tags=if(text.isEmpty()) emptyList() else text.split(',')
            require(tags.distinct().size==tags.size && tags.all {it in setOf("CARBS","PROTEIN","FAT")}) {"营养标签无效"}
        }
    }
    fun applyTo(food:Food):Food {
        validate()
        return food.copy(favorite=favorite ?: food.favorite,allowed=allowed ?: food.allowed,nutritionTags=nutritionTags ?: food.nutritionTags)
            .also {it.validate()}
    }
}
class BatchFoodStore(private val db:LedgerDatabase) {
    suspend fun apply(expected:List<Food>,patch:FoodBatchPatch):Int=db.withTransaction {
        patch.validate()
        require(expected.isNotEmpty() && expected.size<=100000 && expected.all {it.id>0 && !it.isDeleted} &&
            expected.map {it.id}.distinct().size==expected.size) {"请选择未删除且不重复的食品"}
        val fresh=expected.map {old->requireNotNull(db.dao().food(old.id)) {"食品不存在，请刷新后重选"}.also {
            require(it==old && !it.isDeleted) {"食品资料已变化，请刷新并重新预览，未保存任何修改"}
        }}
        val updated=fresh.map(patch::applyTo)
        updated.forEach {db.dao().saveFood(it)}
        updated.size
    }
}
