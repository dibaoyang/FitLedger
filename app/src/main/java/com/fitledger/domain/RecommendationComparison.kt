package com.fitledger.domain

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.round
import kotlin.random.Random

enum class ComparisonMacroMode { AT_LEAST, AT_MOST, CLOSEST, EXACT }
enum class ComparisonColumn { CURRENT_COST, DAILY_COST, CARBS, PROTEIN, FAT, FOOD_COUNT }
data class ComparisonMacroMetric(val targetGrams:Double,val actualGrams:Double,val differenceGrams:Double,
    val mode:ComparisonMacroMode,val satisfied:Boolean,val rankValue:Double)
data class ComparisonMetrics(val currentCostYuan:Double?,val dailyCostYuan:Double?,
    val carbs:ComparisonMacroMetric,val protein:ComparisonMacroMetric,val fat:ComparisonMacroMetric,val foodCount:Int)
data class ComparisonOption(val id:String,val suggestion:Suggestion,val formalPolicySatisfied:Boolean,
    val violations:List<String>,val proposedPolicy:RecommendationPolicy,val metrics:ComparisonMetrics,
    val bestColumns:Set<ComparisonColumn> = emptySet())
data class ComparisonBatch(val options:List<ComparisonOption>,val canGenerateMore:Boolean,val note:String,
    val basePolicy:RecommendationPolicy,val target:Nutrition,val consumed:Nutrition,val spentYuan:Double?)

/** A bounded, read-only comparison. Budget/macro drafts never relax structural constraints. */
object RecommendationComparison {
    private const val MAX_OPTIONS=50
    private const val MAX_EVALUATIONS=40000
    private const val MAX_MILLIS=900L
    private const val POOL_LIMIT=768
    private data class Node(val quantities:Map<Long,Double>,val nutrition:Nutrition,val cost:Double?,val key:String)
    private fun stepCount(food:Candidate):Int {
        val ratio=food.maximum/food.step;val nearest=round(ratio)
        return (if(abs(ratio-nearest)<0.00001) nearest else floor(ratio)).toInt()
    }
    private fun amountAt(food:Candidate,steps:Int)=minOf(food.maximum,steps*food.step)

    /** limit is the maximum number of NEW rows; existing rows are rechecked and retained, up to 50. */
    fun generate(foods:List<Candidate>,target:Nutrition,consumed:Nutrition,locks:Map<Long,Double>,
        zoneLimits:List<ZoneLimit>,policy:RecommendationPolicy,spentYuan:Double?,
        existing:List<ComparisonOption> = emptyList(),limit:Int=10,seed:Int=0):ComparisonBatch {
        require(limit in 1..MAX_OPTIONS && existing.size<=MAX_OPTIONS) {"每次生成 1～50 套，累计最多 50 套"}
        require(target.values().all {it.isFinite() && it>=0} && consumed.values().all {it.isFinite() && it>=0}) {"目标与已吃营养须为有效非负值"}
        policy.validate()
        val context=Context(foods,target,consumed,locks,zoneLimits,policy,spentYuan)
        // Check exact locks once, including incompatible locks within an exclusive zone.
        val initial=requireNotNull(context.node(locks)) {"锁定食品不可用，或锁定量不符合步长、最大量、分区份数及同区互斥限制；请修改锁定后重试"}
        val previous=existing.mapNotNull {context.node(it.suggestion.quantities)}.filter {it.quantities.isNotEmpty()}.distinctBy {it.key}
        if(previous.size>=MAX_OPTIONS) return context.batch(previous.take(MAX_OPTIONS),false,"已达到本轮 50 套上限。")
        val wanted=minOf(limit,MAX_OPTIONS-previous.size)
        val random=Random(seed)
        val deadline=System.nanoTime()+MAX_MILLIS*1_000_000
        var evaluations=0
        val pool=linkedMapOf<String,Node>()
        val previousKeys=previous.map {it.key}.toSet()
        fun running()=evaluations<MAX_EVALUATIONS && System.nanoTime()<deadline
        fun offer(node:Node?) {
            if(node==null || node.quantities.isEmpty() || node.key in previousKeys) return
            pool[node.key]=node
            if(pool.size>POOL_LIMIT) {
                val keep=context.diverse(pool.values.toList(),POOL_LIMIT/2,random)
                pool.clear();keep.forEach {pool[it.key]=it}
            }
        }
        fun evaluate(amounts:Map<Long,Double>):Node? {
            if(!running()) return null
            evaluations++
            return context.node(amounts).also(::offer)
        }
        offer(initial)
        val unlocked=context.eligible.filter {it.id !in locks}.sortedBy {it.id}
        var space=1L
        for(food in unlocked) {
            space=(space*(stepCount(food).toLong()+1)).coerceAtMost(4097L)
            if(space>4096) break
        }
        if(space<=4096) {
            var complete=true
            fun enumerate(index:Int,amounts:Map<Long,Double>) {
                if(!running()) {complete=false;return}
                if(index==unlocked.size) {evaluate(amounts);return}
                val food=unlocked[index]
                for(steps in 0..stepCount(food)) {
                    if(!running()) {complete=false;return}
                    enumerate(index+1,if(steps==0) amounts else amounts+(food.id to amountAt(food,steps)))
                }
            }
            enumerate(0,locks)
            if(complete) {
                val selected=context.diverse(pool.values.toList(),wanted,random)
                val all=previous+selected
                val more=all.size<MAX_OPTIONS && pool.size>selected.size
                val note=if(all.isEmpty()) "当前食品与硬限制下没有非空方案；如今日目标已完成，可以不再新增食品。"
                    else if(all.size>=MAX_OPTIONS) "已达到本轮 50 套上限；最优标记只比较表内方案。"
                    else "本次新增 ${selected.size} 套；${if(more) "仍有未展示的去重方案，可继续生成。" else "当前可用的非空方案均已展示，不凑数。"}"
                return context.batch(all,more,note)
            }
        }
        // Give each food a direct chance before a long beam prefix can consume the shared budget.
        for(food in unlocked) {
            if(!running()) break
            for(quantity in context.amounts(food,initial,random)) {
                if(!running()) break
                evaluate(context.replace(initial,food,quantity))
            }
        }
        val useful=unlocked.sortedByDescending {f->
            f.perUnit.values().zip(context.remaining.values()).sumOf {(n,goal)->if(goal>0) minOf(1.0,n*f.maximum/max(10.0,goal)) else 0.0}
        }
        val orders=listOf(useful,unlocked.shuffled(random),unlocked.reversed()).distinctBy {order->order.map {it.id}}
        for(order in orders) {
            if(!running()) break
            var beam=listOf(initial)
            for(food in order) {
                if(!running()) break
                val expanded=linkedMapOf<String,Node>()
                for(base in beam) {
                    if(!running()) break
                    for(quantity in context.amounts(food,base,random)) {
                        if(!running()) break
                        evaluate(context.replace(base,food,quantity))?.let {expanded[it.key]=it}
                    }
                }
                if(expanded.isEmpty()) break
                beam=context.diverse(expanded.values.toList(),24,random)
            }
        }
        // Discrete local perturbations add alternatives without calling the 1.8s recommender N times.
        var bases=(previous+pool.values).ifEmpty {listOf(initial)}
        while(running() && unlocked.isNotEmpty()) {
            val base=bases[random.nextInt(bases.size)]
            val food=unlocked[random.nextInt(unlocked.size)]
            val oldSteps=round((base.quantities[food.id] ?: 0.0)/food.step).toInt()
            val maximum=stepCount(food)
            val steps=when(random.nextInt(4)) {
                0 -> (oldSteps+if(random.nextBoolean()) 1 else -1).coerceIn(0,maximum)
                1 -> random.nextInt(maximum+1)
                else -> round(context.amounts(food,base,random).random(random)/food.step).toInt()
            }
            evaluate(context.replace(base,food,amountAt(food,steps)))
            if(evaluations%128==0) bases=(previous+pool.values).ifEmpty {listOf(initial)}
        }
        val selected=context.diverse(pool.values.toList(),wanted,random)
        val all=previous+selected
        var possible=if(locks.isEmpty()) 0L else 1L
        // This is only an upper bound; zone constraints can make the true number smaller.
        var combinations=1L
        for(food in unlocked) {
            combinations=(combinations*(stepCount(food).toLong()+1)).coerceAtMost(MAX_OPTIONS+1L)
            if(combinations>MAX_OPTIONS) break
        }
        possible+=if(locks.isEmpty()) max(0L,combinations-1) else combinations-1
        val more=all.size<MAX_OPTIONS && possible>all.size
        val note=when {
            all.size>=MAX_OPTIONS -> "已达到本轮 50 套上限；最优标记只比较表内方案。"
            all.isEmpty() -> "本次有限搜索未找到可展示的非空方案；不代表数学上无解。请检查食品、锁定和分区设置。"
            selected.size<wanted -> "本次在时间与评估上限内新增 ${selected.size} 套，不凑数；未找到更多不代表不存在其他方案。"
            else -> "本次新增 ${selected.size} 套；采用有限搜索，表内最优不是全局最优。"
        }
        return context.batch(all,more,note)
    }

    private class Context(val foods:List<Candidate>,val target:Nutrition,val consumed:Nutrition,
        val locks:Map<Long,Double>,val zones:List<ZoneLimit>,val policy:RecommendationPolicy,val spent:Double?) {
        val remaining=target-consumed
        val byId=foods.associateBy {it.id}
        val eligible=foods.filter {it.allowed}
        val limits=zones.associateBy {it.zoneId}
        val rules=listOf(policy.carbs,policy.protein,policy.fat)
        val goal=listOf(target.carbs,target.protein,target.fat)
        init {RecommendationSearch.validateInputs(foods,remaining,zones,policy,spent)}

        fun node(amounts:Map<Long,Double>):Node? {
            if(locks.any {(id,q)->amounts[id]!=q} || locks.values.any {!it.isFinite() || it<=0}) return null
            var nutrition=Nutrition();var cost=0.0;var priced=true
            val portions=mutableMapOf<Long,Double>();val choices=mutableMapOf<Long,Int>()
            val positive=linkedMapOf<Long,Double>()
            for((id,q) in amounts.toSortedMap()) {
                val food=byId[id] ?: return null
                if(!food.allowed || !q.isFinite() || q<0 || q>food.maximum || abs(q/food.step-round(q/food.step))>=0.00001) return null
                if(q==0.0) continue
                positive[id]=q;nutrition+=food.perUnit*q
                if(food.pricePerUnitYuan==null) priced=false else cost+=food.pricePerUnitYuan*q
                food.zoneId?.let {zone->portions[zone]=(portions[zone] ?: 0.0)+q/food.zonePortionSize;choices[zone]=(choices[zone] ?: 0)+1}
            }
            if(!nutrition.values().all {it.isFinite()} || !(nutrition+consumed).values().all {it.isFinite()} || !cost.isFinite()) return null
            if(portions.any {(id,q)->limits[id]?.let {!QuotaMath.within(q,it.maximumPortions) || (!it.allowMultipleFoods && choices.getValue(id)>1)}==true}) return null
            if(priced && spent!=null && !(cost+spent).isFinite()) return null
            val key=positive.entries.joinToString(";") {(id,q)->"$id:${round(q/byId.getValue(id).step).toLong()}"}
            return Node(positive,nutrition,if(priced) cost else null,key)
        }
        fun replace(base:Node,food:Candidate,quantity:Double):Map<Long,Double> {
            val result=base.quantities.toMutableMap()
            if(quantity>0 && food.zoneId?.let {limits[it]?.allowMultipleFoods==false}==true) {
                val competing=result.keys.filter {it!=food.id && byId.getValue(it).zoneId==food.zoneId}
                if(competing.none {it in locks}) competing.forEach {result.remove(it)}
            }
            if(quantity>0) result[food.id]=quantity else result.remove(food.id)
            return result
        }
        fun amounts(food:Candidate,base:Node,random:Random):List<Double> {
            val maximum=stepCount(food)
            val indices=sortedSetOf(0,1,maximum)
            if(maximum<=32) indices.addAll(0..maximum) else {
                fun near(amount:Double) {
                    if(!amount.isFinite()) return
                    val center=floor(amount/food.step).coerceIn(0.0,maximum.toDouble()).toInt()
                    listOf(-2,-1,0,1,2).forEach {indices+=(center+it).coerceIn(0,maximum)}
                }
                val stripped=node(replace(base,food,0.0)) ?: base
                val n=stripped.nutrition.values();val per=food.perUnit.values();val left=remaining.values()
                per.indices.filter {per[it]>0}.forEach {i->
                    near((left[i]-n[i])/per[i])
                    if(i>0) {
                        rules[i-1].under?.let {near((left[i]-it-n[i])/per[i])}
                        rules[i-1].over?.let {near((left[i]+it-n[i])/per[i])}
                    }
                }
                policy.dailyBudgetYuan?.let {budget->
                    val price=food.pricePerUnitYuan
                    if(spent!=null && stripped.cost!=null && price!=null && price>0) near((budget-spent-stripped.cost)/price)
                }
                food.zoneId?.let {zone->limits[zone]?.let {limit->
                    val used=stripped.quantities.entries.filter {byId.getValue(it.key).zoneId==zone}.sumOf {it.value/byId.getValue(it.key).zonePortionSize}
                    near(max(0.0,limit.maximumPortions-used)*food.zonePortionSize)
                }}
                listOf(0.25,0.5,0.75).forEach {indices+=(maximum*it).toInt()}
                repeat(4) {indices+=random.nextInt(maximum+1)}
            }
            return indices.filter {it in 0..maximum}.map {amountAt(food,it)}
        }
        private fun macro(actual:Double,index:Int):ComparisonMacroMetric {
            val rule=rules[index];val wanted=goal[index]
            val mode=when {
                rule.under==0.0 && rule.over==0.0 -> ComparisonMacroMode.EXACT
                rule.under==0.0 -> ComparisonMacroMode.AT_LEAST
                rule.over==0.0 -> ComparisonMacroMode.AT_MOST
                else -> ComparisonMacroMode.CLOSEST
            }
            val satisfied=when(mode) {
                ComparisonMacroMode.AT_LEAST -> QuotaMath.within(wanted,actual)
                ComparisonMacroMode.AT_MOST -> QuotaMath.within(actual,wanted)
                ComparisonMacroMode.EXACT,ComparisonMacroMode.CLOSEST -> QuotaMath.within(actual,wanted) && QuotaMath.within(wanted,actual)
            }
            val rank=when(mode) {
                ComparisonMacroMode.AT_LEAST -> if(satisfied) 0.0 else max(0.0,wanted-actual)
                ComparisonMacroMode.AT_MOST -> actual
                else -> abs(actual-wanted)
            }
            return ComparisonMacroMetric(wanted,actual,actual-wanted,mode,satisfied,rank)
        }
        private fun metrics(node:Node):ComparisonMetrics {
            val total=node.nutrition+consumed
            return ComparisonMetrics(node.cost,if(node.cost!=null && spent!=null) node.cost+spent else null,
                macro(total.carbs,0),macro(total.protein,1),macro(total.fat,2),node.quantities.size)
        }
        private fun formal(node:Node):Boolean {
            if(policy.dailyBudgetYuan!=null && (node.cost==null || spent==null || !QuotaMath.within(spent,policy.dailyBudgetYuan) ||
                    !QuotaMath.within(node.cost,max(0.0,policy.dailyBudgetYuan-spent)))) return false
            val added=listOf(node.nutrition.carbs,node.nutrition.protein,node.nutrition.fat)
            val left=listOf(remaining.carbs,remaining.protein,remaining.fat)
            return rules.indices.all {i->(rules[i].under?.let {QuotaMath.within(max(0.0,left[i]-it),added[i])}!=false) &&
                (rules[i].over?.let {upper->left[i]+upper>=0 && QuotaMath.within(added[i],left[i]+upper)}!=false)}
        }
        private fun quality(node:Node):Double {
            val m=metrics(node)
            val macros=listOf(m.carbs,m.protein,m.fat)
            val distance=macros.sumOf {val ratio=it.rankValue/max(10.0,it.targetGrams);minOf(1e100,ratio*ratio)}
            val energy=abs((node.nutrition.kcal+consumed.kcal)-target.kcal)/max(100.0,target.kcal)
            return distance+minOf(1e100,energy*energy)*0.05+if(formal(node)) 0.0 else 4.0
        }
        fun diverse(nodes:List<Node>,count:Int,random:Random):List<Node> {
            if(nodes.size<=count) return nodes.sortedWith(compareBy<Node> {quality(it)}.thenBy {it.key})
            val chosen=linkedMapOf<String,Node>()
            fun add(order:List<Node>,n:Int) {order.take(n).forEach {chosen[it.key]=it}}
            val general=nodes.sortedWith(compareBy<Node> {quality(it)}.thenBy {it.key})
            add(general,max(1,count/3))
            val comparators=listOf<(Node)->Double?>(
                {it.cost},{if(it.cost!=null && spent!=null) it.cost+spent else null},
                {metrics(it).carbs.rankValue},{metrics(it).protein.rankValue},{metrics(it).fat.rankValue},{it.quantities.size.toDouble()})
            for(rank in comparators) {
                val order=nodes.filter {rank(it)!=null}.sortedWith(compareBy<Node> {rank(it)!!}.thenBy {quality(it)}.thenBy {it.key})
                add(order,max(1,count/12))
                if(chosen.size>=count) break
            }
            // Preserve tradeoffs and exact quantities, rather than deduplicating by nutrition totals.
            if(count>32) add(nodes.shuffled(random),count/10)
            for(node in general) {if(chosen.size>=count) break;chosen[node.key]=node}
            return chosen.values.take(count)
        }
        private fun option(node:Node):ComparisonOption {
            Recommender.validateProposal(foods,remaining,node.quantities,zones,RecommendationPolicy(),spent)
            check(locks.all {(id,q)->node.quantities[id]==q}) {"比较结果改变了锁定量"}
            val allowed=runCatching {Recommender.validateProposal(foods,remaining,node.quantities,zones,policy,spent)}.isSuccess
            val violations=mutableListOf<String>()
            var adjusted=policy
            val m=metrics(node)
            fun loosen(value:Double)=if(value==0.0) 0.0 else Math.nextUp(Math.nextUp(value))
            policy.dailyBudgetYuan?.let {budget->
                if(m.dailyCostYuan==null) {violations+="本次或已有摄入未完整计价，无法验证全天预算";adjusted=adjusted.copy(dailyBudgetYuan=null)}
                else if(!QuotaMath.within(spent!!,budget) || !QuotaMath.within(node.cost!!,max(0.0,budget-spent))) {
                    violations+="全天费用超过预算";adjusted=adjusted.copy(dailyBudgetYuan=loosen(max(budget,m.dailyCostYuan)))
                }
            }
            val added=listOf(node.nutrition.carbs,node.nutrition.protein,node.nutrition.fat)
            val left=listOf(remaining.carbs,remaining.protein,remaining.fat)
            val names=listOf("碳水","蛋白质","脂肪")
            val adjustedRules=rules.mapIndexed {i,rule->
                var next=rule
                if(rule.under?.let {QuotaMath.within(max(0.0,left[i]-it),added[i])}==false) {
                    violations+="${names[i]}低于允许下限";next=next.copy(under=loosen(max(0.0,left[i]-added[i])))
                }
                if(rule.over?.let {left[i]+it>=0 && QuotaMath.within(added[i],left[i]+it)}==false) {
                    violations+="${names[i]}超过允许上限";next=next.copy(over=loosen(max(0.0,added[i]-left[i])))
                }
                next
            }
            adjusted=adjusted.copy(carbs=adjustedRules[0],protein=adjustedRules[1],fat=adjustedRules[2])
            // Rounding near a zero residual can defeat a minimally widened finite tolerance.
            // Only the violated direction may fall back to unlimited; hard constraints never move.
            if(!allowed && runCatching {Recommender.validateProposal(foods,remaining,node.quantities,zones,adjusted,spent)}.isFailure) {
                val fallback=rules.mapIndexed {i,rule->MacroTolerance(
                    if(adjustedRules[i].under!=rule.under) null else rule.under,
                    if(adjustedRules[i].over!=rule.over) null else rule.over)}
                adjusted=adjusted.copy(dailyBudgetYuan=if(adjusted.dailyBudgetYuan!=policy.dailyBudgetYuan) null else policy.dailyBudgetYuan,
                    carbs=fallback[0],protein=fallback[1],fat=fallback[2])
            }
            Recommender.validateProposal(foods,remaining,node.quantities,zones,adjusted,spent)
            return ComparisonOption(node.key,Suggestion(node.quantities,node.nutrition,quality(node),node.cost),allowed,
                violations,if(allowed) policy else adjusted,m)
        }
        fun batch(nodes:List<Node>,more:Boolean,note:String):ComparisonBatch {
            val options=nodes.map(::option)
            fun value(option:ComparisonOption,column:ComparisonColumn):Double?=when(column) {
                ComparisonColumn.CURRENT_COST -> option.metrics.currentCostYuan
                ComparisonColumn.DAILY_COST -> option.metrics.dailyCostYuan
                ComparisonColumn.CARBS -> option.metrics.carbs.rankValue
                ComparisonColumn.PROTEIN -> option.metrics.protein.rankValue
                ComparisonColumn.FAT -> option.metrics.fat.rankValue
                ComparisonColumn.FOOD_COUNT -> option.metrics.foodCount.toDouble()
            }
            val minima=ComparisonColumn.entries.associateWith {column->options.mapNotNull {value(it,column)}.minOrNull()}
            val marked=options.map {option->option.copy(bestColumns=ComparisonColumn.entries.filter {column->
                val number=value(option,column);val minimum=minima[column]
                number!=null && minimum!=null && (number==minimum || (minimum>0 && abs(number-minimum)<=minOf(Math.ulp(minimum)*4,minimum*1e-12)))
            }.toSet())}
            return ComparisonBatch(marked,more,note,policy,target,consumed,spent)
        }
    }
}
