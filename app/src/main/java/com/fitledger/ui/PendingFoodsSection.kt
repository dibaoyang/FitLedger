package com.fitledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitledger.data.*
import com.fitledger.domain.*
import java.time.LocalDate

@Composable fun PendingFoodsSection(items:List<PendingFood>,foods:List<Food>,intakes:List<Intake>,day:Long,busy:Boolean,
    onEaten:(PendingFood)->Unit,onUpdate:(PendingFoodUpdate)->Unit,onRemove:(PendingFood)->Unit) {
    val today=items.filter {it.day==day}.sortedWith(compareBy<PendingFood> {it.createdAt}.thenBy {it.id})
    val expired=items.filter {it.day<day && it.appliedIntakeId==null}.sortedWith(compareByDescending<PendingFood> {it.day}.thenBy {it.id})
    var older by rememberSaveable {mutableStateOf(false)}
    Text("今日待吃",style=MaterialTheme.typography.titleLarge)
    Text("推荐可先保存到这里，吃了再点「已吃」。未确认的食品不计入今日摄入、费用或食品周期上限。")
    if(today.isEmpty()) Text("今天还没有待吃食品，可先计算搭配，再保存待吃。")
    val pending=today.filter {it.appliedIntakeId==null}
    if(pending.isNotEmpty()) {
        val sum=pending.fold(Nutrition()) {total,item->total+item.nutrition}
        if(sum.values().all {it.isFinite()}) NutritionCard("尚待确认的安排合计 · 不算已摄入",sum)
        val unknown=pending.count {it.costYuan==null};val known=pending.sumOf {it.costYuan ?: 0.0}
        Text(if(known.isFinite()) "待吃已计价费用 ¥${known.money()}"+(if(unknown>0) " · ${unknown}项未计价" else "") else "待吃费用合计超出计算范围")
    }
    today.forEach {item->key(item.id) {
        PendingFoodCard(item,foods.find {it.id==item.foodId},intakes,busy,true,onEaten,onUpdate,onRemove)
    }}
    if(expired.isNotEmpty()) {
        OutlinedButton(onClick={older=!older},enabled=!busy) {Text(if(older) "收起以前未吃安排" else "查看以前未吃安排（${expired.size}项）")}
        if(older) {
            Text("以前的安排不会自动算作今天吃了。可以删除未吃安排，需要时重新安排到今天。")
            expired.take(100).forEach {item->key(item.id) {
                Text(LocalDate.ofEpochDay(item.day).toString(),style=MaterialTheme.typography.titleSmall)
                PendingFoodCard(item,foods.find {it.id==item.foodId},intakes,busy,false,onEaten,onUpdate,onRemove)
            }}
            if(expired.size>100) Text("这里只显示前100项，清理后会显示更多。")
        }
    }
}

@Composable private fun PendingFoodCard(item:PendingFood,food:Food?,intakes:List<Intake>,busy:Boolean,current:Boolean,
    onEaten:(PendingFood)->Unit,onUpdate:(PendingFoodUpdate)->Unit,onRemove:(PendingFood)->Unit) {
    var editing by rememberSaveable(item.id) {mutableStateOf(false)}
    var raw by rememberSaveable(item.id,item.quantity) {mutableStateOf(PortionQuantities.format(item.unit,item.quantity))}
    var removing by remember {mutableStateOf(false)}
    LaunchedEffect(item) {editing=false}
    val applied=item.appliedIntakeId!=null
    val unchanged=PendingFoodUpdates.unchanged(item,food)
    val canRefresh=!applied && current && food!=null && !food.isDeleted && food.unit==item.unit
    Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(7.dp)) {
        Text("${item.name} · ${PortionQuantities.format(item.unit,item.quantity)} ${item.unit}",style=MaterialTheme.typography.titleMedium)
        Text("${item.nutrition.kcal.energy(LocalEnergyUnit.current)} · 碳水 ${item.nutrition.carbs.f()}g · 蛋白质 ${item.nutrition.protein.f()}g · 脂肪 ${item.nutrition.fat.f()}g")
        Text(item.costYuan?.let {"保存时估算 ¥${it.money()}"} ?: "保存时未计价")
        if(applied) {
            Text(if(intakes.any {it.id==item.appliedIntakeId}) "✓ 已吃，已计入实际摄入" else "已确认吃过；原摄入已删除，保留标记防止重复添加")
            Text("数量修改或删除请到实际摄入记录操作。",style=MaterialTheme.typography.bodySmall)
        } else {
            if(!current) Text("该安排日期已过，不会自动添加。",style=MaterialTheme.typography.bodySmall)
            else if(!unchanged) Text("食品资料、单位或价格已经变化；请先核对并刷新。",color=MaterialTheme.colorScheme.error)
            if(current && intakes.any {it.day==item.day && it.foodId==item.foodId}) Text("今天已经记录过这项食品，请确认这次是另外吃的数量。",color=MaterialTheme.colorScheme.primary)
            if(editing && canRefresh) {
                NumberField("实际准备的数量 ${item.unit}"+(if(item.unit=="份") "（整数）" else ""),raw,enabled=!busy) {raw=it}
                val result=runCatching {PendingFoodUpdates.preview(item,requireNotNull(food),requireNotNull(raw.toDoubleOrNull()) {"请填写有效数量"})}
                result.getOrNull()?.let {preview->
                    NutritionCard("保存后待吃预览 · 仍未计入摄入",preview.after.nutrition)
                    Text(preview.after.costYuan?.let {"更新后费用 ¥${it.money()}"} ?: "更新后费用未计价")
                    Text("保存会刷新这项食品当前的名称、营养和价格；吃了以后再点「已吃」。")
                    Button(onClick={onUpdate(preview)},enabled=!busy) {Text("保存数量并刷新资料")}
                }
                result.exceptionOrNull()?.message?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                TextButton(onClick={editing=false},enabled=!busy) {Text("取消修改")}
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick={onEaten(item)},enabled=!busy && current && unchanged) {Text("已吃")}
                TextButton(onClick={editing=!editing},enabled=!busy && canRefresh) {Text(if(editing) "收起修改" else "修改／刷新")}
                TextButton(onClick={removing=true},enabled=!busy) {Text("删除待吃")}
            }
            if(current && !canRefresh && !unchanged) Text("原食品已删除或计量改变，请删除这项安排，再选择当前有效食品。",style=MaterialTheme.typography.bodySmall)
        }
    }}
    if(removing) AlertDialog(onDismissRequest={if(!busy) removing=false},title={Text("删除未吃安排？")},
        text={Text("只删除「${item.name}」的待吃安排，不删除食品、已吃记录或目标。")},
        confirmButton={TextButton(onClick={onRemove(item);removing=false},enabled=!busy) {Text("删除待吃")}},
        dismissButton={TextButton(onClick={removing=false},enabled=!busy) {Text("取消")}})
}
