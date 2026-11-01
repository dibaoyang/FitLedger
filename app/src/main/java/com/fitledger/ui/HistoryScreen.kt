package com.fitledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fitledger.data.*
import com.fitledger.domain.*
import java.time.LocalDate
import java.time.YearMonth

@Composable fun HistoryScreen(vm:LedgerViewModel,s:LedgerState,busy:Boolean,initialDay:Long?=null) {
    val today by vm.today.collectAsStateWithLifecycle()
    var day by rememberSaveable(initialDay) {mutableLongStateOf(initialDay?.coerceAtMost(today) ?: today-1)}
    var dateText by rememberSaveable {mutableStateOf(LocalDate.ofEpochDay(day).toString())}
    var monthText by rememberSaveable {mutableStateOf(YearMonth.from(LocalDate.ofEpochDay(day)).toString())}
    LaunchedEffect(today) {if(day>today) day=today}
    LaunchedEffect(day) {dateText=LocalDate.ofEpochDay(day).toString();monthText=YearMonth.from(LocalDate.ofEpochDay(day)).toString()}
    Text("历史日历",style=MaterialTheme.typography.titleLarge)
    Text("身体与目标保留原快照，只读。可在已有身体记录与目标的日期补改饮食和运动；所有修改均保留当日目标。")
    OutlinedTextField(dateText,{dateText=it},label={Text("日期 yyyy-MM-dd")},singleLine=true,modifier=Modifier.fillMaxWidth())
    val inputDay=runCatching {
        require(dateText.trim().matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
        LocalDate.parse(dateText.trim()).toEpochDay()
    }.getOrNull()
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick={day--}) {Text("前一天")}
        Button(onClick={inputDay?.let {day=it}},enabled=inputDay!=null && inputDay<=today) {Text("查看日期")}
        OutlinedButton(onClick={day++},enabled=day<today) {Text("后一天")}
    }
    if(inputDay!=null && inputDay>today) Text("未来日期不能查看或补写。",color=MaterialTheme.colorScheme.error)
    else if(dateText.isNotBlank() && inputDay==null) Text("请输入有效的 yyyy-MM-dd 日期。")
    val month=YearMonth.parse(monthText)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        TextButton(onClick={monthText=month.minusMonths(1).toString()}) {Text("‹ 上月")}
        Text("${month.year} 年 ${month.monthValue} 月",Modifier.padding(top=12.dp))
        TextButton(onClick={monthText=month.plusMonths(1).toString()},enabled=month<YearMonth.from(LocalDate.ofEpochDay(today))) {Text("下月 ›")}
    }
    Row(Modifier.fillMaxWidth()) {listOf("一","二","三","四","五","六","日").forEach {Text(it,Modifier.weight(1f).padding(8.dp))}}
    val firstOffset=month.atDay(1).dayOfWeek.value-1
    val occupied=remember(s.bodies,s.intakes,s.exercises) {(s.bodies.map {it.day}+s.intakes.map {it.day}+s.exercises.map {it.day}).toSet()}
    repeat((firstOffset+month.lengthOfMonth()+6)/7) {week->
        Row(Modifier.fillMaxWidth()) {
            repeat(7) {weekday->
                val number=week*7+weekday-firstOffset+1
                if(number in 1..month.lengthOfMonth()) {
                    val candidate=month.atDay(number).toEpochDay()
                    TextButton(onClick={day=candidate},enabled=candidate<=today,
                        contentPadding=PaddingValues(horizontal=0.dp,vertical=6.dp),modifier=Modifier.weight(1f)) {
                        Text("${if(candidate==day) "[$number]" else number.toString()}${if(candidate in occupied) "\n·" else "\n "}")
                    }
                } else Spacer(Modifier.weight(1f))
            }
        }
    }
    Text("· 有记录   [ ] 当前所选日期",style=MaterialTheme.typography.bodySmall)
    HorizontalDivider()
    Text(LocalDate.ofEpochDay(day).toString(),style=MaterialTheme.typography.titleLarge)
    key(day) {HistoryDay(vm,s,day,today,busy)}
}

@Composable private fun HistoryDay(vm:LedgerViewModel,s:LedgerState,day:Long,today:Long,busy:Boolean) {
    val body=s.bodies.find {it.day==day}
    val target=s.targets.find {it.day==day}
    val writable=day<=today && body!=null && target!=null
    if(!writable) Text(when {
        day>today -> "未来日期禁止写入。"
        body==null && target==null -> "这天没有身体与目标快照，只能查看已有记录，不能补写或修改。"
        body==null -> "这天没有身体快照，不能用其他日期体重补写；已有记录只读。"
        else -> "这天没有目标快照，不能按当前计划伪造历史目标；已有记录只读。"
    },color=MaterialTheme.colorScheme.error)
    var bodyExpanded by rememberSaveable(day) {mutableStateOf(false)}
    OutlinedButton(onClick={bodyExpanded=!bodyExpanded},modifier=Modifier.fillMaxWidth()) {Text(if(bodyExpanded) "收起当日身体快照" else "查看当日身体快照（只读）")}
    if(bodyExpanded && body!=null) Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        BodyValue(BodyField.WEIGHT,"${body.weight.f2()} kg")
        BodyValue(BodyField.BODY_FAT,"${body.fatPercent.f2()} %")
        BodyValue(BodyField.LEAN,"${body.lean.f2()} kg")
        BodyValue(BodyField.MUSCLE,body.muscle?.let {"${it.f2()} kg"} ?: "未记录")
        body.energy?.let {energy->
            BodyValue(BodyField.GENDER,runCatching {Sex.valueOf(energy.sex).label}.getOrDefault("未记录"))
            BodyValue(BodyField.HEIGHT,"${energy.heightCm.f2()} cm")
            BodyValue(BodyField.BIRTHDAY,LocalDate.ofEpochDay(energy.birthday).toString())
            BodyValue(BodyField.AGE,"${energy.age} 岁")
            BodyValue(BodyField.BMR,energy.bmrKcal.energy(LocalEnergyUnit.current))
        } ?: Text("此日没有保存能量资料快照；不使用当前资料补算。")
    }}
    if(bodyExpanded && body==null) Text("当日无身体记录。")
    val entries=s.intakes.filter {it.day==day}.sortedByDescending {it.id}
    val consumed=entries.fold(Nutrition()) {a,b->a+b.nutrition}
    target?.let {NutritionCard("当日目标 · 原快照",it.nutrition)} ?: Text("当日没有目标快照。")
    RecordedNutritionCard("当日已吃",entries)
    target?.let {RecordedNutritionCard("当日剩余 · 负数表示超出",entries,it.nutrition)}
    val extra=extraEnergy(s,day)
    val total=totalEnergy(s,day)
    Text("当日计入额外运动：${extra.energy(LocalEnergyUnit.current)}")
    Text(total?.let {"估算总消耗 ${it.energy(LocalEnergyUnit.current)} · 缺口 ${(it-consumed.kcal).energy(LocalEnergyUnit.current)}"}
        ?: "无基础消耗快照；额外运动与已吃热量仍按各自记录显示。")
    val unknown=entries.count {it.costYuan==null}
    Text("当日已知费用 ¥${entries.sumOf {it.costYuan ?: 0.0}.money()}${if(unknown>0) " · $unknown 条未计价（总费用不完整）" else ""}")
    if(entries.isEmpty()) Text("当日无摄入记录；无记录不代表费用为 0。")
    if(writable) HistoryAddIntake(vm,s,day,busy)
    if(writable) EnergyOnlyEditor(vm,day,busy,historical=true)
    HistoryIntakeList(vm,entries,day,busy,writable)
    if(day<today && entries.isNotEmpty()) HistoryCopy(vm,s,entries,day,today,busy)
    HistoryExercises(vm,s,day,busy,writable)
}

@Composable private fun HistoryAddIntake(vm:LedgerViewModel,s:LedgerState,day:Long,busy:Boolean) {
    var expanded by rememberSaveable(day) {mutableStateOf(false)}
    var direct by rememberSaveable(day) {mutableStateOf(false)}
    var foodId by rememberSaveable(day) {mutableStateOf<Long?>(null)}
    var query by rememberSaveable(day) {mutableStateOf("")}
    var carbs by rememberSaveable(day) {mutableStateOf("")}
    var protein by rememberSaveable(day) {mutableStateOf("")}
    var fat by rememberSaveable(day) {mutableStateOf("")}
    OutlinedButton(onClick={expanded=!expanded},modifier=Modifier.fillMaxWidth()) {Text(if(expanded) "收起补记饮食" else "补记这天饮食")}
    if(expanded) {
        Choice("记录方式",direct,listOf(false,true),{if(it) "直接填写营养" else "选择食品"},!busy) {direct=it}
        if(direct) {
            NumberField("碳水 g",carbs,enabled=!busy) {carbs=it}
            NumberField("蛋白质 g",protein,enabled=!busy) {protein=it}
            NumberField("脂肪 g",fat,enabled=!busy) {fat=it}
            val nutrition=runCatching {DirectNutrition.parse(carbs,protein,fat)}.getOrNull()
            nutrition?.let {Text("本次 ${it.kcal.energy(LocalEnergyUnit.current)}；直接营养没有食品价格。")}
            Button(onClick={nutrition?.let {vm.historyRecordDirect(day,it.carbs,it.protein,it.fat)}},enabled=!busy && nutrition!=null) {Text("补记到 ${LocalDate.ofEpochDay(day)}")}
        } else {
            OutlinedTextField(query,{query=it},singleLine=true,label={Text("搜索食品")},modifier=Modifier.fillMaxWidth())
            val foods=searchFoods(s.foods,query)
            val food=foods.find {it.id==foodId} ?: foods.firstOrNull()
            if(food==null) Text("没有匹配的在库食品。") else {
                var amount by rememberSaveable(day,food.id,food.unit) {mutableStateOf("")}
                Choice("食品",food,foods,{it.name},!busy) {foodId=it.id;amount=""}
                NumberField("数量（${food.unit}）",amount,enabled=!busy) {amount=it}
                val quick=QuickAmounts.decode(food.quickAmounts).ifEmpty {listOf(food.candidate().step,food.basisAmount).distinct()}.filter {PortionQuantities.isWhole(food.unit,it)}
                quick.chunked(3).forEach {row->Row(horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    row.forEach {q->OutlinedButton(onClick={amount=PortionQuantities.format("g",q)},enabled=!busy) {Text("${PortionQuantities.format(food.unit,q)} ${food.unit}")}}
                }}
                val quantity=amount.positiveNumber()?.takeIf {q->
                    PortionQuantities.isWhole(food.unit,q) && (food.nutrition*(q/food.basisAmount)).values().all {it.isFinite()} &&
                        food.pricePerUnitYuan?.let {(it*q).isFinite()}!=false
                }
                quantity?.let {q->Text("${(food.nutrition*(q/food.basisAmount)).kcal.energy(LocalEnergyUnit.current)} · ${food.pricePerUnitYuan?.let {"¥${(it*q).money()}"} ?: "未计价"}")}
                Text("补记的新条目使用当前食品库营养和价格；此日原有摄入不变。")
                Button(onClick={quantity?.let {vm.historyRecordFood(day,food.id,it)}},enabled=!busy && quantity!=null) {Text("补记到 ${LocalDate.ofEpochDay(day)}")}
            }
        }
    }
}

@Composable private fun HistoryIntakeList(vm:LedgerViewModel,entries:List<Intake>,day:Long,busy:Boolean,writable:Boolean) {
    var edit by remember(day) {mutableStateOf<Intake?>(null)}
    var deleting by remember(day) {mutableStateOf<Intake?>(null)}
    Text("当日摄入明细",style=MaterialTheme.typography.titleMedium)
    entries.forEach {entry->key(entry.id) {Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        Text(entry.name,style=MaterialTheme.typography.titleMedium)
        Text(if(entry.source=="DIRECT") "直接营养" else "${PortionQuantities.format(entry.unit,entry.quantity)} ${entry.unit}${if(entry.source=="ROUTINE") " · 固定食品" else ""}")
        Text("${entry.nutrition.kcal.energy(LocalEnergyUnit.current)} · ${entry.costYuan?.let {"¥${it.money()}"} ?: "未计价"}")
        Text(if(entry.macrosKnown) "碳水 ${entry.nutrition.carbs.f()} g · 蛋白质 ${entry.nutrition.protein.f()} g · 脂肪 ${entry.nutrition.fat.f()} g" else "仅记录热量；碳水、蛋白质、脂肪未知")
        if(writable && !entry.macrosKnown) EnergyRecordActions(vm,entry,busy)
        if(writable) Row {
            if(entry.macrosKnown) TextButton(onClick={edit=entry},enabled=!busy) {Text("修改此次")}
            TextButton(onClick={deleting=entry},enabled=!busy) {Text("删除此次")}
        }
    }}}}
    edit?.let {entry->HistoryIntakeEditor(entry,day,busy,{edit=null}) {q,n->
        if(n!=null) vm.historyRecordDirect(day,n.carbs,n.protein,n.fat,entry.id) else vm.historyEditQuantity(day,entry.id,q!!)
        edit=null
    }}
    deleting?.let {entry->AlertDialog(onDismissRequest={if(!busy) deleting=null},title={Text("删除这次摄入？")},
        text={Text("${LocalDate.ofEpochDay(day)} · ${entry.name}。身体与目标快照保留。删除后可使用撤销恢复这条原记录。")},
        confirmButton={TextButton(onClick={vm.historyRemoveIntake(day,entry.id);deleting=null},enabled=!busy) {Text("确认删除")}},
        dismissButton={TextButton(onClick={deleting=null},enabled=!busy) {Text("取消")}})}
}

@Composable private fun HistoryIntakeEditor(entry:Intake,day:Long,busy:Boolean,dismiss:()->Unit,save:(Double?,Nutrition?)->Unit) {
    var quantity by rememberSaveable(entry.id) {mutableStateOf(entry.quantity.toString())}
    var carbs by rememberSaveable(entry.id) {mutableStateOf(entry.nutrition.carbs.toString())}
    var protein by rememberSaveable(entry.id) {mutableStateOf(entry.nutrition.protein.toString())}
    var fat by rememberSaveable(entry.id) {mutableStateOf(entry.nutrition.fat.toString())}
    val direct=entry.source=="DIRECT"
    val q=quantity.positiveNumber()
    val n=if(direct) runCatching {DirectNutrition.parse(carbs,protein,fat)}.getOrNull()
        else q?.let {entry.nutrition*(it/entry.quantity)}?.takeIf {nutrition->nutrition.values().all {it.isFinite()}}
    val costValid=direct || q==null || entry.costYuan?.let {(it*q/entry.quantity).isFinite()}!=false
    AlertDialog(onDismissRequest={if(!busy) dismiss()},title={Text("修改 ${entry.name}")},text={Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(LocalDate.ofEpochDay(day).toString())
        if(direct) {
            NumberField("碳水 g",carbs,enabled=!busy) {carbs=it}
            NumberField("蛋白质 g",protein,enabled=!busy) {protein=it}
            NumberField("脂肪 g",fat,enabled=!busy) {fat=it}
        } else {
            NumberField("此次数量 ${entry.unit}",quantity,enabled=!busy) {quantity=it}
            Text("按这次原营养与费用同比调整，保留原食品快照。")
            q?.let {amount->Text(entry.costYuan?.let {"费用 ¥${(it*amount/entry.quantity).money()}"} ?: "费用仍为未计价")}
        }
        n?.let {Text("修改后 ${it.kcal.energy(LocalEnergyUnit.current)}")}
        Text("当日身体与目标快照保留。")
    }},confirmButton={TextButton(onClick={save(if(direct) null else q,if(direct) n else null)},enabled=!busy && n!=null && costValid) {Text("保存修改")}},
        dismissButton={TextButton(onClick=dismiss,enabled=!busy) {Text("取消")}})
}

@Composable private fun HistoryCopy(vm:LedgerViewModel,s:LedgerState,entries:List<Intake>,day:Long,today:Long,busy:Boolean) {
    var expanded by rememberSaveable(day) {mutableStateOf(false)}
    var selected by remember(day) {mutableStateOf(setOf<Long>())}
    val amounts=remember(day) {mutableStateMapOf<Long,String>()}
    LaunchedEffect(entries) {entries.forEach {if(it.id !in amounts) amounts[it.id]=if(it.source in setOf("DIRECT","ENERGY")) "1" else it.quantity.toString()}}
    var confirm by rememberSaveable(day) {mutableStateOf(false)}
    val sourceReady=s.bodies.any {it.day==day} && s.targets.any {it.day==day}
    val destinationReady=s.bodies.any {it.day==today} && s.targets.any {it.day==today}
    val current=s.foods.associateBy {it.id}
    fun issue(entry:Intake):String? {
        if(entry.source in setOf("DIRECT","ENERGY")) return null
        val food=current[entry.foodId] ?: return "当前食品已删除或不可用，请先在食品库恢复，或手动补记。"
        return if(food.unit!=entry.unit) "食品单位已改变，不能自动换算，请恢复原单位或手动补记。" else null
    }
    val chosen=entries.filter {it.id in selected}
    val validAmounts=chosen.isNotEmpty() && chosen.all {issue(it)==null && amounts[it.id]?.positiveNumber()!=null}
    val map=if(validAmounts) chosen.associate {it.id to amounts.getValue(it.id).positiveNumber()!!} else emptyMap()
    val nutrition=if(validAmounts) chosen.fold(Nutrition()) {sum,entry->
        val q=map.getValue(entry.id)
        sum+if(entry.source in setOf("DIRECT","ENERGY")) entry.nutrition*q else current.getValue(entry.foodId!!).let {it.nutrition*(q/it.basisAmount)}
    } else null
    val knownCost=if(validAmounts) chosen.sumOf {entry->if(entry.source in setOf("DIRECT","ENERGY")) (entry.costYuan ?: 0.0)*map.getValue(entry.id) else (current.getValue(entry.foodId!!).pricePerUnitYuan ?: 0.0)*map.getValue(entry.id)} else 0.0
    val unknown=if(validAmounts) chosen.count {if(it.source in setOf("DIRECT","ENERGY")) it.costYuan==null else current[it.foodId]?.pricePerUnitYuan==null} else 0
    val valid=validAmounts && nutrition?.values()?.all {it.isFinite()}==true && knownCost.isFinite()
    OutlinedButton(onClick={expanded=!expanded},modifier=Modifier.fillMaxWidth()) {Text(if(expanded) "收起复制到今天" else "选择本日摄入复制到今天")}
    if(expanded) {
        Text("只复制勾选的条目，可逐条改量。新食品摄入使用当前食品库营养与价格；固定食品复制为普通食品，旧记录保留。直接营养按原三宏量倍数复制。")
        if(!sourceReady) Text("所选日期缺少身体或目标快照，已有摄入只读，暂不能复制。",color=MaterialTheme.colorScheme.error)
        if(!destinationReady) Text("今天尚无身体与目标快照，请先完成今日称重后再复制。",color=MaterialTheme.colorScheme.error)
        Row {
            TextButton(onClick={selected=entries.filter {issue(it)==null}.map {it.id}.toSet()},enabled=!busy) {Text("选择可复制项")}
            TextButton(onClick={selected=emptySet()},enabled=!busy) {Text("清空选择")}
        }
        entries.forEach {entry->key(entry.id) {
            val problem=issue(entry)
            Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Row {Checkbox(entry.id in selected,{checked->selected=if(checked) selected+entry.id else selected-entry.id},enabled=!busy && problem==null)
                    Text(entry.name,Modifier.padding(top=12.dp))}
                if(problem!=null) Text(problem,color=MaterialTheme.colorScheme.error)
                if(entry.id in selected) {
                    NumberField(if(entry.source in setOf("DIRECT","ENERGY")) "相对原记录倍数" else "复制数量 ${entry.unit}",amounts[entry.id] ?: "",enabled=!busy) {amounts[entry.id]=it}
                    if(entry.source !in setOf("DIRECT","ENERGY")) {
                        val duplicate=s.intakes.filter {it.day==today && it.foodId==entry.foodId && it.unit==entry.unit}.sumOf {it.quantity}
                        val copying=chosen.filter {it.foodId==entry.foodId && it.unit==entry.unit && it.source !in setOf("DIRECT","ENERGY")}.sumOf {amounts[it.id]?.positiveNumber() ?: 0.0}
                        Text("今天已记 ${duplicate.f()} ${entry.unit}；本次勾选同食品合计 ${copying.f()} ${entry.unit}。复制会额外新增，不覆盖今日条目。")
                    } else Text(if(entry.macrosKnown) "原营养：碳水 ${entry.nutrition.carbs.f()} g · 蛋白质 ${entry.nutrition.protein.f()} g · 脂肪 ${entry.nutrition.fat.f()} g" else "原热量 ${entry.nutrition.kcal.energy(LocalEnergyUnit.current)}；三大营养未知，按热量倍数复制")
                }
            }}
        }}
        if(valid) nutrition?.let {if(chosen.all {entry->entry.macrosKnown}) NutritionCard("复制到今天的预览 · ${chosen.size} 条",it) else Text("复制热量 ${it.kcal.energy(LocalEnergyUnit.current)}；三大营养合计未知");Text("已知费用 ¥${knownCost.money()}${if(unknown>0) " · $unknown 条未计价" else ""}")}
        if(validAmounts && !valid) Text("所选数量的营养或费用超出计算范围，请减小数量。",color=MaterialTheme.colorScheme.error)
        Button(onClick={confirm=true},enabled=!busy && sourceReady && destinationReady && valid) {Text("确认复制所选到今天")}
    }
    if(confirm) AlertDialog(onDismissRequest={if(!busy) confirm=false},title={Text("新增到 ${LocalDate.ofEpochDay(today)}？")},
        text={Text("将新增 ${chosen.size} 条摄入${nutrition?.let {"，${it.kcal.energy(LocalEnergyUnit.current)}"} ?: ""}。今天已有同食品会继续累加。直接营养保留原营养比例，食品采用当前营养与价格；历史原记录和各日身体、目标均保留。")},
        confirmButton={TextButton(onClick={vm.copyHistory(map) {confirm=false;selected=emptySet()}},enabled=!busy && valid && sourceReady && destinationReady) {Text("确认新增")}},
        dismissButton={TextButton(onClick={confirm=false},enabled=!busy) {Text("取消")}})
}

@Composable private fun HistoryExercises(vm:LedgerViewModel,s:LedgerState,day:Long,busy:Boolean,writable:Boolean) {
    val unit=LocalEnergyUnit.current
    var expanded by rememberSaveable(day) {mutableStateOf(false)}
    var editingId by rememberSaveable(day) {mutableStateOf<Long?>(null)}
    var manual by rememberSaveable(day) {mutableStateOf(true)}
    var typeName by rememberSaveable(day) {mutableStateOf(Cardio.HILL.name)}
    var minutes by rememberSaveable(day) {mutableStateOf("")}
    var raw by rememberSaveable(day) {mutableStateOf("")}
    var included by rememberSaveable(day) {mutableStateOf(true)}
    var previousUnit by remember {mutableStateOf(unit)}
    LaunchedEffect(unit) {if(unit!=previousUnit) {raw=raw.toDoubleOrNull()?.let {unit.fromKcal(previousUnit.toKcal(it)).toString()} ?: raw;previousUnit=unit}}
    var confirmation by remember(day) {mutableStateOf<(() -> Unit)?>(null)}
    var deleting by remember(day) {mutableStateOf<Exercise?>(null)}
    Text("当日运动",style=MaterialTheme.typography.titleLarge)
    Text("补改运动仅改变运动记录与额外消耗合计，始终保留原目标，不补回或重算营养。")
    s.exercises.filter {it.day==day}.sortedByDescending {it.id}.forEach {exercise->
        Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
            val label=if(exercise.type=="MANUAL") "手填消耗" else runCatching {Cardio.valueOf(exercise.type).label}.getOrDefault("运动")
            Text("$label${exercise.minutes?.let {" · ${it.f()} 分钟"} ?: ""}")
            Text("${exercise.kcal.energy(unit)} · ${if(exercise.included) "计入额外消耗" else "仅记录，不累加"}")
            if(writable) Row {
                TextButton(onClick={
                    editingId=exercise.id;manual=exercise.type=="MANUAL";typeName=if(manual) Cardio.HILL.name else exercise.type
                    minutes=exercise.minutes?.toString() ?: "";raw=unit.fromKcal(exercise.kcal).toString();included=exercise.included;expanded=true
                },enabled=!busy) {Text("修改")}
                TextButton(onClick={deleting=exercise},enabled=!busy) {Text("删除")}
            }
        }}
    }
    if(s.exercises.none {it.day==day}) Text("当日没有运动记录。")
    if(writable) {
        OutlinedButton(onClick={val wasEditing=editingId!=null;editingId=null;manual=true;raw="";minutes="";included=true;expanded=wasEditing || !expanded},modifier=Modifier.fillMaxWidth(),enabled=!busy) {Text(if(expanded && editingId==null) "收起运动补记" else "补记运动")}
        if(expanded) {
            Text(if(editingId==null) "补记运动" else "修改所选运动",style=MaterialTheme.typography.titleMedium)
            Choice("记录方式",manual,listOf(true,false),{if(it) "直接填写活动消耗" else "按当日体重及时长估算"},!busy) {manual=it}
            val type=runCatching {Cardio.valueOf(typeName)}.getOrDefault(Cardio.HILL)
            if(manual) NumberField("额外 / 活动消耗 ${unit.label}",raw,enabled=!busy) {raw=it} else {
                Choice("有氧类型",type,Cardio.entries.toList(),{it.label},!busy) {typeName=it.name}
                NumberField("有效时长（分钟）",minutes,enabled=!busy) {minutes=it}
                Text("使用所选日期已存体重与当前所选强度估算净额外消耗，已扣除同时间静息消耗；不使用今天体重。")
            }
            val estimate=if(manual) raw.positiveNumber()?.let(unit::toKcal)?.takeIf {it<=100000 && it.isFinite()}
                else runCatching {ExerciseCalculator.extra(s.bodies.first {it.day==day}.weight,minutes.toDouble(),type.met)}.getOrNull()
            estimate?.let {Text("${if(manual) "本次额外消耗" else "估算额外消耗"}：${it.energy(unit)}")}
            Row {Checkbox(included,{included=it},enabled=!busy);Text("计入当日额外消耗",Modifier.padding(top=12.dp))}
            Text("若活动系数已包含此运动，可取消勾选，避免重复计入。手填值为额外活动消耗。")
            Button(onClick={
                val recordId=editingId;val saveType=if(manual) null else type;val duration=if(manual) null else minutes.toDoubleOrNull()
                val manualKcal=if(manual) estimate else null;val counts=included
                confirmation={vm.historySaveExercise(day,recordId,saveType,duration,manualKcal,counts);expanded=false;editingId=null}
            },enabled=!busy && estimate!=null) {Text(if(editingId==null) "补记运动" else "保存运动修改")}
            if(editingId!=null) TextButton(onClick={editingId=null;expanded=false},enabled=!busy) {Text("取消修改")}
        }
    }
    confirmation?.let {action->AlertDialog(onDismissRequest={if(!busy) confirmation=null},title={Text("保存运动并保留原目标？")},
        text={Text("将保存 ${LocalDate.ofEpochDay(day)} 的运动记录并更新额外消耗合计。当天身体、目标和已吃摄入保持原值；不会重算运动补回目标。")},
        confirmButton={TextButton(onClick={confirmation=null;action()},enabled=!busy) {Text("确认保存")}},
        dismissButton={TextButton(onClick={confirmation=null},enabled=!busy) {Text("取消")}})}
    deleting?.let {exercise->AlertDialog(onDismissRequest={if(!busy) deleting=null},title={Text("删除这次运动？")},
        text={Text("只删除 ${LocalDate.ofEpochDay(day)} 的这次运动。身体和目标快照保留，不重算运动补回目标。删除后可撤销恢复原运动记录。")},
        confirmButton={TextButton(onClick={vm.historyRemoveExercise(day,exercise.id);deleting=null},enabled=!busy) {Text("确认删除")}},
        dismissButton={TextButton(onClick={deleting=null},enabled=!busy) {Text("取消")}})}
}

private fun String.positiveNumber()=trim().toDoubleOrNull()?.takeIf {it.isFinite() && it>0}
