package com.fitledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitledger.data.*
import com.fitledger.domain.*

@Composable fun FoodCombosScreen(vm:LedgerViewModel,s:LedgerState,busy:Boolean) {
    var editingId by rememberSaveable {mutableStateOf<Long?>(null)}
    var adding by rememberSaveable {mutableStateOf(false)}
    var deleting by rememberSaveable {mutableStateOf<Long?>(null)}
    Text("常吃组合",style=MaterialTheme.typography.titleLarge)
    Text("把几种食品保存为一个组合，想吃时一起添加。组合不会每天自动记录，与「每日吃的」分开使用。")
    Button(onClick={adding=true;editingId=null},enabled=!busy && s.foods.isNotEmpty()) {Text("新建常吃组合")}
    if(adding) key("new") {ComboEditor(null,s.foods,busy,{vm.saveCombo(it) {adding=false}},{adding=false})}
    if(s.combos.isEmpty()) Text("还没有组合，先选几种常吃食品保存。")
    s.combos.forEach {combo->key(combo.id) {
        Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Text(combo.name,style=MaterialTheme.typography.titleMedium)
            ComboCodec.decode(combo.items).forEach {item->Text("${s.allFoods.find {it.id==item.foodId}?.name ?: "食品已不可用"} · ${PortionQuantities.format(item.unit,item.quantity)} ${item.unit}")}
            Row {
                TextButton(onClick={editingId=if(editingId==combo.id) null else combo.id;adding=false},enabled=!busy) {Text("编辑组合")}
                TextButton(onClick={deleting=combo.id},enabled=!busy) {Text("删除组合")}
            }
            if(editingId==combo.id) ComboEditor(combo,s.foods,busy,{vm.saveCombo(it) {editingId=null}},{editingId=null})
        }}
    }}
    HorizontalDivider()
    QuickComboRecorder(vm,s,busy)
    s.combos.find {it.id==deleting}?.let {combo->AlertDialog(onDismissRequest={deleting=null},title={Text("删除 ${combo.name}？")},text={Text("只删除组合模板，食品库、今天和历史摄入不变。")},confirmButton={TextButton(onClick={vm.deleteCombo(combo.id);deleting=null},enabled=!busy) {Text("删除")}},dismissButton={TextButton(onClick={deleting=null}) {Text("取消")}})}
}

@Composable private fun ComboEditor(original:FoodCombo?,foods:List<Food>,busy:Boolean,onSave:(FoodCombo)->Unit,onClose:()->Unit) {
    var name by rememberSaveable(original?.id) {mutableStateOf(original?.name ?: "")}
    val items=remember(original?.id) {mutableStateListOf<ComboItem>().apply {original?.let {addAll(ComboCodec.decode(it.items))}}}
    var query by rememberSaveable {mutableStateOf("")}
    var selected by rememberSaveable {mutableStateOf<Long?>(null)}
    var raw by rememberSaveable {mutableStateOf("")}
    var ratioRules by rememberSaveable(original?.id) {mutableStateOf(original?.ratioRules?:"")}
    var ratioRevision by remember {mutableIntStateOf(0)}
    val available=searchFoods(foods,query)
    val food=available.find {it.id==selected} ?: available.firstOrNull()
    val amount=raw.toDoubleOrNull()
    OutlinedTextField(name,{name=it},label={Text("组合名称")},modifier=Modifier.fillMaxWidth(),enabled=!busy)
    OutlinedTextField(query,{query=it},label={Text("搜索要加入的食品")},modifier=Modifier.fillMaxWidth(),enabled=!busy)
    if(available.isNotEmpty()) {
        Choice("选择食品",food!!.id,available.map {it.id},{id->available.first {it.id==id}.name},enabled=!busy) {selected=it}
        NumberField("加入数量 ${food.unit}",raw,enabled=!busy) {raw=it}
        if(food.unit=="份") Text("份数请填写整数。")
        Button(onClick={items.add(ComboItem(food.id,food.unit,amount!!));raw=""},enabled=!busy && amount!=null && PortionQuantities.isWhole(food.unit,amount) && items.none {it.foodId==food.id} && items.size<50) {Text("加入组合")}
        if(items.any {it.foodId==food.id}) Text("该食品已经在组合里，可直接修改下方数量。")
    }
    items.toList().forEach {item->key(item.foodId) {
        var quantity by rememberSaveable(item.foodId,ratioRevision) {mutableStateOf(PortionQuantities.inputText(item.quantity))}
        val present=foods.find {it.id==item.foodId && it.unit==item.unit}
        Text(present?.name ?: "原食品已删除或单位改变，请移除此项后重新选择",color=if(present==null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        NumberField("组合数量 ${item.unit}",quantity,enabled=!busy) {value->quantity=value;val i=items.indexOfFirst {it.foodId==item.foodId};if(i>=0) items[i]=item.copy(quantity=value.toDoubleOrNull() ?: Double.NaN)}
        TextButton(onClick={
            items.removeAll {it.foodId==item.foodId}
            ratioRules=WeightRatios.encode(WeightRatios.decode(ratioRules).filterNot {it.foodId==item.foodId || it.referenceFoodId==item.foodId})
        },enabled=!busy) {Text("移出组合")}
    }}
    WeightRatioEditor(foods.filter {f->items.any {it.foodId==f.id}},ratioRules,busy) {ratioRules=it}
    val quantities=items.associate {it.foodId to it.quantity}
    val ratioPreview=runCatching {WeightRatios.round(quantities,WeightRatios.decode(ratioRules),foodRatioInputs(foods),enforceMaximum=false)}
    val exact=runCatching {WeightRatios.deviations(quantities,WeightRatios.decode(ratioRules),foodRatioInputs(foods),enforceMaximum=false)}
    ratioPreview.getOrNull()?.let {preview->
        RatioDeviationRows(foods,preview.deviations)
        if(exact.isFailure) Button(onClick={
            for(i in items.indices) items[i]=items[i].copy(quantity=preview.quantities.getValue(items[i].foodId))
            ratioRevision++
        },enabled=!busy) {Text("采用按比例取整后的数量")}
    }
    ratioPreview.exceptionOrNull()?.message?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    val valid=name.trim().isNotBlank() && name.trim().length<=100 && items.isNotEmpty() && items.all {item->PortionQuantities.isWhole(item.unit,item.quantity) && foods.any {it.id==item.foodId && it.unit==item.unit}}
    if(items.any {it.unit=="份" && !PortionQuantities.isWhole(it.unit,it.quantity)}) Text("组合里有旧的小数份，请按本次实际数量改成整数后保存；原模板尚未更改。",color=MaterialTheme.colorScheme.error)
    Row {
        Button(onClick={onSave(FoodCombo(original?.id ?: 0,name.trim(),ComboCodec.encode(items.toList()),ratioRules))},enabled=!busy && valid && exact.isSuccess) {Text("保存组合")}
        TextButton(onClick=onClose,enabled=!busy) {Text("取消")}
    }
}

@Composable fun QuickComboRecorder(vm:LedgerViewModel,s:LedgerState,busy:Boolean) {
    if(s.combos.isEmpty()) return
    var expanded by rememberSaveable {mutableStateOf(false)}
    var selected by rememberSaveable {mutableStateOf<Long?>(null)}
    TextButton(onClick={expanded=!expanded},enabled=!busy) {Text(if(expanded) "常吃组合 ▾" else "从常吃组合添加 ▸")}
    if(!expanded) return
    val combo=s.combos.find {it.id==selected} ?: s.combos.first()
    Choice("组合",combo.id,s.combos.map {it.id},{id->s.combos.first {it.id==id}.name},enabled=!busy) {selected=it}
    key(combo.id,combo.items,combo.ratioRules) {ComboAdoptionForm(vm,combo,s,busy)}
}

@Composable private fun ComboAdoptionForm(vm:LedgerViewModel,combo:FoodCombo,s:LedgerState,busy:Boolean) {
    val items=remember(combo.items) {ComboCodec.decode(combo.items)}
    val amounts=remember(combo.items) {mutableStateMapOf<Long,String>().apply {items.forEach {put(it.foodId,PortionQuantities.inputText(it.quantity))}}}
    val picked=remember(combo.items) {mutableStateMapOf<Long,Boolean>().apply {items.forEach {put(it.foodId,true)}}}
    var confirm by remember {mutableStateOf(false)}
    val day=vm.today.collectAsState().value
    items.forEach {item->
        val food=s.foods.find {it.id==item.foodId && it.unit==item.unit}
        Row {Checkbox(picked[item.foodId]==true,{picked[item.foodId]=it},enabled=!busy);Text(food?.name ?: "食品已删除或单位改变",Modifier.padding(top=12.dp))}
        if(picked[item.foodId]==true) {
            NumberField("本次 ${item.unit}",amounts[item.foodId].orEmpty(),enabled=!busy) {amounts[item.foodId]=it}
            if(food==null) Text("请先恢复食品或编辑组合后再添加。",color=MaterialTheme.colorScheme.error)
        }
    }
    val quantities=items.filter {picked[it.foodId]==true}.associate {it.foodId to (amounts[it.foodId]?.toDoubleOrNull() ?: Double.NaN)}
    val ratioPreview=runCatching {WeightRatios.round(quantities,WeightRatios.decode(combo.ratioRules),foodRatioInputs(s.foods),enforceMaximum=false)}
    val exactRatios=runCatching {WeightRatios.deviations(quantities,WeightRatios.decode(combo.ratioRules),foodRatioInputs(s.foods),enforceMaximum=false)}
    runCatching {WeightRatios.describeActual(quantities,WeightRatios.decode(combo.ratioRules),foodRatioInputs(s.foods))}.getOrNull()?.let {
        if(it.isNotEmpty()) Text("本次实际数量的比例偏差")
        RatioDeviationRows(s.foods,it)
    }
    ratioPreview.getOrNull()?.let {result->
        if(exactRatios.isFailure && result.deviations.isNotEmpty()) {
            Text("可选的按比例取整预览（尚未采用）")
            RatioDeviationRows(s.foods,result.deviations)
            Button(onClick={result.quantities.forEach {(id,q)->picked[id]=true;amounts[id]=PortionQuantities.inputText(q)}},enabled=!busy) {Text("采用按比例取整后的数量")}
        }
    }
    ratioPreview.exceptionOrNull()?.message?.let {Text("可选的比例预览暂不可用：$it。仍可按实际数量记录。",color=MaterialTheme.colorScheme.tertiary)}
    val preview=runCatching {
        require(quantities.isNotEmpty())
        var n=Nutrition();var cost=0.0;var missing=false
        quantities.forEach {(id,q)->
            require(q.isFinite() && q>0)
            val item=items.first {it.foodId==id};val food=s.foods.first {it.id==id && it.unit==item.unit}
            PortionQuantities.requireWhole(item.unit,q)
            n+=food.nutrition*(q/food.basisAmount)
            val price=FoodPricing.cost(food.pricePerUnitYuan,q);if(price==null) missing=true else cost+=price
        }
        require(n.values().all {it.isFinite() && it>=0} && cost.isFinite())
        Triple(n,cost,missing)
    }.getOrNull()
    if(quantities.any {(id,q)->items.find {it.foodId==id}?.let {!PortionQuantities.isWhole(it.unit,q)}==true}) Text("本次份数须为整数。旧模板中的小数份保留，请核对实际数量后修改。",color=MaterialTheme.colorScheme.error)
    preview?.let {
        NutritionCard("本次组合预览",it.first)
        Text("预计已知费用 ¥${it.second.money()}"+if(it.third) " · 含未计价食品" else "")
    }
    val duplicates=s.intakes.filter {it.day==day && it.foodId in quantities}.map {it.name}.distinct()
    if(duplicates.isNotEmpty()) Text("今天已记过：${duplicates.joinToString("、")}。请核对这次是否确实还要吃，避免与每日固定项重复。")
    Text("本次可按实际食用量临时改量或不选某项，不受模板比例限制。可选择按比例取整，也可如实记录当前数量；不改变组合模板。")
    Button(onClick={confirm=true},enabled=!busy && preview!=null && s.bodies.any {it.day==day} && s.targets.any {it.day==day}) {Text("添加本次组合到今天")}
    if(confirm) AlertDialog(onDismissRequest={confirm=false},title={Text("添加 ${combo.name}？")},text={Text("将按当前实际数量新增 ${quantities.size} 项摄入；已有记录和组合模板不会改变。"+(if(duplicates.isNotEmpty()) "其中有今天已经记过的食品，请确认不是重复记录。" else ""))},confirmButton={TextButton(onClick={vm.recordCombo(combo.id,quantities);confirm=false},enabled=!busy && preview!=null) {Text("确认添加")}},dismissButton={TextButton(onClick={confirm=false}) {Text("取消")}})
}
