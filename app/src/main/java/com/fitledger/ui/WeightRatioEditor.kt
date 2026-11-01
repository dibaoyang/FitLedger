package com.fitledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitledger.data.Food
import com.fitledger.domain.*

@Composable fun WeightRatioEditor(foods:List<Food>,encoded:String,busy:Boolean,onChange:(String)->Unit) {
    val decoded=runCatching {WeightRatios.decode(encoded)}
    val rules=decoded.getOrDefault(emptyList())
    val choices=foods.filter {!it.isDeleted && (it.unit=="g" || it.gramsPerPortion!=null)}
    var dependent by rememberSaveable {mutableStateOf<Long?>(null)}
    var reference by rememberSaveable {mutableStateOf<Long?>(null)}
    var raw by rememberSaveable {mutableStateOf("0.5")}
    Text("重量比例",style=MaterialTheme.typography.titleMedium)
    Text("例如A重量＝B重量×0.5。按各自食品调整单位就近取整，预览显示目标、实际与偏差。按份食品须先填写每份克重；分区仍按原生g／份计数。")
    decoded.exceptionOrNull()?.message?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    rules.forEach {rule->Row(Modifier.fillMaxWidth()) {
        Text("${foods.find {it.id==rule.foodId}?.name?:"食品${rule.foodId}"}＝${foods.find {it.id==rule.referenceFoodId}?.name?:"食品${rule.referenceFoodId}"}重量×${rule.fraction.ratioNumber()}",Modifier.weight(1f))
        TextButton(onClick={onChange(WeightRatios.encode(rules.filterNot {it.foodId==rule.foodId}))},enabled=!busy) {Text("移除")}
    }}
    if(choices.size<2) {Text("至少需要两种有已知重量的食品。按份食品可在食品编辑中填写每份克重。");return}
    val a=choices.find {it.id==dependent}?:choices.first()
    val references=choices.filter {it.id!=a.id}
    val b=references.find {it.id==reference}?:references.first()
    Choice("食品A",a.id,choices.map {it.id},{id->choices.first {it.id==id}.name},enabled=!busy) {dependent=it}
    Choice("参考食品B",b.id,references.map {it.id},{id->references.first {it.id==id}.name},enabled=!busy) {reference=it}
    NumberField("A重量／B重量，例如0.5",raw,enabled=!busy) {raw=it}
    val fraction=raw.toDoubleOrNull()
    val next=if(fraction!=null)runCatching {WeightRatios.encode(rules.filterNot {it.foodId==a.id}+WeightRatio(a.id,b.id,fraction))} else null
    next?.exceptionOrNull()?.message?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    Button(onClick={next?.getOrNull()?.let(onChange)},enabled=!busy && next?.isSuccess==true) {Text(if(rules.any {it.foodId==a.id}) "更新此比例" else "加入比例")}
    Text("同一食品只有一个参考，不能循环引用；选择其中一种时需要一起包含另一种，都未选择时不会强制新增。保存规则不改已记录摄入。",style=MaterialTheme.typography.bodySmall)
}

@Composable fun RatioDeviationRows(foods:List<Food>,deviations:List<WeightRatioDeviation>) {
    deviations.forEach {d->
        val name=foods.find {it.id==d.foodId}?.name?:"食品${d.foodId}"
        Text("$name：目标${d.targetGrams.ratioNumber()}g → 实际${d.actualGrams.ratioNumber()}g；偏差${d.deltaGrams.ratioNumber()}g（${d.percent.ratioNumber()}%）",
            color=if(kotlin.math.abs(d.percent)>0.00001)MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurface)
    }
}

private fun Double.ratioNumber():String {
    if(!isFinite()) return toString()
    val rounded=java.math.BigDecimal.valueOf(this).setScale(6,java.math.RoundingMode.HALF_UP).stripTrailingZeros()
    return if(rounded.signum()==0 && this!=0.0) toString() else rounded.toPlainString()
}
