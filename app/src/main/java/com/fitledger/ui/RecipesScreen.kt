package com.fitledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitledger.data.*
import com.fitledger.domain.PortionQuantities

@Composable fun RecipesScreen(foods:List<Food>,recipes:List<FoodRecipe>,busy:Boolean,
    onSave:(FoodRecipe,Boolean,FoodRecipe?,Food?,()->Unit)->Unit,onDelete:(FoodRecipe)->Unit) {
    var adding by rememberSaveable {mutableStateOf(false)}
    var editing by remember {mutableStateOf<FoodRecipe?>(null)}
    var deleting by remember {mutableStateOf<FoodRecipe?>(null)}
    Text("自制食品配方",style=MaterialTheme.typography.titleLarge)
    Text("选食材和用量，再填写成品实际总重量，估算每100g营养和成本。保存配方不会记录今天吃了什么，需另行记录实际吃掉的量。")
    Button(onClick={adding=true;editing=null},enabled=!busy && foods.any {!it.isDeleted}) {Text("新建配方")}
    if(adding || editing!=null) key(editing?.id ?: 0L) {
        RecipeEditor(editing,foods,busy,onSave) {adding=false;editing=null}
    }
    if(recipes.isEmpty()) Text("还没有配方，先把食材加入个人食品库。")
    recipes.forEach {recipe->key(recipe.id) {
        val summary=runCatching {RecipeCalculator.calculate(RecipeIngredients.decode(recipe.ingredients),recipe.finishedWeightG)}.getOrNull()
        Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(recipe.name,style=MaterialTheme.typography.titleMedium)
            Text("成品 ${recipe.finishedWeightG.f()} g · ${if(recipe.foodId==null) "尚未生成食品" else "已关联食品库"}")
            summary?.let {value->
                Text("每100g：${value.per100g.kcal.energy(LocalEnergyUnit.current)} · 碳水 ${value.per100g.carbs.f()}g · 蛋白质 ${value.per100g.protein.f()}g · 脂肪 ${value.per100g.fat.f()}g")
                Text(value.costPer100gYuan?.let {"每100g约 ¥${it.money()}"} ?: "成本不完整：${value.missingPriceCount}种食材未计价")
            }
            Row {
                TextButton(onClick={adding=false;editing=recipe},enabled=!busy) {Text("编辑／更新生成食品")}
                TextButton(onClick={deleting=recipe},enabled=!busy) {Text("删除配方")}
            }
        }}
    }}
    deleting?.let {recipe->AlertDialog(onDismissRequest={if(!busy) deleting=null},title={Text("删除 ${recipe.name}？")},
        text={Text("只删除配方定义，已经生成的食品及所有身体、目标、饮食历史都会保留。")},
        confirmButton={TextButton(onClick={onDelete(recipe);deleting=null},enabled=!busy) {Text("删除配方")}},
        dismissButton={TextButton(onClick={deleting=null},enabled=!busy) {Text("取消")}})}
}

@Composable private fun RecipeEditor(original:FoodRecipe?,foods:List<Food>,busy:Boolean,
    onSave:(FoodRecipe,Boolean,FoodRecipe?,Food?,()->Unit)->Unit,onClose:()->Unit) {
    var name by rememberSaveable {mutableStateOf(original?.name ?: "")}
    var yieldText by rememberSaveable {mutableStateOf(original?.finishedWeightG?.toString() ?: "")}
    var query by rememberSaveable {mutableStateOf("")}
    var selectedId by rememberSaveable {mutableStateOf<Long?>(null)}
    var quantity by rememberSaveable {mutableStateOf("")}
    val items=remember {mutableStateListOf<RecipeIngredient>().apply {original?.let {addAll(RecipeIngredients.decode(it.ingredients))}}}
    val amounts=remember {mutableStateMapOf<Long,String>().apply {items.forEach {put(it.foodId,PortionQuantities.inputText(it.quantity))}}}
    var publish by rememberSaveable {mutableStateOf(original?.foodId==null)}
    var confirm by remember {mutableStateOf(false)}
    val expectedFood=remember {foods.find {it.id==original?.foodId}}
    val available=searchFoods(foods.filter {!it.isDeleted && it.id!=original?.foodId},query)
    val selected=available.find {it.id==selectedId} ?: available.firstOrNull()
    val ingredient=selected?.let {food->quantity.toDoubleOrNull()?.let {runCatching {PortionQuantities.requireWhole(food.unit,it);RecipeIngredients.fromFood(food,it)}.getOrNull()}}
    OutlinedTextField(name,{name=it},label={Text("配方／生成食品名称")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy)
    OutlinedTextField(query,{query=it},label={Text("搜索食材名称、别名")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy)
    if(selected!=null) {
        Choice("选择食材",selected.id,available.map {it.id},{id->available.first {it.id==id}.name},enabled=!busy) {selectedId=it}
        NumberField("食材用量 ${selected.unit}",quantity,enabled=!busy) {quantity=it}
        if(selected.unit=="份") Text("新增食材份数须为整数。")
        Button(onClick={ingredient?.let {items.add(it);amounts[it.foodId]=PortionQuantities.inputText(it.quantity);quantity=""}},
            enabled=!busy && ingredient!=null && items.none {it.foodId==ingredient.foodId} && items.size<50) {Text("加入食材")}
    } else Text("没有可选食材，请先到食品库添加。")
    items.toList().forEach {item->key(item.foodId) {
        val current=foods.find {it.id==item.foodId && !it.isDeleted && it.unit==item.unit}
        Text("${item.name} · 保存的营养快照",style=MaterialTheme.typography.titleSmall)
        NumberField("用量 ${item.unit}",amounts[item.foodId].orEmpty(),enabled=!busy) {amounts[item.foodId]=it}
        if(item.unit=="份" && !PortionQuantities.isWhole(item.unit,item.quantity)) Text("这里保留旧配方的小数份快照；修改数量时须填整数，不会自动取整。")
        if(current==null) Text("原食材已删除或单位改变；这里仍保留旧营养快照，可继续估算或手动移除。")
        val refreshed=current?.let {food->amounts[item.foodId]?.toDoubleOrNull()?.let {runCatching {PortionQuantities.requireWhole(food.unit,it);RecipeIngredients.fromFood(food,it)}.getOrNull()}}
        Row {
            TextButton(onClick={refreshed?.let {replacement->val i=items.indexOfFirst {it.foodId==item.foodId};if(i>=0) items[i]=replacement}},
                enabled=!busy && refreshed!=null) {Text("按当前食品资料刷新此项")}
            TextButton(onClick={items.removeAll {it.foodId==item.foodId};amounts.remove(item.foodId)},enabled=!busy) {Text("移除食材")}
        }
    }}
    NumberField("成品实际总重量 g",yieldText,enabled=!busy) {yieldText=it}
    Text("请称成品总重量，不能用吃掉的份量代替。计算按食材营养合计分摊，不自动推断烹饪流失；食材资料变化后需明确刷新，不会重算旧配方。")
    val draft=runCatching {
        val savedItems=items.map {item->
            val amount=requireNotNull(amounts[item.foodId]?.toDoubleOrNull())
            if(original==null || RecipeIngredients.decode(original.ingredients).none {it.foodId==item.foodId && it.unit==item.unit && it.quantity==amount}) PortionQuantities.requireWhole(item.unit,amount)
            item.resized(amount)
        }
        FoodRecipe(original?.id ?: 0,name.trim(),RecipeIngredients.encode(savedItems),requireNotNull(yieldText.toDoubleOrNull()),original?.foodId).also {it.validate()}
    }.getOrNull()
    val summary=draft?.let {RecipeCalculator.calculate(RecipeIngredients.decode(it.ingredients),it.finishedWeightG)}
    summary?.let {value->
        NutritionCard("整份配方合计",value.totalNutrition)
        NutritionCard("每100g成品估算",value.per100g)
        Text(value.totalCostYuan?.let {"整份约 ¥${it.money()} · 每100g约 ¥${value.costPer100gYuan!!.money()}"}
            ?: "成本未完整计价：已知部分 ¥${value.knownCostYuan.money()}，${value.missingPriceCount}种食材未计价；生成食品单价保持未知。")
    }
    val outputUnavailable=original?.foodId!=null && (expectedFood==null || expectedFood.isDeleted || expectedFood.unit!="g")
    Row {
        Checkbox(publish,{publish=it},enabled=!busy && !outputUnavailable)
        Text(if(original?.foodId==null) "同时按预览生成一个按g记录的食品" else "同时按预览更新已关联食品",Modifier.padding(top=12.dp))
    }
    if(outputUnavailable) Text("关联食品已删除或单位改变，可只保存配方；恢复并核对食品后再更新。")
    if(original?.foodId!=null) Text("更新仅影响关联食品今后的营养和单价；已记录的饮食及目标快照保持原样。")
    Row {
        Button(onClick={if(publish) confirm=true else draft?.let {onSave(it,false,original,null,onClose)}},enabled=!busy && draft!=null && (!publish || !outputUnavailable)) {Text("保存配方")}
        TextButton(onClick=onClose,enabled=!busy) {Text("取消")}
    }
    if(confirm && draft!=null && summary!=null) AlertDialog(onDismissRequest={if(!busy) confirm=false},
        title={Text(if(original?.foodId==null) "保存配方并生成食品？" else "保存配方并更新关联食品？")},
        text={Text("${draft.name}，每100g ${summary.per100g.kcal.energy(LocalEnergyUnit.current)}。"+
            (summary.costPer100gYuan?.let {"每100g约 ¥${it.money()}。"} ?: "有食材未计价，食品价格保存为未知。")+
            "这不会记录今天的摄入，也不会改写已有饮食、身体或目标。")},
        confirmButton={TextButton(onClick={onSave(draft,true,original,expectedFood) {confirm=false;onClose()}},enabled=!busy) {Text("确认保存")}},
        dismissButton={TextButton(onClick={confirm=false},enabled=!busy) {Text("返回核对")}})
}
