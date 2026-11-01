package com.fitledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitledger.data.*
import com.fitledger.domain.FoodAliases
import com.fitledger.domain.PortionQuantities
import java.util.Locale

fun searchFoods(foods:List<Food>,query:String,favoritesOnly:Boolean=false):List<Food> {
    val words=query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter {it.isNotEmpty()}
    return foods.filter {food->
        val aliases=runCatching {FoodAliases.decode(food.aliases).joinToString(" ")}.getOrDefault("")
        (!favoritesOnly || food.favorite) && words.all {it in (food.name+" "+aliases+" "+food.nutritionTags.nutritionLabel()).lowercase(Locale.ROOT)}
    }
}
/** Preserve invisible slots when a filtered list is sorted. */
fun <T> mergeFilteredOrder(all:List<T>,updated:List<T>):List<T> {
    require(all.distinct().size==all.size && updated.distinct().size==updated.size && all.containsAll(updated))
    val visible=updated.toSet();val iterator=updated.iterator()
    return all.map {if(it in visible) iterator.next() else it}
}

@Composable fun FoodSearchControls(query:String,favorites:Boolean,busy:Boolean,onQuery:(String)->Unit,onFavorites:(Boolean)->Unit) {
    OutlinedTextField(query,onQuery,label={Text("搜索食品名称、别名或营养分类")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy)
    FilterChip(favorites,{onFavorites(!favorites)},label={Text(if(favorites) "★ 只看收藏" else "☆ 只看收藏")},enabled=!busy)
}

@Composable fun FoodQuickAmounts(food:Food,intakes:List<Intake>,day:Long,busy:Boolean,onSelect:(Double)->Unit) {
    val recent=intakes.filter {it.foodId==food.id && it.unit==food.unit && it.day<=day}.maxWithOrNull(compareBy<Intake> {it.day}.thenBy {it.id})?.quantity
    val configured=QuickAmounts.decode(food.quickAmounts)
    val defaults=if(food.unit=="g") listOf(50.0,100.0,200.0) else listOf(1.0,2.0,3.0)
    val values=(listOfNotNull(recent)+configured.ifEmpty {defaults}).filter {PortionQuantities.isWhole(food.unit,it)}.distinct().take(8)
    Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
        values.chunked(3).forEach {row->Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            row.forEach {q->OutlinedButton(onClick={onSelect(q)},enabled=!busy) {Text("${PortionQuantities.format(food.unit,q)} ${food.unit}"+if(q==recent) " · 最近" else "")}}
        }}
    }
}

@Composable fun FoodRecycleBin(vm:LedgerViewModel,s:LedgerState,busy:Boolean) {
    var query by rememberSaveable {mutableStateOf("")}
    var restoring by rememberSaveable {mutableStateOf<Long?>(null)}
    Text("食品回收站",style=MaterialTheme.typography.titleLarge)
    Text("删除的食品仍保留原身份和历史摄入，恢复后不用重新填写。")
    OutlinedTextField(query,{query=it},label={Text("搜索已删除食品")},modifier=Modifier.fillMaxWidth(),enabled=!busy)
    val deleted=searchFoods(s.allFoods.filter {it.isDeleted},query)
    if(deleted.isEmpty()) Text("没有符合条件的已删除食品。")
    deleted.forEach {food->Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp)) {
        Text(food.name,style=MaterialTheme.typography.titleMedium)
        Text("每 ${food.basisAmount.f()} ${food.unit} · ${food.nutrition.kcal.energy(LocalEnergyUnit.current)}")
        FoodPriceLabel(food)
        TextButton(onClick={restoring=food.id},enabled=!busy) {Text("恢复食品")}
    }}}
    s.allFoods.find {it.id==restoring && it.isDeleted}?.let {food->AlertDialog(onDismissRequest={restoring=null},title={Text("恢复 ${food.name}？")},text={Text("食品会回到食品库，保留删除前的营养、价格、收藏和推荐设置。既有摄入不变。")},
        confirmButton={TextButton(onClick={vm.restoreFood(food.id);restoring=null},enabled=!busy) {Text("恢复")}},dismissButton={TextButton(onClick={restoring=null}) {Text("取消")}})}
}
