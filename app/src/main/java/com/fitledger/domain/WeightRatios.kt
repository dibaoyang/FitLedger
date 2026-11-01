package com.fitledger.domain

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.floor

data class WeightRatio(val foodId:Long,val referenceFoodId:Long,val fraction:Double)
data class WeightRatioInput(val id:Long,val name:String,val gramsPerUnit:Double?,val step:Double,val maximum:Double)
data class WeightRatioDeviation(val foodId:Long,val referenceFoodId:Long,val fraction:Double,
    val targetGrams:Double,val actualGrams:Double) {
    val deltaGrams get()=actualGrams-targetGrams
    val percent get()=deltaGrams/targetGrams*100.0
}
data class WeightRatioPreview(val quantities:Map<Long,Double>,val deviations:List<WeightRatioDeviation>)

/** Native food quantities are never confused with mass or with native zone quotas. */
object WeightRatios {
    fun validate(rules:List<WeightRatio>) {
        require(rules.size<=50 && rules.map {it.foodId}.distinct().size==rules.size) {"最多50条重量比例，每种食品只能有一个参考食品"}
        require(rules.all {it.foodId>0 && it.referenceFoodId>0 && it.foodId!=it.referenceFoodId && it.fraction.isFinite() && it.fraction>0}) {"重量比例须为不同食品之间大于0的有效数值"}
        val byId=rules.associateBy {it.foodId}
        rules.forEach {rule->
            val visited=mutableSetOf<Long>();var current:Long?=rule.foodId
            while(current!=null) {require(visited.add(current)) {"重量比例不能循环引用"};current=byId[current]?.referenceFoodId}
        }
    }
    fun encode(rules:List<WeightRatio>):String {
        validate(rules)
        return if(rules.isEmpty()) "" else JSONArray(rules.map {JSONObject().put("foodId",it.foodId).put("referenceFoodId",it.referenceFoodId).put("fraction",it.fraction)}).toString()
    }
    fun decode(encoded:String):List<WeightRatio> {
        if(encoded.isEmpty()) return emptyList()
        require(encoded.length<=20000) {"重量比例内容过长"}
        val array=JSONArray(encoded);require(array.length()<=50) {"重量比例过多"}
        fun id(j:JSONObject,key:String):Long {
            val value=j.get(key);require(value is Number) {"比例食品编号无效"}
            return requireNotNull(runCatching {value.toString().toBigDecimal().longValueExact()}.getOrNull()) {"比例食品编号须为整数"}
        }
        return (0 until array.length()).map {i->val j=array.getJSONObject(i)
            require(j.keys().asSequence().toSet()==setOf("foodId","referenceFoodId","fraction")) {"重量比例字段无效"}
            val fraction=j.get("fraction");require(fraction is Number) {"重量比例数值无效"}
            WeightRatio(id(j,"foodId"),id(j,"referenceFoodId"),fraction.toDouble())
        }.also(::validate)
    }
    fun round(quantities:Map<Long,Double>,rules:List<WeightRatio>,inputs:List<WeightRatioInput>,
              locks:Map<Long,Double> = emptyMap(),enforceMaximum:Boolean=true):WeightRatioPreview {
        validate(rules)
        require(inputs.map {it.id}.distinct().size==inputs.size) {"比例食品编号重复"}
        require(quantities.values.all {it.isFinite() && it>0}) {"比例数量须为大于0的有效数值"}
        val byId=inputs.associateBy {it.id};val byRule=rules.associateBy {it.foodId}
        val result=quantities.toMutableMap();val done=mutableSetOf<Long>();val deviations=mutableListOf<WeightRatioDeviation>()
        fun mass(id:Long):WeightRatioInput {
            val input=requireNotNull(byId[id]) {"比例食品不存在或已删除"}
            require(input.gramsPerUnit!=null && input.gramsPerUnit.isFinite() && input.gramsPerUnit>0) {"${input.name}按份记录，请先填写每份克重"}
            require(input.step.isFinite() && input.step>0 && input.maximum.isFinite() && input.maximum>=input.step) {"${input.name}的调整单位无效"}
            return input
        }
        fun visit(id:Long) {
            if(!done.add(id)) return
            val rule=byRule[id] ?: return
            visit(rule.referenceFoodId)
            val reference=result[rule.referenceFoodId]
            if(reference==null && result[id]==null) return
            require(reference!=null) {"比例参考食品未选择，不能单独添加依赖食品"}
            val ref=mass(rule.referenceFoodId);val food=mass(id)
            val target=reference*ref.gramsPerUnit!!*rule.fraction
            val native=target/food.gramsPerUnit!!
            require(target.isFinite() && target>0 && native.isFinite()) {"重量比例超出计算范围"}
            val rounded=floor(native/food.step+0.5)*food.step
            require(rounded.isFinite() && rounded>0) {"${food.name}按当前步长将取整为0，请调整参考量或减小步长"}
            require(!enforceMaximum || QuotaMath.within(rounded,food.maximum)) {"${food.name}按比例取整后超过最大推荐量"}
            locks[id]?.let {require(close(it,rounded)) {"${food.name}锁定量与重量比例取整结果冲突"}}
            // A tolerance may accept a representational rounding difference, but an exact lock
            // must still be returned byte-for-byte as supplied by the caller.
            val actual=locks[id]?:rounded
            result[id]=actual
            val deviation=WeightRatioDeviation(id,rule.referenceFoodId,rule.fraction,target,actual*food.gramsPerUnit)
            require(deviation.actualGrams.isFinite() && deviation.deltaGrams.isFinite() && deviation.percent.isFinite()) {"重量比例偏差超出显示范围，请调整比例或参考数量"}
            deviations+=deviation
        }
        rules.forEach {visit(it.foodId)}
        require(locks.all {(id,q)->result[id]?.let {close(it,q)}==true}) {"重量比例不能改变必吃锁定量"}
        return WeightRatioPreview(result,deviations)
    }
    fun deviations(quantities:Map<Long,Double>,rules:List<WeightRatio>,inputs:List<WeightRatioInput>,enforceMaximum:Boolean=true):List<WeightRatioDeviation> {
        val preview=round(quantities,rules,inputs,enforceMaximum=enforceMaximum)
        require(preview.quantities.keys==quantities.keys && quantities.all {(id,q)->close(q,preview.quantities.getValue(id))}) {"实际数量与重量比例取整结果不一致，请重新预览并确认"}
        return preview.deviations
    }
    /** Reports actual manual quantities, without changing them or requiring a recommended ratio. */
    fun describeActual(quantities:Map<Long,Double>,rules:List<WeightRatio>,inputs:List<WeightRatioInput>):List<WeightRatioDeviation> {
        validate(rules)
        require(quantities.values.all {it.isFinite() && it>0}) {"实际数量须为大于0的有效数值"}
        val byId=inputs.associateBy {it.id}
        fun grams(id:Long):Double {
            val mass=requireNotNull(byId[id]?.gramsPerUnit) {"食品缺少每份克重，无法显示实际重量偏差"}
            require(mass.isFinite() && mass>0)
            return quantities.getValue(id)*mass
        }
        return rules.filter {it.foodId in quantities && it.referenceFoodId in quantities}.map {
            val deviation=WeightRatioDeviation(it.foodId,it.referenceFoodId,it.fraction,grams(it.referenceFoodId)*it.fraction,grams(it.foodId))
            require(deviation.targetGrams.isFinite() && deviation.targetGrams>0 && deviation.actualGrams.isFinite() && deviation.percent.isFinite()) {"实际重量偏差超出显示范围"}
            deviation
        }
    }
    private fun close(a:Double,b:Double)=abs(a-b)<=maxOf(1e-7,maxOf(abs(a),abs(b))*1e-8)
}
