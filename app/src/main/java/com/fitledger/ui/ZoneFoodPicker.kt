package com.fitledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.fitledger.data.*

private val zoneFoodSelectionSaver = listSaver<SnapshotStateList<Long>, Long>(
    save = { it.toList() }, restore = { it.toMutableStateList() }
)

@Composable
fun ZoneFoodPicker(zone:FoodZone,foods:List<Food>,zones:List<FoodZone>,busy:Boolean,
    onDismiss:()->Unit,onConfirm:(List<ZoneFoodAssignment>)->Unit) {
    var query by rememberSaveable(zone.id) {mutableStateOf("")}
    var onlySelected by rememberSaveable(zone.id) {mutableStateOf(false)}
    val selectedIds=rememberSaveable(zone.id,saver=zoneFoodSelectionSaver) {mutableStateListOf<Long>()}
    val nativeUnit=FoodZoneQuotas.unitFor(zone)
    val needsReview=FoodZoneQuotas.zoneIssues(zone,foods).isNotEmpty()
    val activeFoods=foods.filter {!it.isDeleted && FoodZoneQuotas.matchesUnit(it,zone)}
    val selectedFoods=activeFoods.filter {it.id in selectedIds && it.zoneId!=zone.id}
    val currentCount=activeFoods.count {it.zoneId==zone.id}
    val movedCount=selectedFoods.count {it.zoneId!=null}
    val visibleFoods=activeFoods.filter {(!onlySelected || (it.id in selectedIds && it.zoneId!=zone.id)) && it.name.contains(query.trim(),ignoreCase=true)}
        .sortedWith(compareBy<Food> {if(it.zoneId==zone.id) 0 else 1}.thenBy {it.sortOrder}.thenBy {it.name}.thenBy {it.id})
    val maximumHeight=LocalConfiguration.current.screenHeightDp.dp*0.9f
    Dialog(onDismissRequest={if(!busy) onDismiss()},properties=DialogProperties(dismissOnBackPress=!busy,dismissOnClickOutside=!busy,usePlatformDefaultWidth=false)) {
        Surface(Modifier.padding(horizontal=12.dp,vertical=16.dp).widthIn(max=560.dp).fillMaxWidth().heightIn(max=maximumHeight),
            shape=MaterialTheme.shapes.extraLarge,tonalElevation=6.dp) {
            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text("给「${zone.name}」添加食品",style=MaterialTheme.typography.titleLarge,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text("只显示按 $nativeUnit 记录的食品；本区按 $nativeUnit 直接累计，不跨单位换算。",style=MaterialTheme.typography.bodySmall)
                if(needsReview) Text("本区有旧单位或旧换算需要处理，请先回到分区管理整理并确认上限。",color=MaterialTheme.colorScheme.error)
                OutlinedTextField(query,{query=it},label={Text("搜索食品名称")},modifier=Modifier.fillMaxWidth(),singleLine=true,enabled=!busy,
                    trailingIcon={if(query.isNotEmpty()) TextButton(onClick={query=""},enabled=!busy) {Text("清空")}})
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text("本区已有 $currentCount 项同单位食品",Modifier.weight(1f),style=MaterialTheme.typography.bodySmall)
                    FilterChip(onlySelected,{onlySelected=!onlySelected;query=""},enabled=!busy,label={Text("待添加 ${selectedFoods.size} 项")})
                }
                HorizontalDivider()
                LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    if(visibleFoods.isEmpty()) item {
                        Text(when {
                            onlySelected && selectedFoods.isEmpty()->"还没有待添加食品，可关闭「待添加」筛选后勾选。"
                            activeFoods.isEmpty()->"食品库没有按 $nativeUnit 记录的食品，请先添加同单位食品。"
                            else->"没有匹配的同单位食品，试试其他名称。"
                        },Modifier.padding(vertical=12.dp))
                    }
                    items(visibleFoods,key={it.id}) {food->
                        val existing=food.zoneId==zone.id
                        val selected=existing || food.id in selectedIds
                        val sourceName=zones.find {it.id==food.zoneId}?.name ?: "其他分区"
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().toggleable(selected,enabled=!busy && !existing && !needsReview,role=Role.Checkbox,
                                onValueChange={checked->if(checked) selectedIds.add(food.id) else selectedIds.remove(food.id)}).padding(10.dp),verticalAlignment=Alignment.CenterVertically) {
                                Checkbox(selected,onCheckedChange=null,enabled=!busy && !existing && !needsReview)
                                Column(Modifier.weight(1f).padding(start=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                    Text(food.name,style=MaterialTheme.typography.titleSmall)
                                    Text(when {existing->"已在本区 · 保留现有设置";food.zoneId!=null->"来自「$sourceName」· 确认后移入本区";else->"未分区 · 按 $nativeUnit 记录"},style=MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                HorizontalDivider()
                Text("将添加 ${selectedFoods.size} 项，其中 $movedCount 项从其他分区移入。每项食品只归属一个种类分区。资料和历史记录保留，今日分区额度按新归属计算。",style=MaterialTheme.typography.bodySmall)
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick=onDismiss,enabled=!busy) {Text("取消")}
                    Button(onClick={onConfirm(selectedFoods.map {ZoneFoodAssignment(it.id,1.0,it.gramsPerPortion)})},modifier=Modifier.weight(1f),
                        enabled=!busy && selectedFoods.isNotEmpty() && !needsReview) {Text(if(busy) "正在添加…" else "确认添加（${selectedFoods.size} 项）")}
                }
            }
        }
    }
}