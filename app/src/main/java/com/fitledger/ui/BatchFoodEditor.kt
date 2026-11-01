package com.fitledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitledger.data.*

private data class FoodBatchPreview(val expected:List<Food>,val patch:FoodBatchPatch)
private enum class BatchSwitch(val label:String) { KEEP("保持原值"),YES("全部开启"),NO("全部关闭") }

@Composable fun BatchFoodEditor(foods:List<Food>,busy:Boolean,onApply:(List<Food>,FoodBatchPatch,()->Unit)->Unit) {
    var query by rememberSaveable {mutableStateOf("")}
    var favorite by rememberSaveable {mutableStateOf(BatchSwitch.KEEP)}
    var allowed by rememberSaveable {mutableStateOf(BatchSwitch.KEEP)}
    var changeTags by rememberSaveable {mutableStateOf(false)}
    val tags=remember {mutableStateMapOf<String,Boolean>()}
    val selected=remember {mutableStateMapOf<Long,Food>()}
    var pending by remember {mutableStateOf<FoodBatchPreview?>(null)}
    val active=foods.filter {!it.isDeleted}
    val visible=searchFoods(active,query)
    Text("批量修改食品",style=MaterialTheme.typography.titleLarge)
    Text("多选食品，只应用下面明确选择的项目；营养、价格、单位、分区与历史记录保持原样。修改前先预览。")
    OutlinedTextField(query,{query=it},label={Text("搜索要选择的食品")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy)
    Row {
        TextButton(onClick={visible.forEach {selected[it.id]=it}},enabled=!busy && visible.isNotEmpty()) {Text("选择当前搜索结果")}
        TextButton(onClick={selected.clear()},enabled=!busy && selected.isNotEmpty()) {Text("清空选择")}
    }
    visible.forEach {food->Row(Modifier.fillMaxWidth()) {
        Checkbox(food.id in selected,{if(it) selected[food.id]=food else selected.remove(food.id)},enabled=!busy)
        Text("${food.name} · ${food.unit}",Modifier.padding(top=12.dp))
    }}
    Text("已选 ${selected.size} 种食品（包含搜索结果以外的选择）")
    val stale=selected.values.filter {old->active.none {it==old}}
    if(stale.isNotEmpty()) {
        Text("以下食品已修改或删除，请取消后重新选择，未保存任何更改：",color=MaterialTheme.colorScheme.error)
        stale.forEach {food->TextButton(onClick={selected.remove(food.id)},enabled=!busy) {Text("取消选择 ${food.name}")}}
    }
    Choice("收藏",favorite,BatchSwitch.entries.toList(),{it.label},enabled=!busy) {favorite=it}
    Choice("允许智能推荐",allowed,BatchSwitch.entries.toList(),{it.label},enabled=!busy) {allowed=it}
    Row {Checkbox(changeTags,{changeTags=it},enabled=!busy);Text("统一替换营养标签",Modifier.padding(top=12.dp))}
    if(changeTags) {
        Text("勾选新的标签，可多选；全部不选表示清空所选食品的营养标签。")
        for((tag,label) in listOf("CARBS" to "碳水区","PROTEIN" to "蛋白质区","FAT" to "脂肪区")) Row {
            Checkbox(tags[tag]==true,{tags[tag]=it},enabled=!busy);Text(label,Modifier.padding(top=12.dp))
        }
    }
    fun choice(value:BatchSwitch):Boolean?=when(value) {BatchSwitch.KEEP->null;BatchSwitch.YES->true;BatchSwitch.NO->false}
    val patch=FoodBatchPatch(choice(favorite),choice(allowed),if(changeTags) listOf("CARBS","PROTEIN","FAT").filter {tags[it]==true}.joinToString(",") else null)
    val valid=selected.isNotEmpty() && stale.isEmpty() && runCatching {patch.validate()}.isSuccess
    Button(onClick={pending=FoodBatchPreview(selected.values.sortedBy {it.id}.toList(),patch)},enabled=!busy && valid) {Text("预览批量修改")}
    pending?.let {preview->AlertDialog(onDismissRequest={if(!busy) pending=null},title={Text("确认修改 ${preview.expected.size} 种食品？")},
        text={Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            preview.expected.forEach {food->
                val after=preview.patch.applyTo(food)
                Text(food.name,style=MaterialTheme.typography.titleSmall)
                if(preview.patch.favorite!=null) Text("收藏：${if(food.favorite) "是" else "否"} → ${if(after.favorite) "是" else "否"}")
                if(preview.patch.allowed!=null) Text("参与推荐：${if(food.allowed) "是" else "否"} → ${if(after.allowed) "是" else "否"}")
                if(preview.patch.nutritionTags!=null) Text("营养标签：${food.nutritionTags.nutritionLabel()} → ${after.nutritionTags.nutritionLabel()}")
            }
            Text("只保存上述更改，其他食品资料及所有历史摄入不变。任一所选食品已变化时整批取消，请重新预览。")
        }},confirmButton={TextButton(onClick={onApply(preview.expected,preview.patch) {pending=null;selected.clear()}},enabled=!busy) {Text("确认批量保存")}},
        dismissButton={TextButton(onClick={pending=null},enabled=!busy) {Text("返回")}})}
}
