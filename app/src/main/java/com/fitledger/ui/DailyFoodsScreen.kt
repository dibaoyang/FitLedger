package com.fitledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fitledger.data.*
import com.fitledger.domain.BodyField
import com.fitledger.domain.PortionQuantities
import java.time.LocalDate

@Composable fun DailyFoodsScreen(vm: LedgerViewModel, s: LedgerState, busy: Boolean) {
    val day by vm.today.collectAsStateWithLifecycle()
    var page by rememberSaveable {mutableStateOf("FIXED")}
    Choice("安排",page,listOf("FIXED","TOMORROW"),{if(it=="TOMORROW") "未来预设" else "每日固定安排"},enabled=!busy) {page=it}
    if(page=="TOMORROW") {TomorrowFoodPresetScreen(vm,s,day,busy);return}
    var adding by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deletingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var pausingId by rememberSaveable {mutableStateOf<Long?>(null)}
    var removeAutomatic by rememberSaveable {mutableStateOf(false)}
    val foods = s.allFoods.associateBy { it.id }
    val activeFoods = s.foods.filterNot { it.isDeleted }
    val hasBody = s.bodies.any { it.day == day }
    Text("每日固定食品", style = MaterialTheme.typography.titleLarge)
    Text("完成每天称重后，已启用的固定食品会自动加入今日摄入；每项每天只添加一次，也可以按当天体重计算克数。")
    Text("修改、停用或删除固定项不会改写已经添加的摄入。今天删除某次摄入后，也不会自动重新添加；需要时可手动记录。")
    Text("可按每周几、每隔若干天或指定日期安排。同一种食品的多条规则若同日命中，会分别添加并合计展示。")
    OutlinedButton(onClick = { adding = !adding; editingId = null }, enabled = !busy) {
        Text(if (adding) "收起新增固定食品" else "新增固定食品")
    }
    if (adding) DailyFoodEditor(null, activeFoods, day, busy,
        save = { template -> vm.saveDailyFood(template) { adding = false } },
        cancel = { adding = false })
    if (activeFoods.isEmpty()) Text("先在食品库添加食品，再设置每日固定项。")
    if (s.dailyFoods.isEmpty()) Text("尚未设置每日固定食品。")
    s.dailyFoods.forEach { template ->
        val food = foods[template.foodId]
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(food?.name ?: "食品已不可用", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Switch(template.enabled, { vm.saveDailyFood(template.copy(enabled = it)) }, enabled = !busy)
                }
                Text(if (template.mode == "PER_WEIGHT") "每天按体重：${template.quantity.f()} g/kg"
                    else "每天固定：${PortionQuantities.format(template.unit,template.quantity)} ${template.unit}")
                Text("${if (template.enabled) "已启用" else "已停用"} · ${LocalDate.ofEpochDay(template.startDay)} 起")
                Text(scheduleSummary(template.schedule,template.startDay))
                val available = food != null && !food.isDeleted && food.unit == template.unit
                if (!available) Text("原食品已删除、不可用或计量单位已改变，请编辑此项后选择有效食品。", color = MaterialTheme.colorScheme.error)
                if (template.mode == "PER_WEIGHT" && available && s.preferences.visibility.shows(BodyField.WEIGHT)) {
                    s.bodies.find { it.day == day }?.let { body ->
                        val quantity = body.weight * template.quantity
                        if (quantity.isFinite()) Text("按今日体重折合 ${quantity.f()} g")
                    }
                }
                val applied = s.dailyApplications.any { it.day == day && it.templateId == template.id }
                val skipped=s.dailySkips.any {it.day==day && it.templateId==template.id}
                val scheduled=com.fitledger.data.ScheduleCodec.decode(template.schedule).matches(day,template.startDay)
                Text(if(skipped) "今天已暂停，其他日期仍按规则执行。"
                    else if (applied) "今日已处理；本次摄入可在「今日」中修改或删除。"
                    else if (!hasBody) "完成今日称重后自动添加。"
                    else if (template.enabled && scheduled) "今日尚未添加，可使用下方补记按钮。"
                    else "停用期间或起始日期前不自动添加。")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick={if(skipped) vm.skipDailyFood(template.id,false) else {pausingId=template.id;removeAutomatic=false}},enabled=!busy) {Text(if(skipped) "取消今日暂停" else "今天暂停")}
                    TextButton(onClick = { editingId = if (editingId == template.id) null else template.id; adding = false }, enabled = !busy) {
                        Text(if (editingId == template.id) "收起编辑" else "编辑")
                    }
                    TextButton(onClick = { deletingId = template.id }, enabled = !busy) { Text("删除固定项") }
                }
                if (editingId == template.id) DailyFoodEditor(template, activeFoods, day, busy,
                    save = { changed -> vm.saveDailyFood(changed) { editingId = null } }, cancel = { editingId = null })
            }
        }
    }
    OutlinedButton(onClick = { vm.applyDailyFoods() }, enabled = !busy && hasBody) { Text("补记今日未添加的固定食品") }
    if (!hasBody) Text("请先完成今日身体数据录入。")
    pausingId?.let {id->AlertDialog(onDismissRequest={pausingId=null},title={Text("今天暂停这项固定食品？")},text={Column {
        Text("只暂停今天，不改变其他日期的安排。已经记录的摄入默认保留。")
        Row {Checkbox(removeAutomatic,{removeAutomatic=it},enabled=!busy);Text("同时删除今天由这一固定项自动添加的摄入",Modifier.weight(1f).padding(top=12.dp))}
        Text("如果已经吃了，请保留摄入。取消暂停也不会把已经手动删除的摄入重新加回来。")
    }},confirmButton={TextButton(onClick={vm.skipDailyFood(id,true,removeAutomatic);pausingId=null},enabled=!busy) {Text("确认暂停")}},dismissButton={TextButton(onClick={pausingId=null}) {Text("取消")}})}
    deletingId?.let { id ->
        val template = s.dailyFoods.find { it.id == id }
        if (template == null) LaunchedEffect(id) { deletingId = null }
        else AlertDialog(onDismissRequest = { if (!busy) deletingId = null }, title = { Text("删除每日固定项？") },
            text = { Text("将停止以后自动添加“${foods[template.foodId]?.name ?: "这项食品"}”。今天已经添加的摄入和历史记录都会保留；若要删除今日摄入，请前往「今日」单独删除。") },
            confirmButton = { TextButton(onClick = { vm.deleteDailyFood(id); deletingId = null }, enabled = !busy) { Text("确认删除固定项") } },
            dismissButton = { TextButton(onClick = { deletingId = null }, enabled = !busy) { Text("取消") } })
    }
}

@Composable private fun DailyFoodEditor(
    original: DailyFoodTemplate?, foods: List<Food>, day: Long, busy: Boolean,
    save: (DailyFoodTemplate) -> Unit, cancel: () -> Unit
) {
    var foodId by rememberSaveable(original?.id) { mutableStateOf(original?.foodId) }
    var mode by rememberSaveable(original?.id) { mutableStateOf(original?.mode ?: "FIXED") }
    var raw by rememberSaveable(original?.id) { mutableStateOf(original?.quantity?.let(PortionQuantities::inputText) ?: "") }
    var enabled by rememberSaveable(original?.id) { mutableStateOf(original?.enabled ?: true) }
    var scheduleDraft by remember(original?.id) {mutableStateOf<ScheduleDraft?>(ScheduleDraft(original?.startDay ?: day,original?.schedule ?: ""))}
    val food = foods.find { it.id == foodId }
    val modes = if (food?.unit == "g") listOf("FIXED", "PER_WEIGHT") else listOf("FIXED")
    val draft = food?.takeIf {scheduleDraft!=null}?.let {
        DailyFoodTemplate(original?.id ?: 0, it.id, it.unit, mode, raw.toDoubleOrNull() ?: Double.NaN,
            enabled, scheduleDraft!!.startDay,scheduleDraft!!.schedule)
    }
    val error = if (draft == null) "请选择有效食品" else runCatching {
        draft.validate()
        if(original==null || original.quantity!=draft.quantity || original.unit!=draft.unit) PortionQuantities.requireWhole(draft.unit,draft.quantity)
    }.exceptionOrNull()?.message
    Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(if (original == null) "新增固定项" else "编辑固定项", style = MaterialTheme.typography.titleMedium)
        Choice("食品", foodId, listOf<Long?>(null) + foods.map { it.id }, { id -> foods.find { it.id == id }?.name ?: "请选择食品" }, enabled = !busy) { selected ->
            foodId = selected
            if (foods.find { it.id == selected }?.unit != "g") mode = "FIXED"
        }
        Choice("数量方式", mode, modes, { if (it == "PER_WEIGHT") "按当天体重 g/kg" else "固定数量" }, enabled = !busy) { mode = it }
        OutlinedTextField(raw, { raw = it }, modifier = Modifier.fillMaxWidth(), enabled = !busy,
            label = { Text(if (mode == "PER_WEIGHT") "每 kg 体重对应多少 g 食品" else "每天数量 ${food?.unit ?: "g／份"}") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
            isError = raw.isNotBlank() && error != null)
        if (mode == "PER_WEIGHT") Text("仅适用于以 g 计量的食品，例如填写 1，表示每 1 kg 当日体重添加 1 g 食品。")
        if(food?.unit=="份") Text("新份数须为整数。旧的小数份保留原值，自动添加会跳过并提醒修改。")
        EatingScheduleEditor(original?.schedule ?: "",original?.startDay ?: day,busy) {scheduleDraft=it}
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("启用每日自动添加", Modifier.weight(1f).padding(top = 12.dp))
            Switch(enabled, { enabled = it }, enabled = !busy)
        }
        Text("保存后，若今天已称重且这项尚未添加，会补记今天；已添加的数量保持原样。")
        if (raw.isNotBlank() && error != null) Text(error, color = MaterialTheme.colorScheme.error)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { draft?.let(save) }, enabled = !busy && error == null) { Text("保存固定项") }
            TextButton(onClick = cancel, enabled = !busy) { Text("取消") }
        }
    }
}
