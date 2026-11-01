package com.fitledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitledger.data.*
import java.time.LocalDate

@Composable fun RecipeBatchesSection(recipes:List<FoodRecipe>,batches:List<RecipeBatch>,foods:List<Food>,busy:Boolean,
    onSave:(RecipeBatch,FoodRecipe,Boolean,()->Unit)->Unit,onDelete:(RecipeBatch)->Unit) {
    var selectedId by rememberSaveable {mutableStateOf<Long?>(null)}
    var adding by rememberSaveable {mutableStateOf(false)}
    var deleting by remember {mutableStateOf<RecipeBatch?>(null)}
    Text("配方批次与分装",style=MaterialTheme.typography.titleLarge)
    Text("每次制作单独保存食材、成品重量、营养、成本和每盒重量。以后修改或删除原配方，不会重算这次制作的数据。")
    OutlinedButton(onClick={adding=!adding},enabled=!busy && recipes.isNotEmpty()) {Text(if(adding) "收起新增批次" else "记录一次制作")}
    if(recipes.isEmpty()) Text("先保存一个自制配方，再记录本次制作与分装。")
    if(adding && recipes.isNotEmpty()) {
        val recipe=recipes.find {it.id==selectedId}?:recipes.first()
        Choice("参考配方",recipe.id,recipes.map {it.id},{id->recipes.first {it.id==id}.name},enabled=!busy) {selectedId=it}
        key(recipe) {BatchEditor(recipe,busy,onSave) {adding=false}}
    }
    batches.sortedWith(compareByDescending<RecipeBatch> {it.createdDay}.thenByDescending {it.id}).forEach {batch->key(batch.id) {
        val summary=batch.summary()
        Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(batch.name,style=MaterialTheme.typography.titleMedium)
            Text("${LocalDate.ofEpochDay(batch.createdDay)} · 成品 ${batch.finishedWeightG.f()}g · 独立快照")
            NutritionCard("本批每100g",summary.per100g)
            Text(summary.totalCostYuan?.let {"整批约 ¥${it.money()} · 每100g约 ¥${summary.costPer100gYuan!!.money()}"}
                ?: "整批费用尚不完整：${summary.missingPriceCount}种食材未计价")
            val weights=RecipeBoxes.decode(batch.boxWeights,batch.finishedWeightG)
            if(weights.isEmpty()) Text("尚未分盒，可按实际吃掉克数记录。")
            weights.forEachIndexed {index,grams->
                val n=batch.nutrition*(grams/batch.finishedWeightG)
                Text("第${index+1}盒 · ${grams.f()}g · ${n.kcal.energy(LocalEnergyUnit.current)} · 碳${n.carbs.f()}／蛋${n.protein.f()}／脂${n.fat.f()}g"+
                    (batch.costYuan?.let {" · 约 ¥${(it*grams/batch.finishedWeightG).money()}"}?:" · 未完整计价"))
            }
            batch.foodId?.let {id->
                val linked=foods.find {it.id==id}
                Text(if(linked==null || linked.isDeleted) "独立批次食品已删除；历史和本批快照保留，可在食品回收站恢复。"
                    else "已生成独立食品「${linked.name}」。在今日选择该食品，按吃掉的实际克数记录。")
                if(linked!=null && (linked.unit!="g" || linked.basisAmount!=100.0 || linked.nutrition!=summary.per100g || linked.pricePerUnitYuan!=summary.pricePerGramYuan))
                    Text("关联食品资料后来有修改。这里仍显示原批次快照，记录食品时将使用你修改后的资料。",color=MaterialTheme.colorScheme.tertiary)
            }?:Text("本批只保存制作快照，尚未生成食品库条目。")
            TextButton(onClick={deleting=batch},enabled=!busy) {Text("删除本批快照")}
        }}
    }}
    deleting?.let {batch->AlertDialog(onDismissRequest={if(!busy) deleting=null},title={Text("删除这次制作快照？")},
        text={Text("只删除「${batch.name}」的批次资料。已经生成的独立食品和所有饮食记录都会保留。")},
        confirmButton={TextButton(onClick={onDelete(batch);deleting=null},enabled=!busy) {Text("删除批次")}},
        dismissButton={TextButton(onClick={deleting=null},enabled=!busy) {Text("取消")}})}
}

@Composable private fun BatchEditor(recipe:FoodRecipe,busy:Boolean,onSave:(RecipeBatch,FoodRecipe,Boolean,()->Unit)->Unit,onClose:()->Unit) {
    val today=LocalDate.now().toEpochDay()
    var name by rememberSaveable {mutableStateOf("${recipe.name} · ${LocalDate.ofEpochDay(today)}")}
    var yield by rememberSaveable {mutableStateOf(recipe.finishedWeightG.toString())}
    var boxes by rememberSaveable {mutableStateOf("")}
    var equalCount by rememberSaveable {mutableStateOf("")}
    var publish by rememberSaveable {mutableStateOf(true)}
    var confirming by remember {mutableStateOf<RecipeBatch?>(null)}
    OutlinedTextField(name,{name=it},label={Text("本批名称")},modifier=Modifier.fillMaxWidth(),enabled=!busy)
    NumberField("这次实称成品总重量 g",yield,enabled=!busy) {yield=it}
    Text("食材沿用所选配方里已保存的快照。食材或用量不同，请先编辑保存配方，再建立本批；不会按当前食品价格悄悄重算。")
    NumberField("均分盒数（整数，可留空）",equalCount,enabled=!busy) {equalCount=it}
    val grams=yield.toDoubleOrNull();val count=equalCount.toIntOrNull()
    OutlinedButton(onClick={if(grams!=null && count!=null) boxes=List(count) {grams/count}.joinToString(",")},
        enabled=!busy && grams!=null && grams.isFinite() && grams>0 && count!=null && count in 1..1000) {Text("按盒数均分重量")}
    OutlinedTextField(boxes,{boxes=it},label={Text("各盒实重 g，用逗号分隔（未分装可留空）")},modifier=Modifier.fillMaxWidth(),enabled=!busy)
    Text("例如300,280,320。合计须等于本批总重；未装盒的剩余也单列一项。均分只是预填，可改成实称重量。")
    val draft=runCatching {
        val weight=requireNotNull(grams) {"请填写本批成品重量"}
        val parts=if(boxes.isBlank()) emptyList() else boxes.replace('，',',').split(',').map {requireNotNull(it.trim().toDoubleOrNull()) {"各盒重量格式无效"}}
        val summary=RecipeCalculator.calculate(RecipeIngredients.decode(recipe.ingredients),weight)
        RecipeBatch(recipeId=recipe.id,name=name.trim(),createdDay=today,ingredients=recipe.ingredients,finishedWeightG=weight,
            boxWeights=RecipeBoxes.encode(parts,weight),nutrition=summary.totalNutrition,costYuan=summary.totalCostYuan).also {it.validate()}
    }
    draft.getOrNull()?.let {NutritionCard("本批每100g预览",it.summary().per100g)}
    draft.exceptionOrNull()?.message?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    Row {Checkbox(publish,{publish=it},enabled=!busy);Text("为本批生成一个独立的按g食品",Modifier.padding(top=12.dp))}
    Text("独立食品方便在今日记录这批成品，不覆盖上次制作的食品，也不会记录任何摄入。")
    Row {
        Button(onClick={confirming=draft.getOrNull()},enabled=!busy && draft.isSuccess) {Text("核对并保存本批")}
        TextButton(onClick=onClose,enabled=!busy) {Text("取消")}
    }
    confirming?.let {batch->AlertDialog(onDismissRequest={if(!busy) confirming=null},title={Text("保存本次制作？")},
        text={Text("${batch.name}：成品${batch.finishedWeightG.f()}g，共${RecipeBoxes.decode(batch.boxWeights,batch.finishedWeightG).size}个分装。"+
            (if(publish) "将新增一个独立食品。" else "只保存批次快照。")+"保存后本批快照不随配方变化；已有食品、目标和摄入不变。")},
        confirmButton={TextButton(onClick={onSave(batch,recipe,publish) {confirming=null;onClose()}},enabled=!busy) {Text("确认保存")}},
        dismissButton={TextButton(onClick={confirming=null},enabled=!busy) {Text("返回核对")}})}
}
