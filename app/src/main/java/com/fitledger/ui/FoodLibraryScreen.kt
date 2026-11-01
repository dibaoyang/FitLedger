package com.fitledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitledger.data.*
import com.fitledger.domain.PortionQuantities

private data class LibraryEntry(val key:String,val order:Long,val name:String,val zone:FoodZone?=null,val food:Food?=null)

@Composable fun FoodLibraryScreen(vm:LedgerViewModel,s:LedgerState,busy:Boolean) {
    var grouping by rememberSaveable {mutableStateOf("TYPE")}
    var filter by rememberSaveable {mutableStateOf("ALL")}
    var editingId by rememberSaveable {mutableStateOf<Long?>(null)}
    var showEditor by rememberSaveable {mutableStateOf(false)}
    var deletingId by rememberSaveable {mutableStateOf<Long?>(null)}
    val editing=s.foods.find {it.id==editingId}
    var page by rememberSaveable {mutableStateOf("FOODS")}
    var query by rememberSaveable {mutableStateOf("")}
    var favoritesOnly by rememberSaveable {mutableStateOf(false)}
    LaunchedEffect(s.foods.map {it.id}) {
        if(editingId!=null && editing==null) {editingId=null;showEditor=false}
        if(deletingId!=null && s.foods.none {it.id==deletingId}) deletingId=null
    }
    Text("我的食品库",style=MaterialTheme.typography.titleLarge)
    Choice("功能",page,listOf("FOODS","COMBOS","RECIPES","BATCHES","BATCH","TRASH","COMPARE"),{when(it) {"COMBOS"->"常吃组合";"RECIPES"->"自制食品配方";"BATCHES"->"配方批次与分装";"BATCH"->"批量编辑";"TRASH"->"食品回收站";"COMPARE"->"食品比价";else->"食品列表"}},enabled=!busy) {page=it}
    when(page) {
        "COMBOS"->{FoodCombosScreen(vm,s,busy);return}
        "TRASH"->{FoodRecycleBin(vm,s,busy);return}
        "COMPARE"->{FoodComparisonScreen(s,busy);return}
        "RECIPES"->{RecipesScreen(s.foods,s.recipes,busy,vm::saveRecipe,vm::deleteRecipe);return}
        "BATCH"->{BatchFoodEditor(s.foods,busy,vm::applyFoodBatch);return}
        "BATCHES"->{RecipeBatchesSection(s.recipes,s.recipeBatches,s.foods,busy,vm::saveRecipeBatch,vm::deleteRecipeBatch);return}
    }
    Text("推荐只使用这里允许参与的食品。种类分区设置共同上限，营养分区可自由多选，价格按你的实际购买情况填写。")
    FoodSearchControls(query,favoritesOnly,busy,{query=it},{favoritesOnly=it})
    val matching=searchFoods(s.foods,query,favoritesOnly)
    val matchingIds=matching.map {it.id}.toSet()
    FoodGroupingFilter(grouping,filter,s.zones,!busy,{grouping=it;filter="ALL"},{filter=it})
    val temporarilyExpanded=query.isNotBlank() || favoritesOnly || filter!="ALL"
    val collapseKeys=if(grouping=="TYPE") s.zones.map {LibraryGroupCollapse.type(it.id)}.toSet()
        else setOf("CARBS","PROTEIN","FAT","NONE").map(LibraryGroupCollapse::nutrition).toSet()
    if(collapseKeys.isNotEmpty()) Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        TextButton(onClick={vm.libraryGroupsCollapsed(collapseKeys,false)},enabled=!busy) {Text("全部展开")}
        TextButton(onClick={vm.libraryGroupsCollapsed(collapseKeys,true)},enabled=!busy && !temporarilyExpanded) {Text("全部收起")}
    }
    if(temporarilyExpanded) Text("筛选结果暂时展开；清除搜索、收藏和分区筛选后恢复各分区原来的折叠状态。",style=MaterialTheme.typography.bodySmall)
    Button(onClick={editingId=null;showEditor=true},enabled=!busy) {Text("添加食品")}
    FoodCodeImporter(vm,s.foods,busy)
    ZoneManager(vm,s.zones,s.foods,busy)
    if(showEditor) key(editing?.id) {
        FoodEditor(editing,busy,s.zones,if(grouping=="TYPE") filter.toLongOrNull() else null,
            initialTags=if(grouping=="NUTRITION" && filter in listOf("CARBS","PROTEIN","FAT")) filter else "",
            zoneFoods=s.allFoods,save={vm.saveFood(it) {showEditor=false}},close={showEditor=false})
    }
    if(s.foods.isEmpty()) Text("食品库暂无食品，添加后即可记录和推荐；已有摄入记录保留。")
    val edit:(Food)->Unit={editingId=it.id;showEditor=true}
    val delete:(Food)->Unit={deletingId=it.id}
    if(grouping=="TYPE") {
        Text("长按「≡」拖动：整个分区可与未分区食品排序，分区里的食品可单独排序。")
        val all=(s.zones.map {LibraryEntry("zone:${it.id}",it.sortOrder,it.name,zone=it)}+
            s.foods.filter {it.zoneId==null}.map {LibraryEntry("food:${it.id}",it.sortOrder,it.name,food=it)})
            .sortedWith(compareBy<LibraryEntry> {it.order}.thenBy {it.name}.thenBy {it.key})
        val visible=when(filter) {"ALL"->all;"NONE"->all.filter {it.food!=null};else->all.filter {it.zone?.id?.toString()==filter}}
            .filter {entry->(query.isBlank() && !favoritesOnly) || entry.food?.id in matchingIds || (entry.zone!=null && matching.any {it.zoneId==entry.zone.id})}
        if(visible.isEmpty()) Text("没有符合筛选条件的食品。")
        DragSortColumn(visible,{it.key},busy,{updated->
            // A filtered list may rearrange its own slots, never omit another top-level item.
            val ids=visible.map {it.key}.toSet();val iterator=updated.iterator()
            vm.reorderLibrary(all.map {if(it.key in ids) iterator.next().key else it.key})
        }) {entry,handle->
            entry.food?.let {FoodLibraryCard(it,vm,busy,handle,edit,delete)}
            entry.zone?.let {zone->
                Card(Modifier.fillMaxWidth(),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        val groupKey=LibraryGroupCollapse.type(zone.id)
                        val collapsed=groupKey in s.preferences.collapsedLibraryGroups
                        val expanded=temporarilyExpanded || !collapsed
                        Row(Modifier.fillMaxWidth()) {
                            Text(zone.name,Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                            TextButton(onClick={vm.libraryGroupsCollapsed(setOf(groupKey),!collapsed)},enabled=!busy && !temporarilyExpanded) {Text(if(expanded) "▾ 收起" else "▸ 展开")}
                            handle()
                        }
                        Text("每日 ${zone.dailyMaximum?.let {"${zone.quotaAmount(it)} ${zone.quotaUnitLabel()}"} ?: "不限"} · 单次推荐 ${zone.recommendationMaximum?.let {"${zone.quotaAmount(it)} ${zone.quotaUnitLabel()}"} ?: "跟随每日"}")
                        val allFoods=s.foods.filter {it.zoneId==zone.id}.sortedWith(compareBy<Food> {it.sortOrder}.thenBy {it.name}.thenBy {it.id})
                        val foods=allFoods.filter {it.id in matchingIds}
                        if(!expanded) Text("已收起 · ${allFoods.size}项食品",style=MaterialTheme.typography.bodySmall)
                        else {
                            if(foods.isEmpty()) Text("此分区暂无食品，添加或编辑食品时可指定分区。")
                            DragSortColumn(foods,{"food:${it.id}"},busy,{updated->vm.reorderZone(zone.id,mergeFilteredOrder(allFoods.map {it.id},updated.map {it.id}))}) {food,foodHandle->
                                FoodLibraryCard(food,vm,busy,foodHandle,edit,delete,zone)
                            }
                        }
                    }
                }
            }
        }
    } else {
        Text("一个食品可出现在多个营养分区，记录时不会重复计算。切回「按种类」可拖动排序。")
        val nutrientSections=listOf("CARBS","PROTEIN","FAT","NONE")
        val sections=if(filter in nutrientSections) listOf(filter) else nutrientSections
        sections.forEach {section->key(section) {
            val groupKey=LibraryGroupCollapse.nutrition(section)
            val collapsed=groupKey in s.preferences.collapsedLibraryGroups
            val expanded=temporarilyExpanded || !collapsed
            Row(Modifier.fillMaxWidth()) {
                Text(if(section=="NONE") "未分类" else section.nutritionLabel(),Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                TextButton(onClick={vm.libraryGroupsCollapsed(setOf(groupKey),!collapsed)},enabled=!busy && !temporarilyExpanded) {Text(if(expanded) "▾ 收起" else "▸ 展开")}
            }
            val foods=filterFoods(matching,grouping,section)
            if(!expanded) Text("已收起 · ${foods.size}项食品",style=MaterialTheme.typography.bodySmall)
            else {
                if(foods.isEmpty()) Text("这个营养分区暂无食品，编辑食品即可设置。")
                foods.forEach {food->key(food.id) {FoodLibraryCard(food,vm,busy,{},edit,delete,s.zones.find {it.id==food.zoneId})}}
            }
        }}
    }
    s.foods.find {it.id==deletingId}?.let {food->
        AlertDialog(onDismissRequest={if(!busy) deletingId=null},title={Text("删除食品？")},
            text={Text("删除「${food.name}」后不再提供记录和推荐。今天和历史摄入、费用与目标都保留，关联的每日固定设置会暂停添加。")},
            confirmButton={TextButton(onClick={vm.deleteFood(food.id) {deletingId=null;if(editingId==food.id) {editingId=null;showEditor=false}}},enabled=!busy) {Text("确认删除")}},
            dismissButton={TextButton(onClick={deletingId=null},enabled=!busy) {Text("取消")}})
    }
}

@Composable private fun FoodLibraryCard(food:Food,vm:LedgerViewModel,busy:Boolean,handle:@Composable ()->Unit,edit:(Food)->Unit,delete:(Food)->Unit,zone:FoodZone?=null) {
    Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) {Text(food.name,Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);TextButton(onClick={vm.favorite(food)},enabled=!busy) {Text(if(food.favorite) "★" else "☆")};handle()}
        Text(food.nutritionTags.nutritionLabel())
        Text("每 ${PortionQuantities.format("g",food.basisAmount)} ${food.unit}：${food.nutrition.kcal.energy(LocalEnergyUnit.current)}\n碳水 ${food.nutrition.carbs.f()} / 蛋白质 ${food.nutrition.protein.f()} / 脂肪 ${food.nutrition.fat.f()} g")
        FoodPriceLabel(food)
        Text("推荐步长 ${PortionQuantities.format(food.unit,food.candidate().step)} ${food.unit} · 最小 ${PortionQuantities.format(food.unit,food.minimum)} · 最大 ${PortionQuantities.format(food.unit,food.maximum)} ${food.unit}")
        if(food.preparationTags.isNotEmpty()) Text("准备条件：${food.preparationTags}")
        if(zone!=null) {
            Text("本区按 ${zone.quotaUnitLabel()} 直接累计，不跨单位换算")
            if(!FoodZoneQuotas.matchesUnit(food,zone)) Text("旧分区单位不匹配，请在分区管理中移出或调整分区单位。",color=MaterialTheme.colorScheme.error)
            else if(zone.limitUnit=="PORTION" && food.zonePortionSize!=1.0) Text("旧换算待确认：请到分区管理重新填写原生份数上限。",color=MaterialTheme.colorScheme.error)
        }
        Row {Switch(food.allowed,{vm.foodAllowed(food.id,it)},enabled=!busy);Text("参与推荐",Modifier.padding(12.dp))}
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            TextButton(onClick={edit(food)},enabled=!busy) {Text("编辑")}
            TextButton(onClick={delete(food)},enabled=!busy) {Text("删除食品")}
        }
        CopyFoodCodeButton(food,busy,vm)
    }}
}
