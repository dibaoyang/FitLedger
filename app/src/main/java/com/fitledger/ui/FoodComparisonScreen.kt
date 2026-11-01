package com.fitledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitledger.domain.*

@Composable fun FoodComparisonScreen(s:LedgerState,busy:Boolean) {
    Text("食品比价",style=MaterialTheme.typography.titleLarge)
    Text("比较达到同一数量或营养量的费用；使用当前食品库价格。金额不代表营养是否适合你。")
    var basisName by rememberSaveable {mutableStateOf(ComparisonBasis.KCAL_100.name)}
    val basis=ComparisonBasis.valueOf(basisName)
    var selected by remember {mutableStateOf(setOf<Long>())}
    var query by rememberSaveable {mutableStateOf("")}
    var descending by rememberSaveable {mutableStateOf(false)}
    Choice("比较口径",basis,ComparisonBasis.entries.toList(),{it.label},!busy) {basisName=it.name}
    OutlinedTextField(query,{query=it},label={Text("筛选食品名称")},singleLine=true,modifier=Modifier.fillMaxWidth())
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        TextButton(onClick={selected=s.foods.map {it.id}.toSet()},enabled=!busy) {Text("全选在库食品")}
        TextButton(onClick={selected=emptySet()},enabled=!busy) {Text("清空选择")}
    }
    searchFoods(s.foods,query).forEach {food->
        Row(Modifier.fillMaxWidth()) {
            Checkbox(food.id in selected,{checked->selected=if(checked) selected+food.id else selected-food.id},enabled=!busy)
            Text("${food.name} · ${food.unit}",Modifier.padding(top=12.dp))
        }
    }
    if(s.foods.isEmpty()) Text("食品库暂无可比较的食品，请先添加食品。")
    val quotes=s.foods.filter {it.id in selected}.map {food->
        FoodComparison.quote(ComparisonFood(food.id,food.name,food.unit,food.nutrition*(1/food.basisAmount),food.pricePerUnitYuan),basis)
    }
    if(quotes.isNotEmpty()) {
        HorizontalDivider()
        Text("已选 ${quotes.size} 项 · ${basis.label}",style=MaterialTheme.typography.titleMedium)
        TextButton(onClick={descending=!descending}) {Text(if(descending) "金额：从高到低 ↓" else "金额：从低到高 ↑")}
        val ordered=quotes.sortedWith(compareBy<ComparisonQuote> {it.costYuan==null}
            .thenBy {if(descending) -(it.costYuan ?: 0.0) else it.costYuan ?: 0.0}.thenBy {it.food.name})
        ordered.forEach {quote->Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(quote.food.name,style=MaterialTheme.typography.titleMedium)
                Text("${basis.label} · 所需 ${quote.quantity?.let {"${it.f2()} ${quote.food.unit}"} ?: "无法换算"}")
                Text(quote.costYuan?.let {"¥${it.money()}${if(it==0.0) "（免费）" else ""}"}
                    ?: quote.issues.joinToString("；") {it.label})
            }
        }}
    } else Text("勾选两项或更多食品，查看同一口径的费用。")
    Text("每 100 g 仅比较 g；每份仅比较份。g 与份可以按热量或宏量比较，不推算份重。营养量为 0、未填写价格分别标明；免费食品可以比较。")
}
