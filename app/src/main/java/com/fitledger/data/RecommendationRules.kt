package com.fitledger.data

import com.fitledger.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.ceil

/** User labels, not a fixed equipment enum. An empty food list means unclassified. */
object PreparationTags {
    fun parseInput(text:String):String {
        val tags=text.replace('，',',').replace('\n',',').replace('\r',',').split(',').map {it.trim()}.filter {it.isNotEmpty()}
        require(tags.distinct().size==tags.size) {"准备条件不能重复"}
        return tags.joinToString(",").also(::decode)
    }
    fun decode(encoded:String):List<String> {
        if(encoded.isEmpty()) return emptyList()
        require(encoded.length<=1000) {"准备条件过长"}
        return encoded.split(',').also {tags->
            require(tags.size<=20 && tags.distinct().size==tags.size && tags.all {it.isNotBlank() && it==it.trim() && it.length<=40 && it.none {c->c.code<32 || c=='，'}}) {"准备条件需为不重复、每项40字以内的名称"}
        }
    }
}

data class FoodCycleRule(val foodId:Long,val periodDays:Int=7,val maximumDays:Int?=null,val maximumQuantity:Double?=null) {
    fun validate() {
        require(foodId>0 && periodDays in 1..366) {"食品周期须为1至366天"}
        require(maximumDays!=null || maximumQuantity!=null) {"周期至少填写出现天数或累计数量上限"}
        require(maximumDays==null || maximumDays in 0..periodDays) {"周期出现天数须在0至周期天数内"}
        require(maximumQuantity==null || maximumQuantity.isFinite() && maximumQuantity>=0) {"周期累计数量须为不小于0的有效数量"}
    }
}
data class ConditionalFoodRule(val id:String,val triggerKind:String,val triggerId:Long,val kind:String,val zoneId:Long) {
    fun validate() {
        require(id.isNotBlank() && id.length<=100 && id.none {it.code<32} && triggerId>0 && zoneId>0 && triggerKind in setOf("FOOD","ZONE") && kind in setOf("REQUIRE_ZONE","EXCLUDE_ZONE")) {"条件搭配规则无效"}
        require(triggerKind!="ZONE" || triggerId!=zoneId || kind!="EXCLUDE_ZONE") {"分区不能排斥自己"}
    }
}
data class RecommendationRules(val cycleRules:List<FoodCycleRule> = emptyList(),val conditions:List<ConditionalFoodRule> = emptyList(),
    val availablePreparations:Set<String> = emptySet()) {
    val enabled get()=cycleRules.isNotEmpty() || conditions.isNotEmpty() || availablePreparations.isNotEmpty()
    fun validate() {
        require(cycleRules.size<=1000 && cycleRules.map {it.foodId}.distinct().size==cycleRules.size) {"每种食品只能设置一个周期，最多1000项"}
        require(conditions.size<=50 && conditions.map {it.id}.distinct().size==conditions.size) {"条件搭配最多50条，编号不能重复"}
        require(availablePreparations.size<=20) {"准备条件最多20项"}
        cycleRules.forEach {it.validate()};conditions.forEach {it.validate()}
        availablePreparations.forEach {require(PreparationTags.decode(it)==listOf(it)) {"准备条件名称无效"}}
    }
}

private object RuleJson {
    fun fields(j:JSONObject,names:Set<String>) {require(j.keys().asSequence().toSet()==names) {"推荐规则字段不完整或包含未知字段"}}
    fun id(j:JSONObject,key:String):Long {
        val raw=j.get(key);require(raw is Number) {"规则编号格式无效"}
        return requireNotNull(runCatching {raw.toString().toBigDecimal().longValueExact()}.getOrNull()) {"规则编号须为整数"}
    }
    fun integer(j:JSONObject,key:String):Int {val n=id(j,key);require(n in Int.MIN_VALUE..Int.MAX_VALUE) {"规则整数超出范围"};return n.toInt()}
    fun text(j:JSONObject,key:String):String {val raw=j.get(key);require(raw is String) {"规则文字格式无效"};return raw}
    fun number(j:JSONObject,key:String):Double {val raw=j.get(key);require(raw is Number && raw.toDouble().isFinite()) {"规则数量格式无效"};return raw.toDouble()}
}
object RecommendationRulesCodec {
    fun encode(r:RecommendationRules):JSONObject {
        r.validate()
        return JSONObject().put("cycleRules",JSONArray(r.cycleRules.map {JSONObject().put("foodId",it.foodId).put("periodDays",it.periodDays)
            .put("maximumDays",it.maximumDays?:JSONObject.NULL).put("maximumQuantity",it.maximumQuantity?:JSONObject.NULL)}))
            .put("conditions",JSONArray(r.conditions.map {JSONObject().put("id",it.id).put("triggerKind",it.triggerKind).put("triggerId",it.triggerId).put("kind",it.kind).put("zoneId",it.zoneId)}))
            .put("availablePreparations",JSONArray(r.availablePreparations.sorted()))
    }
    fun decode(j:JSONObject):RecommendationRules {
        RuleJson.fields(j,setOf("cycleRules","conditions","availablePreparations"))
        val cycles=j.getJSONArray("cycleRules");val conditions=j.getJSONArray("conditions");val tags=j.getJSONArray("availablePreparations")
        require(cycles.length()<=1000 && conditions.length()<=50 && tags.length()<=20) {"推荐规则数量过多"}
        val labels=(0 until tags.length()).map {i->val t=tags.get(i);require(t is String) {"准备条件格式无效"};t}
        require(labels.distinct().size==labels.size) {"准备条件不能重复"}
        return RecommendationRules((0 until cycles.length()).map {i->val c=cycles.getJSONObject(i)
            RuleJson.fields(c,setOf("foodId","periodDays","maximumDays","maximumQuantity"))
            FoodCycleRule(RuleJson.id(c,"foodId"),RuleJson.integer(c,"periodDays"),if(c.isNull("maximumDays"))null else RuleJson.integer(c,"maximumDays"),if(c.isNull("maximumQuantity"))null else RuleJson.number(c,"maximumQuantity"))},
            (0 until conditions.length()).map {i->val c=conditions.getJSONObject(i)
                RuleJson.fields(c,setOf("id","triggerKind","triggerId","kind","zoneId"))
                ConditionalFoodRule(RuleJson.text(c,"id"),RuleJson.text(c,"triggerKind"),RuleJson.id(c,"triggerId"),RuleJson.text(c,"kind"),RuleJson.id(c,"zoneId"))},labels.toSet()).also {it.validate()}
    }
}

data class RecommendationProfile(val id:String,val name:String,val policy:RecommendationPolicy,val extras:RecommendationExtras) {
    fun validate() {
        require(id.isNotBlank() && id.length<=100 && id.none {it.code<32}) {"推荐档案编号无效"}
        require(name.isNotBlank() && name==name.trim() && name.length<=60 && name.none {it.code<32}) {"推荐档案名称须为60字以内的非空文字"}
        policy.validate();extras.validate()
    }
}
object RecommendationProfilesCodec {
    fun encode(profiles:List<RecommendationProfile>):JSONArray {
        require(profiles.size<=50 && profiles.map {it.id}.distinct().size==profiles.size) {"推荐档案最多50套，编号不能重复"}
        profiles.forEach {it.validate()}
        return JSONArray(profiles.map {JSONObject().put("id",it.id).put("name",it.name).put("policy",RecommendationPolicyCodec.encode(it.policy)).put("extras",RecommendationExtrasCodec.encode(it.extras))})
    }
    fun decode(array:JSONArray):List<RecommendationProfile> {
        require(array.length()<=50) {"推荐档案过多"}
        return (0 until array.length()).map {i->val j=array.getJSONObject(i);RuleJson.fields(j,setOf("id","name","policy","extras"))
            RecommendationProfile(RuleJson.text(j,"id"),RuleJson.text(j,"name"),RecommendationPolicyCodec.decode(j.getJSONObject("policy")),RecommendationExtrasCodec.decode(j.getJSONObject("extras")))}
            .also {encode(it)}
    }
}

data class FoodCycleSummary(val foodId:Long,val anchorDay:Long?,val startDay:Long,val endDay:Long,val usedDays:Int,
    val usedQuantity:Double,val remainingDays:Int?,val remainingQuantity:Double?)
/** Anchors derive from actual saved consumption, never a recommendation or an unconsumed preset. */
object FoodCycles {
    fun summary(day:Long,food:Food,intakes:List<Intake>,rule:FoodCycleRule):FoodCycleSummary {
        LocalDate.ofEpochDay(day);rule.validate();require(rule.foodId==food.id)
        val actual=intakes.filter {it.foodId==food.id && it.day<=day}
        val anchor=actual.minOfOrNull {it.day}
        val start=anchor?.let {it+Math.floorDiv(day-it,rule.periodDays.toLong())*rule.periodDays}?:day
        val end=start+rule.periodDays-1
        val entries=actual.filter {it.day in start..end}
        require(rule.maximumQuantity==null || entries.all {it.unit==food.unit}) {"${food.name}在本周期有不同计量单位的旧记录，不能推算累计数量；请改用出现天数或核对规则"}
        val quantity=if(entries.all {it.unit==food.unit})entries.sumOf {it.quantity} else 0.0
        require(quantity.isFinite() && quantity>=0 && entries.all {it.quantity.isFinite() && it.quantity>0}) {"周期摄入数量无效或超出计算范围"}
        val days=entries.map {it.day}.distinct().size
        return FoodCycleSummary(food.id,anchor,start,end,days,quantity,rule.maximumDays?.let {(it-days).coerceAtLeast(0)},rule.maximumQuantity?.let {(it-quantity).coerceAtLeast(0.0)})
    }
}

/** Fresh read-only hard constraints. All actual intake remains editable by the manual paths. */
class RecommendationRuleContext(val day:Long,val foods:List<Food>,val zones:List<FoodZone>,val intakes:List<Intake>,val rules:RecommendationRules) {
    private val byId=foods.associateBy {it.id}
    private val today=intakes.filter {it.day==day && it.foodId!=null}
    private val todayIds=today.mapNotNull {it.foodId}.toSet()
    private val cycleById=rules.cycleRules.associateBy {it.foodId}
    private val summaries:Map<Long,FoodCycleSummary>
    init {
        rules.validate();validateReferences(foods,zones,rules)
        summaries=rules.cycleRules.associate {rule->rule.foodId to FoodCycles.summary(day,byId.getValue(rule.foodId),intakes,rule)}
    }
    fun candidates(original:List<Candidate>):List<Candidate> = original.map {c->
        val f=byId.getValue(c.id);val summary=summaries[c.id];val rule=cycleById[c.id]
        val maximum=minOf(c.maximum,summary?.remainingQuantity?:c.maximum)
        val dayAllowed=rule?.maximumDays==null || c.id in todayIds || summary!!.remainingDays!!>0
        val prepAllowed=rules.availablePreparations.isEmpty() || PreparationTags.decode(f.preparationTags).any {it in rules.availablePreparations}
        c.copy(maximum=maximum.coerceAtLeast(c.step),allowed=c.allowed && maximum+1e-7>=maxOf(c.step,f.minimum) && dayAllowed && prepAllowed)
    }
    fun minimum(id:Long,candidate:Candidate):Double=ceil(maxOf(candidate.step,byId.getValue(id).minimum)/candidate.step-1e-9)*candidate.step
    fun repair(q:MutableMap<Long,Double>,candidates:List<Candidate>,locks:Map<Long,Double>) {
        val byCandidate=candidates.associateBy {it.id}
        q.keys.toList().forEach {id->
            val c=requireNotNull(byCandidate[id]);require(c.allowed) {"${byId[id]?.name?:id}不符合准备条件或周期上限"}
            if(q.getValue(id)+1e-7<byId.getValue(id).minimum) {
                require(id !in locks) {"必吃锁定量低于食品最小推荐量"};q[id]=minimum(id,c)
            }
        }
        repeat(rules.conditions.size+1) {
            var changed=false
            rules.conditions.filter {it.kind=="REQUIRE_ZONE"}.forEach {rule->if(triggered(rule,q) && !hasZone(rule.zoneId,q)) {
                val candidate=candidates.filter {it.allowed && byId[it.id]?.zoneId==rule.zoneId}.sortedWith(compareBy<Candidate> {it.pricePerUnitYuan==null}.thenBy {it.id})
                    .firstOrNull {c->val amount=minimum(c.id,c);amount<=c.maximum+1e-7 && c.id !in locks}
                require(candidate!=null) {"搭配条件所需分区没有符合准备条件、周期或数量限制的食品"}
                q[candidate.id]=minimum(candidate.id,candidate);changed=true
            }}
            if(!changed) return
        }
        require(rules.conditions.none {it.kind=="REQUIRE_ZONE" && triggered(it,q) && !hasZone(it.zoneId,q)}) {"搭配条件无法完成"}
    }
    private fun actualIds(q:Map<Long,Double>)=todayIds+q.filterValues {it>0}.keys
    private fun hasZone(zone:Long,q:Map<Long,Double>)=actualIds(q).any {byId[it]?.zoneId==zone}
    private fun triggered(rule:ConditionalFoodRule,q:Map<Long,Double>)=if(rule.triggerKind=="FOOD")rule.triggerId in actualIds(q) else hasZone(rule.triggerId,q)
    fun validate(q:Map<Long,Double>) {
        q.forEach {(id,quantity)->
            val food=requireNotNull(byId[id]);require(quantity+1e-7>=food.minimum) {"${food.name}低于最小推荐量"}
            val rule=cycleById[id]
            if(rule!=null) {
                val summary=summaries.getValue(id)
                require(rule.maximumQuantity==null || QuotaMath.within(summary.usedQuantity+quantity,rule.maximumQuantity)) {"${food.name}超过本周期累计数量上限"}
                require(rule.maximumDays==null || summary.usedDays+(if(id in todayIds)0 else 1)<=rule.maximumDays) {"${food.name}超过本周期出现天数上限"}
            }
            require(rules.availablePreparations.isEmpty() || PreparationTags.decode(food.preparationTags).any {it in rules.availablePreparations}) {"${food.name}不符合当前准备条件"}
        }
        rules.conditions.forEach {r->if(triggered(r,q)) {
            require(if(r.kind=="REQUIRE_ZONE")hasZone(r.zoneId,q) else !hasZone(r.zoneId,q)) {if(r.kind=="REQUIRE_ZONE") "搭配条件要求包含指定分区" else "搭配条件禁止同时包含指定分区"}
        }}
    }
    companion object {
        fun validateReferences(foods:List<Food>,zones:List<FoodZone>?,rules:RecommendationRules) {
            rules.validate();val byId=foods.associateBy {it.id};val zoneIds=zones?.map {it.id}?.toSet()
            rules.cycleRules.forEach {rule->val food=requireNotNull(byId[rule.foodId]) {"周期食品引用不存在"}
                require(rule.maximumQuantity==null || food.unit!="份" || rule.maximumQuantity==kotlin.math.floor(rule.maximumQuantity)) {"按份食品的周期累计上限须为整数"}}
            rules.conditions.forEach {r->
                require(r.triggerKind!="FOOD" || r.triggerId in byId) {"条件食品引用不存在"}
                require(zoneIds==null || r.zoneId in zoneIds && (r.triggerKind!="ZONE" || r.triggerId in zoneIds)) {"条件分区引用不存在"}
            }
        }
    }
}
