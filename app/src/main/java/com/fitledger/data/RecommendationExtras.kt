package com.fitledger.data

import com.fitledger.domain.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

data class RecommendationExtras(val weeklyBudgetYuan:Double?=null,val shareWeekRemainder:Boolean=false,
    val varietyDays:Int=0,val comboIds:Set<Long> = emptySet(),val ratios:List<WeightRatio> = emptyList(),
    val rules:RecommendationRules=RecommendationRules()) {
    val enabled get()=weeklyBudgetYuan!=null || varietyDays>0 || comboIds.isNotEmpty() || ratios.isNotEmpty() || rules.enabled
    fun validate() {
        require(weeklyBudgetYuan==null || weeklyBudgetYuan.isFinite() && weeklyBudgetYuan>=0) {"每周预算须为不小于0的有效金额"}
        require(varietyDays in 0..90 && comboIds.size<=8 && comboIds.all {it>0}) {"轮换天数须为0～90天，参与推荐组合最多8套"}
        WeightRatios.validate(ratios)
        rules.validate()
    }
}
object RecommendationExtrasCodec {
    fun encode(value:RecommendationExtras):JSONObject {
        value.validate()
        return JSONObject().put("weeklyBudgetYuan",value.weeklyBudgetYuan?:JSONObject.NULL).put("shareWeekRemainder",value.shareWeekRemainder)
            .put("varietyDays",value.varietyDays).put("comboIds",JSONArray(value.comboIds.sorted())).put("ratios",WeightRatios.encode(value.ratios))
            .put("rules",RecommendationRulesCodec.encode(value.rules))
    }
    fun decode(j:JSONObject):RecommendationExtras {
        val keys=j.keys().asSequence().toSet();val legacy=setOf("weeklyBudgetYuan","shareWeekRemainder","varietyDays","comboIds","ratios")
        require(keys==legacy || keys==legacy+"rules") {"推荐扩展设置字段不完整"}
        val budget=j.get("weeklyBudgetYuan");require(budget==JSONObject.NULL || budget is Number) {"每周预算格式无效"}
        val share=j.get("shareWeekRemainder");val days=j.get("varietyDays")
        require(share is Boolean && days is Number && days.toDouble()==days.toInt().toDouble()) {"推荐扩展设置格式无效"}
        val array=j.getJSONArray("comboIds");require(array.length()<=8) {"参与推荐组合过多"}
        val ids=(0 until array.length()).map {i->val n=array.get(i);require(n is Number) {"组合编号无效"}
            requireNotNull(runCatching {n.toString().toBigDecimal().longValueExact()}.getOrNull()) {"组合编号须为整数"}}
        require(ids.distinct().size==ids.size && j.get("ratios") is String) {"组合编号重复或比例设置无效"}
        return RecommendationExtras(if(budget==JSONObject.NULL)null else (budget as Number).toDouble(),share,days.toInt(),ids.toSet(),WeightRatios.decode(j.getString("ratios")),
            if(j.has("rules"))RecommendationRulesCodec.decode(j.getJSONObject("rules")) else RecommendationRules()).also {it.validate()}
    }
}
fun foodRatioInputs(foods:List<Food>)=foods.map {WeightRatioInput(it.id,it.name,if(it.unit=="g")1.0 else it.gramsPerPortion,it.candidate().step,it.maximum)}
data class WeeklyBudgetSummary(val startDay:Long,val endDay:Long,val budgetYuan:Double,val knownCostYuan:Double,
    val unknownPriceCount:Int,val remainingYuan:Double?,val currentRecommendationAllowance:Double?)

/** Optional extras wrap the legacy engine. No default plan or historical record is rewritten. */
object ExtendedRecommendations {
    private const val MAX_PROJECTS=1200
    /** Validates references without requiring a body record, target or actual intake. */
    fun validateConfiguration(foods:List<Food>,combos:List<FoodCombo>,extras:RecommendationExtras) {
        extras.validate()
        RecommendationRuleContext.validateReferences(foods,null,extras.rules)
        require(foods.map {it.id}.distinct().size==foods.size && combos.map {it.id}.distinct().size==combos.size) {"食品或组合编号重复"}
        val byId=foods.filterNot {it.isDeleted}.associateBy {it.id}
        fun available(id:Long):Food=requireNotNull(byId[id]) {"比例或组合引用的食品不存在或已删除"}.also {
            it.validate();require(it.allowed) {"${it.name}未允许参与智能推荐"}
        }
        val selected=extras.comboIds.map {id->requireNotNull(combos.find {it.id==id}) {"参与推荐的组合已删除，请重新选择"}}
        val used=mutableSetOf<Long>()
        selected.forEach {combo->
            combo.validate()
            ComboCodec.decode(combo.items).forEach {item->
                require(used.add(item.foodId)) {"参与推荐的组合不能重复包含同一种食品，请只保留其中一套"}
                require(available(item.foodId).unit==item.unit) {"组合食品单位已经变化，请先编辑组合"}
            }
        }
        val rules=mergeRules(extras.ratios+selected.flatMap {WeightRatios.decode(it.ratioRules)})
        rules.flatMap {listOf(it.foodId,it.referenceFoodId)}.distinct().forEach {id->
            val food=available(id)
            require(food.unit=="g" || food.gramsPerPortion!=null) {"${food.name}按份记录，请先填写每份克重"}
        }
    }
    fun weeklyBudget(day:Long,intakes:List<Intake>,extras:RecommendationExtras):WeeklyBudgetSummary? {
        extras.validate();val budget=extras.weeklyBudgetYuan?:return null
        val date=LocalDate.ofEpochDay(day);val start=day-(date.dayOfWeek.value-1);val end=start+6
        val week=intakes.filter {it.day in start..end}
        val known=week.sumOf {it.costYuan?:0.0};require(known.isFinite() && week.all {it.costYuan==null || it.costYuan.isFinite() && it.costYuan>=0}) {"本周摄入费用无效或超出计算范围"}
        val unknown=week.count {it.costYuan==null};val left=if(unknown==0)(budget-known).coerceAtLeast(0.0) else null
        return WeeklyBudgetSummary(start,end,budget,known,unknown,left,left?.let {if(extras.shareWeekRemainder)it/(end-day+1) else it})
    }
    fun effectivePolicy(day:Long,intakes:List<Intake>,policy:RecommendationPolicy,extras:RecommendationExtras):RecommendationPolicy {
        extras.validate();policy.validate();val summary=weeklyBudget(day,intakes,extras)?:return policy
        require(summary.unknownPriceCount==0) {"本周有${summary.unknownPriceCount}条未计价摄入，无法验证周预算；请先补全费用或明确取消周预算"}
        require(QuotaMath.within(summary.knownCostYuan,summary.budgetYuan)) {"本周已记录费用超过周预算，请先明确调整周预算后再推荐"}
        val today=intakes.filter {it.day==day};require(today.none {it.costYuan==null}) {"今日存在未计价摄入，无法验证预算"}
        val spent=today.sumOf {it.costYuan!!};val maximum=spent+summary.currentRecommendationAllowance!!
        require(maximum.isFinite()) {"周预算超出计算范围"}
        return policy.copy(dailyBudgetYuan=policy.dailyBudgetYuan?.let {minOf(it,maximum)}?:maximum)
    }
    fun recommend(day:Long,foods:List<Food>,zones:List<FoodZone>,intakes:List<Intake>,combos:List<FoodCombo>,
                  target:Nutrition,locks:Map<Long,Double>,policy:RecommendationPolicy,extras:RecommendationExtras,preferValue:Boolean=false):Suggestion {
        val consumed=IntakeNutritionTotals.requireKnown(intakes.filter {it.day==day})
        val spent=spent(day,intakes)
        if(!usesExtended(foods,extras)) return Recommender.recommend(FoodZoneQuotas.candidates(foods,zones),target-consumed,locks,FoodZoneQuotas.limits(zones,foods,intakes,day),policy,spent,preferValue)
        val batch=generate(day,foods,zones,intakes,combos,target,locks,policy,extras,limit=50)
        val priceScale=foods.filter {it.allowed && !it.isDeleted}.mapNotNull {f->f.pricePerUnitYuan?.times(f.step)?.takeIf {it>0 && it.isFinite()}}.minOrNull()?.coerceAtLeast(1.0)?:1.0
        return batch.options.filter {it.formalPolicySatisfied && (!preferValue || it.suggestion.costYuan!=null)}.minByOrNull {option->
            quality(option.suggestion.nutrition,target-consumed)+varietyPenalty(day,intakes,extras,option.suggestion.quantities)+
                (if(preferValue)option.suggestion.costYuan!!.let {cost->0.02*(if(cost<=priceScale)cost/(cost+priceScale) else 1.0/(1.0+priceScale/cost))} else 0.0)
        }?.suggestion ?: throw IllegalArgumentException("在当前周期上限、搭配、准备条件、最小量、预算及营养限制下，有限搜索未找到可采用方案；请检查食品、价格和条件")
    }
    fun generate(day:Long,foods:List<Food>,zones:List<FoodZone>,intakes:List<Intake>,combos:List<FoodCombo>,
                 target:Nutrition,locks:Map<Long,Double>,policy:RecommendationPolicy,extras:RecommendationExtras,
                 existing:List<ComparisonOption> = emptyList(),limit:Int=10,seed:Int=0):ComparisonBatch {
        extras.validate();require(limit in 1..50 && existing.size<=50)
        val eaten=IntakeNutritionTotals.requireKnown(intakes.filter {it.day==day});val spent=spent(day,intakes)
        val actualPolicy=effectivePolicy(day,intakes,policy,extras)
        val ruleContext=RecommendationRuleContext(day,foods,zones,intakes,extras.rules)
        val candidates=ruleContext.candidates(FoodZoneQuotas.candidates(foods,zones));val limits=FoodZoneQuotas.limits(zones,foods,intakes,day)
        if(!usesExtended(foods,extras)) return RecommendationComparison.generate(candidates,target,eaten,locks,limits,policy,spent,existing,limit,seed)
        val context=Structure(foods,combos,extras,candidates,target-eaten,limits,locks,ruleContext)
        val original=RecommendationComparison.generate(candidates,target,eaten,locks,limits,actualPolicy,spent,limit=50,seed=seed)
        val pool=linkedMapOf<String,Map<Long,Double>>()
        // Up to50 existing rows are revalidated separately so a new-search deadline cannot
        // drop a previously displayed row merely because the library is large.
        existing.forEach {option->
            val q=option.suggestion.quantities
            if(runCatching {context.validate(q)}.isSuccess) pool[key(q)]=q
        }
        val deadline=System.nanoTime()+900_000_000L
        var projections=0
        fun offer(q:Map<Long,Double>) {
            if(projections>=MAX_PROJECTS || System.nanoTime()>=deadline) return
            projections++
            runCatching {context.project(q)}.getOrNull()?.let {pool[key(it)]=it}
        }
        original.options.forEach {offer(it.suggestion.quantities)}
        candidates.filter {it.allowed}.forEach {c->
            val minimum=ruleContext.minimum(c.id,c)
            offer(locks+(c.id to minimum))
        }
        // Required zones receive direct opportunities even when no old top-50 row used them.
        extras.rules.conditions.filter {it.kind=="REQUIRE_ZONE"}.forEach {condition->
            val triggers=if(condition.triggerKind=="FOOD")candidates.filter {it.id==condition.triggerId && it.allowed}
                else candidates.filter {it.allowed && foods.find {f->f.id==it.id}?.zoneId==condition.triggerId}.take(4)
            val required=candidates.filter {it.allowed && foods.find {f->f.id==it.id}?.zoneId==condition.zoneId}.take(6)
            triggers.forEach {a->required.forEach {b->offer(locks+mapOf(a.id to ruleContext.minimum(a.id,a),b.id to ruleContext.minimum(b.id,b)))}}
        }
        context.bundles.forEach {bundle->
            val useful=bundle.variants.sortedBy {quality(context.nutrition(it),target-eaten)}.take(8)
            useful.forEach {variant->offer(locks+variant);original.options.take(12).forEach {offer(it.suggestion.quantities+variant)}}
        }
        // Isolated ratio pairs receive an opportunity even when the old pool never selected the reference.
        extras.ratios.forEach {rule->context.byId[rule.referenceFoodId]?.let {food->
            val count=floor(food.maximum/food.step).toInt();listOf(1,count/4,count/2,count).filter {it>0}.distinct().forEach {n->offer(locks+(food.id to food.step*n))}
        }}
        val known=existing.map {key(it.suggestion.quantities)}.toSet()
        val previous=existing.mapNotNull {pool[key(it.suggestion.quantities)]}.distinctBy(::key)
        val fresh=pool.values.filter {key(it) !in known}.sortedBy {quality(context.nutrition(it),target-eaten)+varietyPenalty(day,intakes,extras,it)}
        val wanted=minOf(limit,50-previous.size)
        val chosen=previous+fresh.take(wanted)
        val options=chosen.map {q->evaluate(candidates,limits,target,eaten,spent,actualPolicy,policy,q,day,intakes,extras)}
        val best=ComparisonColumn.entries.associateWith {column->options.mapNotNull {rank(it,column)}.minOrNull()}
        val ranked=options.map {o->o.copy(bestColumns=ComparisonColumn.entries.filter {column->val r=rank(o,column);val b=best[column];r!=null && b!=null && abs(r-b)<=maxOf(1e-7,abs(b)*1e-8)}.toSet())}
        val more=ranked.size<50 && (fresh.size>wanted || context.optionUpperBound()>ranked.size)
        return ComparisonBatch(ranked,more,
            "本轮保留重量比例取整及整套组合，${ranked.size}套去重方案；按当前有限候选比较，不保证找到全部可行解。${if(more) "可继续换一轮搜索，可能没有新增方案。" else ""}比例偏差请在采用前核对。",policy,target,eaten,spent)
    }
    /** Called again in the caller's transaction with fresh rows immediately before recording. */
    fun validate(day:Long,foods:List<Food>,zones:List<FoodZone>,intakes:List<Intake>,combos:List<FoodCombo>,
                 target:Nutrition,quantities:Map<Long,Double>,policy:RecommendationPolicy,extras:RecommendationExtras,locks:Map<Long,Double> = emptyMap()):List<WeightRatioDeviation> {
        val eaten=IntakeNutritionTotals.requireKnown(intakes.filter {it.day==day})
        val ruleContext=RecommendationRuleContext(day,foods,zones,intakes,extras.rules)
        val candidates=ruleContext.candidates(FoodZoneQuotas.candidates(foods,zones));val limits=FoodZoneQuotas.limits(zones,foods,intakes,day)
        val structure=Structure(foods,combos,extras,candidates,target-eaten,limits,locks,ruleContext)
        structure.validate(quantities)
        Recommender.validateProposal(candidates,target-eaten,quantities,limits,effectivePolicy(day,intakes,policy,extras),spent(day,intakes))
        return deviations(foods,combos,extras,quantities)
    }
    fun deviations(foods:List<Food>,combos:List<FoodCombo>,extras:RecommendationExtras,quantities:Map<Long,Double>):List<WeightRatioDeviation> {
        val activeRules=extras.ratios+combos.filter {it.id in extras.comboIds && ComboCodec.decode(it.items).any {item->item.foodId in quantities}}.flatMap {WeightRatios.decode(it.ratioRules)}
        val rules=mergeRules(activeRules)
        if(rules.isEmpty()) return emptyList()
        val ids=rules.flatMap {listOf(it.foodId,it.referenceFoodId)}.toSet()
        return WeightRatios.deviations(quantities,rules,foodRatioInputs(foods.filter {it.id in ids}))
    }
    private fun spent(day:Long,intakes:List<Intake>):Double?=intakes.filter {it.day==day}.let {if(it.any {e->e.costYuan==null})null else it.sumOf {e->e.costYuan!!}.also {require(it.isFinite())}}
    private fun usesExtended(foods:List<Food>,extras:RecommendationExtras)=extras.enabled || foods.any {!it.isDeleted && it.allowed && it.minimum>0}
    private fun mergeRules(rules:List<WeightRatio>):List<WeightRatio> {
        val grouped=rules.groupBy {it.foodId};require(grouped.values.all {it.distinct().size==1}) {"组合与全局比例规则冲突"}
        return grouped.values.map {it.first()}.also(WeightRatios::validate)
    }
    private fun key(q:Map<Long,Double>)=q.toSortedMap().entries.joinToString(";") {"${it.key}:${java.lang.Double.toHexString(it.value)}"}
    private fun quality(actual:Nutrition,remaining:Nutrition)=actual.values().zip(remaining.values()).mapIndexed {i,(a,b)->val d=(a-b)/max(abs(b),if(i==0)100.0 else 10.0);d*d}.sum()
    private fun varietyPenalty(day:Long,intakes:List<Intake>,extras:RecommendationExtras,q:Map<Long,Double>):Double {
        if(extras.varietyDays==0) return 0.0
        val counts=intakes.filter {it.day in (day-extras.varietyDays) until day && it.foodId!=null}.distinctBy {it.day to it.foodId}.groupingBy {it.foodId}.eachCount()
        return q.keys.sumOf {(counts[it]?:0).toDouble()}*0.025/max(1,q.size)
    }
    private data class Bundle(val id:Long,val items:List<ComboItem>,val rules:List<WeightRatio>,val variants:List<Map<Long,Double>>)
    private class Structure(val foods:List<Food>,combos:List<FoodCombo>,val extras:RecommendationExtras,val candidates:List<Candidate>,
                            val remaining:Nutrition,val limits:List<ZoneLimit>,val locks:Map<Long,Double>,val ruleContext:RecommendationRuleContext) {
        val byId=foods.filter {!it.isDeleted}.associateBy {it.id}
        val bundles:List<Bundle>
        private val ratioInputs:List<WeightRatioInput>
        init {
            validateConfiguration(foods,combos,extras)
            val used=mutableSetOf<Long>()
            bundles=combos.filter {it.id in extras.comboIds}.map {combo->
                combo.validate();val items=ComboCodec.decode(combo.items)
                require(items.all {used.add(it.foodId)}) {"参与推荐的组合不能重复包含同一种食品，请只保留其中一套"}
                items.forEach {item->val food=requireNotNull(byId[item.foodId]) {"组合食品已删除"};require(food.unit==item.unit && candidates.any {it.id==food.id}) {"组合食品单位变化或分区尚未核对"}}
                val temporarilyUnavailable=items.any {item->candidates.none {it.id==item.foodId && it.allowed}}
                val anchor=candidates.first {it.id==items.first().foodId};val base=items.first().quantity
                val rules=WeightRatios.decode(combo.ratioRules)
                val inputs=if(rules.isEmpty())emptyList() else foodRatioInputs(items.map {byId.getValue(it.foodId)})
                val variants=if(temporarilyUnavailable)emptyList() else (1..floor(anchor.maximum/anchor.step).toInt()).mapNotNull {n->
                    val scale=n*anchor.step/base
                    val q=items.associate {it.foodId to it.quantity*scale}
                    if(q.all {(id,amount)->val f=candidates.first {it.id==id};amount.isFinite() && amount>0 && amount+1e-7>=byId.getValue(id).minimum && QuotaMath.within(amount,f.maximum) && abs(amount/f.step-kotlin.math.round(amount/f.step))<0.00001} &&
                        (rules.isEmpty() || runCatching {WeightRatios.deviations(q,rules,inputs)}.isSuccess))q else null
                }
                require(temporarilyUnavailable || variants.isNotEmpty()) {"${combo.name}的整套数量无法满足食品步长、最小量或最大量，请编辑组合基础量"}
                Bundle(combo.id,items,rules,variants)
            }
            val rules=mergeRules(extras.ratios+bundles.flatMap {it.rules})
            val ids=rules.flatMap {listOf(it.foodId,it.referenceFoodId)}.toSet()
            ratioInputs=foodRatioInputs(foods.filter {it.id in ids})
        }
        /** Upper bound only: quotas and ratio dependencies may reduce the real count. */
        fun optionUpperBound():Int {
            var count=1L
            val grouped=bundles.flatMap {it.items}.map {it.foodId}.toSet()
            bundles.forEach {bundle->
                val mandatory=bundle.items.any {it.foodId in locks}
                val compatible=if(mandatory)bundle.variants.count {v->bundle.items.all {item->locks[item.foodId]?.let {abs(v.getValue(item.foodId)-it)<1e-7}!=false}} else bundle.variants.size
                count=(count*(compatible+if(mandatory)0 else 1)).coerceAtMost(51L)
            }
            candidates.filter {it.allowed && it.id !in grouped && it.id !in locks}.forEach {
                count=(count*(floor(it.maximum/it.step).toLong()+1)).coerceAtMost(51L)
            }
            return (count-if(locks.isEmpty())1 else 0).coerceAtLeast(0).toInt()
        }
        fun nutrition(q:Map<Long,Double>)=q.entries.fold(Nutrition()) {sum,(id,amount)->val food=byId.getValue(id);sum+food.nutrition*(amount/food.basisAmount)}
        fun project(original:Map<Long,Double>):Map<Long,Double> {
            val q=original.toMutableMap()
            // Repairs can introduce another bundle or trigger another condition. Iterate to a
            // fixed point, keeping a strict bound and validating every final structural rule.
            repeat(extras.rules.conditions.size+bundles.size+extras.ratios.size+2) {
                val before=q.toMap()
                ruleContext.repair(q,candidates,locks)
                bundles.forEach {bundle->if(bundle.items.any {it.foodId in q || it.foodId in locks}) {
                    val variant=bundle.variants.filter {v->bundle.items.all {item->locks[item.foodId]?.let {abs(v.getValue(item.foodId)-it)<1e-7}!=false}}
                        .minByOrNull {v->bundle.items.sumOf {item->val desired=q[item.foodId]?:0.0;val d=(v.getValue(item.foodId)-desired)/max(item.quantity,1e-6);d*d}}
                    require(variant!=null) {"锁定量与整套组合不兼容"};q.putAll(variant);q.putAll(locks)
                }}
                val rules=mergeRules(extras.ratios+bundles.filter {b->b.items.any {it.foodId in q}}.flatMap {it.rules})
                repeat(rules.size) {rules.forEach {rule->if(rule.foodId in q && rule.referenceFoodId !in q) {
                    val source=byId.getValue(rule.foodId);val ref=byId.getValue(rule.referenceFoodId)
                    val mass=if(source.unit=="g")1.0 else requireNotNull(source.gramsPerPortion)
                    val refMass=if(ref.unit=="g")1.0 else requireNotNull(ref.gramsPerPortion)
                    val native=q.getValue(rule.foodId)*mass/rule.fraction/refMass
                    val step=ref.candidate().step
                    val rounded=floor(native/step+0.5)*step;require(rounded.isFinite() && rounded>0);q[ref.id]=rounded
                }}}
                if(rules.isNotEmpty()) {
                    val rounded=WeightRatios.round(q,rules,ratioInputs,locks).quantities
                    q.clear();q.putAll(rounded)
                }
                if(q==before) {validate(q);return q.toMap()}
            }
            validate(q);return q.toMap()
        }
        fun validate(q:Map<Long,Double>) {
            require(locks.all {(id,amount)->q[id]==amount}) {"不能改变必吃锁定量"}
            Recommender.validateProposal(candidates,remaining,q,limits,RecommendationPolicy(),0.0)
            ruleContext.validate(q)
            bundles.forEach {bundle->if(bundle.items.any {it.foodId in q}) {
                require(bundle.items.all {it.foodId in q} && bundle.variants.any {v->bundle.items.all {abs(v.getValue(it.foodId)-q.getValue(it.foodId))<1e-7}}) {"整套组合不能拆散或分别改变倍率"}
            }}
            val rules=mergeRules(extras.ratios+bundles.filter {b->b.items.any {it.foodId in q}}.flatMap {it.rules})
            if(rules.isNotEmpty()) WeightRatios.deviations(q,rules,ratioInputs)
        }
    }
    private fun evaluate(foods:List<Candidate>,limits:List<ZoneLimit>,target:Nutrition,consumed:Nutrition,spent:Double?,policy:RecommendationPolicy,originalPolicy:RecommendationPolicy,
                         q:Map<Long,Double>,day:Long,intakes:List<Intake>,extras:RecommendationExtras):ComparisonOption {
        val n=q.entries.fold(Nutrition()) {sum,(id,amount)->sum+foods.first {it.id==id}.perUnit*amount}
        val cost=RecommendationSearch.cost(foods,q);val actual=n+consumed
        fun macro(goal:Double,value:Double,tolerance:MacroTolerance):ComparisonMacroMetric {
            val mode=when {tolerance.under==0.0 && tolerance.over==0.0->ComparisonMacroMode.EXACT;tolerance.under==0.0->ComparisonMacroMode.AT_LEAST;tolerance.over==0.0->ComparisonMacroMode.AT_MOST;else->ComparisonMacroMode.CLOSEST}
            val fulfilled=when(mode) {ComparisonMacroMode.AT_LEAST->QuotaMath.within(goal,value);ComparisonMacroMode.AT_MOST->QuotaMath.within(value,goal);else->abs(value-goal)<1e-7}
            val rank=when(mode) {ComparisonMacroMode.AT_LEAST->if(fulfilled)0.0 else max(0.0,goal-value);ComparisonMacroMode.AT_MOST->value;else->abs(value-goal)}
            return ComparisonMacroMetric(goal,value,value-goal,mode,fulfilled,rank)
        }
        val metrics=ComparisonMetrics(cost,if(cost!=null && spent!=null)cost+spent else null,macro(target.carbs,actual.carbs,policy.carbs),macro(target.protein,actual.protein,policy.protein),macro(target.fat,actual.fat,policy.fat),q.size)
        val allowed=runCatching {Recommender.validateProposal(foods,target-consumed,q,limits,policy,spent)}.isSuccess
        val violations=mutableListOf<String>();var adjusted=originalPolicy
        if(policy.dailyBudgetYuan!=null && (metrics.dailyCostYuan==null || !QuotaMath.within(metrics.dailyCostYuan,policy.dailyBudgetYuan))) {
            violations+="全天费用或本周可用额度不满足；周预算需到设置中明确调整"
            if(originalPolicy.dailyBudgetYuan!=null && (metrics.dailyCostYuan==null || !QuotaMath.within(metrics.dailyCostYuan,originalPolicy.dailyBudgetYuan)))
                adjusted=adjusted.copy(dailyBudgetYuan=metrics.dailyCostYuan?.let {Math.nextUp(max(it,originalPolicy.dailyBudgetYuan))})
        }
        fun loosen(goal:Double,value:Double,rule:MacroTolerance,label:String):MacroTolerance {
            var r=rule
            if(rule.under?.let {value<goal-it-1e-7}==true) {violations+="${label}低于允许下限";r=r.copy(under=Math.nextUp(max(0.0,goal-value)))}
            if(rule.over?.let {value>goal+it+1e-7}==true) {violations+="${label}超过允许上限";r=r.copy(over=Math.nextUp(max(0.0,value-goal)))}
            return r
        }
        adjusted=adjusted.copy(carbs=loosen(target.carbs,actual.carbs,policy.carbs,"碳水"),protein=loosen(target.protein,actual.protein,policy.protein,"蛋白质"),fat=loosen(target.fat,actual.fat,policy.fat,"脂肪"))
        return ComparisonOption(key(q),Suggestion(q,n,quality(n,target-consumed)+varietyPenalty(day,intakes,extras,q),cost),allowed,violations,adjusted,metrics)
    }
    private fun rank(option:ComparisonOption,column:ComparisonColumn):Double?=when(column) {
        ComparisonColumn.CURRENT_COST->option.metrics.currentCostYuan;ComparisonColumn.DAILY_COST->option.metrics.dailyCostYuan
        ComparisonColumn.CARBS->option.metrics.carbs.rankValue;ComparisonColumn.PROTEIN->option.metrics.protein.rankValue
        ComparisonColumn.FAT->option.metrics.fat.rankValue;ComparisonColumn.FOOD_COUNT->option.metrics.foodCount.toDouble()
    }
}
