package com.fitledger.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fitledger.data.*
import com.fitledger.domain.*
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.delay

fun Double.f() = String.format(Locale.CHINA,"%.1f",this)
fun Double.f2() = String.format(Locale.CHINA,"%.2f",this)
val LocalEnergyUnit=staticCompositionLocalOf {EnergyUnit.KCAL}

fun Double.energy(unit:EnergyUnit) = "${unit.fromKcal(this).f()} ${unit.label}"
private fun String.number() = toDoubleOrNull() ?: Double.NaN
@Composable fun NumberField(label: String, value: String, field:BodyField?=null, maskValue:Boolean=false,enabled:Boolean=true, change: (String)->Unit) {
    OutlinedTextField(value,change,label={Text(label)},singleLine=true, trailingIcon={field?.let {VisibilityEye(it)}},
        visualTransformation=if(maskValue || (field!=null && !LocalBodyVisibility.current.shows(field))) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal),modifier=Modifier.fillMaxWidth(),enabled=enabled)
}
@Composable fun <T> Choice(label: String, value: T, options: List<T>, name: (T)->String, enabled: Boolean=true, change: (T)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box { OutlinedButton(onClick={expanded=true},enabled=enabled) { Text("$label：${name(value)}") }
        DropdownMenu(expanded && enabled,{expanded=false}) { options.forEach { item -> DropdownMenuItem(text={Text(name(item))},onClick={change(item);expanded=false}) } }
    }
}
@Composable fun NutritionCard(title: String, n: Nutrition) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(title,style=MaterialTheme.typography.titleMedium)
        Text(n.kcal.energy(LocalEnergyUnit.current),style=MaterialTheme.typography.headlineSmall)
        Text("碳水 ${n.carbs.f()} g　蛋白质 ${n.protein.f()} g　脂肪 ${n.fat.f()} g")
    } }
}
@Composable fun LedgerApp(vm: LedgerViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val day by vm.today.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val ready by vm.dataReady.collectAsStateWithLifecycle()
    val muscleNotice by vm.muscleOmittedNotice.collectAsStateWithLifecycle()
    val stage=startupStage(state.loaded,ready,state.preferences,state.bodies.any {it.day==day})
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val scroll=rememberScrollState()
    LaunchedEffect(tab,day,stage) {scroll.scrollTo(0)}
    LaunchedEffect(muscleNotice) {
        val token=muscleNotice
        if(token!=null) {delay((token-android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0));if(vm.muscleOmittedNotice.value==token) vm.muscleOmittedNotice.value=null}
    }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); vm.message.value=null } }
    CompositionLocalProvider(LocalEnergyUnit provides state.preferences.energyUnit,LocalBodyVisibility provides state.preferences.visibility,LocalVisibilityAction provides {field,show->vm.visibility(field,show)},LocalPrivacyBusy provides busy,LocalPageScroll provides scroll) {
    MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFF166953),secondary=Color(0xFF527565),background=Color(0xFFF5F8F5))) {
        Scaffold(snackbarHost={SnackbarHost(snackbar)},bottomBar={
            if(stage==StartupStage.MAIN) NavigationBar {
                listOf("今日","食品库","每日吃的","趋势","设置").forEachIndexed { i,s -> NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Text(listOf("◉","＋","✓","↗","⚙")[i])},label={Text(s)}) }
            }
        }) { padding ->
            BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("轻衡 · ${LocalDate.ofEpochDay(day)}",style=MaterialTheme.typography.headlineMedium)
                if(state.loaded && state.preferences.hasReadIntro) Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick={vm.allVisible(false)},enabled=!busy && ready) {Text("全部隐藏")}
                    TextButton(onClick={vm.allVisible(true)},enabled=!busy && ready) {Text("全部显示")}
                }
                if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                when(stage) {
                    StartupStage.LOADING -> Text("正在读取本地数据…")
                    StartupStage.SYNC -> {Text("正在完成数据与设置同步，请稍候。若上次操作中断，可重试完成。");Button(onClick={vm.retryRecovery()},enabled=!busy) {Text("重试同步")}}
                    StartupStage.INTRO -> IntroScreen(busy,vm::acknowledgeIntro)
                    StartupStage.SETUP -> FirstSetupScreen(vm,state.preferences,busy,day)
                    StartupStage.WEIGH -> key(day) { WeighScreen(vm,busy,state.preferences) }
                    StartupStage.MAIN -> when(tab) {
                        0 -> TodayScreen(vm,state,day,busy)
                        1 -> FoodLibraryScreen(vm,state,busy)
                        2 -> DailyFoodsScreen(vm,state,busy)
                        3 -> TrendsScreen(state,day)
                        else -> SettingsScreen(vm,state.preferences,busy)
                    }
                }
            }
            if(muscleNotice!=null && stage==StartupStage.WEIGH) Surface(
                modifier=Modifier.align(Alignment.BottomCenter).padding(horizontal=20.dp).padding(bottom=maxHeight*0.20f),
                shape=MaterialTheme.shapes.medium,shadowElevation=6.dp,
                color=MaterialTheme.colorScheme.inverseSurface,contentColor=MaterialTheme.colorScheme.inverseOnSurface
            ) {Text("肌肉量已隐藏，可在设置中恢复，后续无需输入肌肉量",Modifier.padding(16.dp).semantics {liveRegion=LiveRegionMode.Polite})}
            }
        }
    }
    }
}
@Composable private fun WeighScreen(vm: LedgerViewModel,busy: Boolean,preferences: Preferences) {
    val day by vm.today.collectAsStateWithLifecycle()
    val keyboard=LocalSoftwareKeyboardController.current
    val configuredPlan=preferences.planFor(day)
    var trainingDay by rememberSaveable(day,configuredPlan.advanced?.schedule?.trainingDay) {mutableStateOf(configuredPlan.advanced?.schedule?.trainingDay ?: false)}
    val plan=if(configuredPlan.advanced?.schedule?.mode==ScheduleMode.TRAINING_REST) configuredPlan.copy(advanced=configuredPlan.advanced.copy(schedule=configuredPlan.advanced.schedule.copy(trainingDay=trainingDay))) else configuredPlan
    var editPlan by rememberSaveable {mutableStateOf(false)}
    var weight by rememberSaveable { mutableStateOf("") }; var fat by rememberSaveable { mutableStateOf("") }; var muscle by rememberSaveable { mutableStateOf("") }
    var weightUnit by rememberSaveable { mutableStateOf(preferences.weightInputUnit) }
    fun changeWeightUnit(unit:WeightUnit):Boolean {
        val converted=weightUnit.convertInput(weight,unit)
        if(converted==null) {vm.message.value="体重输入尚未完成，请先输入有效数字再切换单位";return false}
        weight=converted;weightUnit=unit
        return true
    }
    LaunchedEffect(preferences.weightInputUnit) {
        if(weightUnit!=preferences.weightInputUnit) changeWeightUnit(preferences.weightInputUnit)
    }
    Text("先记录今天的身体数据",style=MaterialTheme.typography.titleLarge)
    Text(if(preferences.skipMuscleInput) "体重、体脂率为必填，肌肉量已设为省略。保存后生成今天的目标，再进入饮食记录。体脂率按百分数输入，如 15.2。"
        else "填写今日体重、体脂率和肌肉量；不记录肌肉量时，可点击「隐藏并免填肌肉量」。保存后生成今天的目标，再进入饮食记录。体脂率按百分数输入，如 15.2。")
    Choice("体重输入单位",weightUnit,WeightUnit.entries.toList(),{it.label},enabled=!busy) {unit ->
        if(changeWeightUnit(unit)) vm.weightInputUnit(unit)
    }
    NumberField("体重 ${weightUnit.label}",weight,BodyField.WEIGHT) {weight=it}
    Text("1 kg = 2 斤；切换时自动换算已输入的体重，记住本次单位。")
    NumberField("体脂率 %",fat,BodyField.BODY_FAT) {fat=it}
    if(!preferences.skipMuscleInput) {
        NumberField("肌肉量 kg",muscle,BodyField.MUSCLE) {muscle=it}
        TextButton(onClick={keyboard?.hide();vm.omitMuscleFromWeighing()},enabled=!busy) {Text("隐藏并免填肌肉量")}
    }
    else Text("今日肌肉量将保存为未记录；不影响瘦体重、消耗和营养目标计算。")
    val body = Body(weightUnit.toKg(weight.number()),fat.number(),if(preferences.skipMuscleInput) null else muscle.number())
    BodyValue(BodyField.LEAN,if(body.weight.isFinite() && body.fatPercent in 0.0..100.0) body.lean.f2()+" kg（自动计算）" else "—")
    Text("当前计划：${plan.goal} · ${if(plan.advanced!=null) "高级组合计划" else plan.mode.label}")
    if(plan.advanced?.schedule?.mode==ScheduleMode.TRAINING_REST) {
        Choice("今天的类型",trainingDay,listOf(true,false),{if(it) "训练日" else "休息日"},enabled=!busy) {trainingDay=it}
        Text("此选择仅用于今天的目标，保留以后默认类型。")
    }
    if(plan == Plan(start=plan.start)) Text("当前使用示例目标。正常保存计划明天生效；完成今日身体记录后，可确认立即生效来更新今日目标。")
    val energy=runCatching {preferences.profile?.let {EnergyExpenditureCalculator.snapshot(body.weight,it,day)}}.getOrNull()
    energy?.let {
        BodyValue(BodyField.BMR,"约 "+it.bmrKcal.energy(LocalEnergyUnit.current))
        Text("今日估算消耗预览：约 ${it.expenditureKcal.energy(LocalEnergyUnit.current)}")
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val preview=runCatching {TargetPlanning.preview(plan,BodyRecord(day,body.weight,body.fatPercent,body.lean,body.muscle,energy),state.bodies,state.targets,state.exercises)}
    preview.getOrNull()?.let {NutritionCard("今日目标预览",it)}
    TextButton(onClick={editPlan=!editPlan}) {Text(if(editPlan) "收起设置" else "设置：省略肌肉量、计划、单位与备份")}
    if(editPlan) SettingsScreen(vm,preferences,busy)
    Button(onClick={vm.weigh(body,if(plan.advanced?.schedule?.mode==ScheduleMode.TRAINING_REST) trainingDay else null)},enabled=!busy && preferences.profile!=null,modifier=Modifier.fillMaxWidth()) {Text("保存身体数据并生成今日目标")}
}
@Composable private fun TodayScreen(vm: LedgerViewModel,s: LedgerState,day: Long,busy: Boolean) {
    val b = s.bodies.first {it.day==day}
    if(s.dayModes.any {it.day==day && it.isIndulgence}) {
        Text("今日是放纵餐",style=MaterialTheme.typography.headlineLarge)
        Text("安心享受这一餐，留意饱腹感，明天恢复平常节奏即可。")
        TextButton(onClick={vm.indulgence(day,false)},enabled=!busy) {Text("恢复今日记录")}
        return
    }
    Text("今日身体与能量",style=MaterialTheme.typography.titleLarge)
    val bodyValues=linkedMapOf(BodyField.WEIGHT to (b.weight.f2()+" kg"),BodyField.BODY_FAT to (b.fatPercent.f2()+" %"),BodyField.LEAN to (b.lean.f2()+" kg"),BodyField.MUSCLE to (b.muscle?.let {it.f2()+" kg"} ?: "未记录"))
    b.energy?.let {e->bodyValues.putAll(linkedMapOf(BodyField.GENDER to Sex.valueOf(e.sex).label,BodyField.HEIGHT to (e.heightCm.f()+" cm"),BodyField.BIRTHDAY to LocalDate.ofEpochDay(e.birthday).toString(),BodyField.AGE to "${e.age} 岁",BodyField.BMR to ("约 "+e.bmrKcal.energy(LocalEnergyUnit.current))))}
    bodyValues.filterKeys {s.preferences.visibility.shows(it)}.forEach {(field,value)->BodyValue(field,value)}
    val extra=extraEnergy(s,day)
    val base=baseEnergy(s,day)
    b.energy?.let {e->
        Text("身体资料估算消耗：${e.expenditureKcal.energy(LocalEnergyUnit.current)}")
    } ?: Text(if(base==null) "当天缺少基础能量快照，无法计算总消耗和缺口；补全资料后可确认立即生效。" else "当天没有公式能量快照；消耗与缺口使用已保存的手填值。")
    base?.let {
        if(b.energy==null || kotlin.math.abs(it-b.energy.expenditureKcal)>0.0001) Text("今日采用的手填消耗：${it.energy(LocalEnergyUnit.current)}")
        Text("今日估算总消耗：${(it+extra).energy(LocalEnergyUnit.current)}",style=MaterialTheme.typography.titleMedium)
    }
    val target = s.targets.find {it.day==day} ?: return
    PlanCodec.decode(target.planSnapshot).advanced?.let {advanced->
        val template=AdvancedTargetCalculator.resolveTemplate(advanced,day,TargetPlanning.readContext(target) ?: AdvancedContext())
        Text("今日计划：${template.name} · ${advanced.reference.mode.label}")
    }
    val entries = s.intakes.filter {it.day==day}
    val total = entries.fold(Nutrition()) {a,i->a+i.nutrition}
    base?.let {e ->
        EnergyDifference("计划",e+extra-target.nutrition.kcal)
        val difference=e+extra-total.kcal
        Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(if(difference>=0) "今日实际热量缺口" else "今日实际热量盈余",style=MaterialTheme.typography.titleLarge)
            Text(kotlin.math.abs(difference).energy(LocalEnergyUnit.current),style=MaterialTheme.typography.headlineLarge)
        }}
        Text("实际差额 = 今日采用的消耗 + 计入的额外运动 − 已记录摄入；尚未记录完当天饮食时不是全天最终值。手填消耗若已包含运动，请将相应运动设为不重复计入。")
    }
    NutritionCard("今日目标 · 已保存快照",target.nutrition)
    NutritionCard("今日已摄入",total)
    NutritionCard("今日剩余 · 负数表示超出",target.nutrition-total)
    Text("记录摄入",style=MaterialTheme.typography.titleLarge)
    if(s.foods.isEmpty()) Text("先到「食品库」添加你能买到的食品。") else {
        var grouping by rememberSaveable {mutableStateOf("TYPE")}
        var filter by rememberSaveable {mutableStateOf("ALL")}
        FoodGroupingFilter(grouping,filter,s.zones,!busy,{grouping=it;filter="ALL"},{filter=it})
        val selectable=filterFoods(s.foods,grouping,filter)
        if(selectable.isEmpty()) Text("这个分区暂无食品，可切换分区或在食品库添加。")
        if(selectable.isNotEmpty()) key(grouping,filter) {
        var foodId by rememberSaveable { mutableLongStateOf(s.foods.first().id) }
        val food = selectable.find {it.id==foodId} ?: selectable.first()
        var amount by rememberSaveable(day,food.id,food.unit) {mutableStateOf("")}
        var fromWeight by rememberSaveable(day,food.id,food.unit) {mutableStateOf(false)}
        var revealWeightAmount by rememberSaveable(day,food.id,food.unit) {mutableStateOf(false)}
        var quickFillConfirmation by rememberSaveable(day,food.id,food.unit) {mutableStateOf(false)}
        val weightShown=s.preferences.visibility.shows(BodyField.WEIGHT)
        val maskAmount=fromWeight && !weightShown && !revealWeightAmount
        LaunchedEffect(weightShown) {if(weightShown) revealWeightAmount=false}
        val quickGrams=WeightFoodShortcut.grams(b.weight,food.unit,s.preferences.weightFoodShortcut)
        Choice("食品",food,selectable,{it.name},enabled=!busy) {foodId=it.id}
        NumberField("摄入数量（${food.unit}）",amount,maskValue=maskAmount) {amount=it;fromWeight=false;revealWeightAmount=false}
        if(maskAmount) Text("体重已隐藏，快捷填入的数量也已隐藏。可重新快捷填入并确认显示，或手动输入数量。")
        quickGrams?.let {grams ->
            OutlinedButton(onClick={
                if(weightShown) {amount=grams.toString();fromWeight=true;revealWeightAmount=false}
                else quickFillConfirmation=true
            },enabled=!busy) {Text("按今日体重填入（1 g/kg）")}
            Text("只填入数量，可继续修改；点击「记录到今日」后才保存。")
            if(quickFillConfirmation) AlertDialog(onDismissRequest={quickFillConfirmation=false},
                title={Text("填入后会显示食品克数")},
                text={Text("你已隐藏体重。此快捷方式会把今日体重的公斤数作为食品克数显示，因此能从填入数量得知体重。是否继续？体重的隐藏设置不会改变。")},
                confirmButton={TextButton(onClick={amount=grams.toString();fromWeight=true;revealWeightAmount=true;quickFillConfirmation=false},enabled=!busy) {Text("继续填入")}},
                dismissButton={TextButton(onClick={quickFillConfirmation=false}) {Text("取消")}})
        }
        Button(onClick={vm.record(food.id,amount.number())},enabled=!busy && !maskAmount) {Text("记录到今日")}
        }
    }
    DirectNutritionEditor(vm,day,busy)
    DailyCostCard(entries)
    GroupedIntakes(entries,day,busy,vm::remove,vm::editQuantity)
    HorizontalDivider()
    RecommendationScreen(vm,s,busy)
    val hidden=bodyValues.filterKeys {!s.preferences.visibility.shows(it)}
    if(hidden.isNotEmpty()) {
        HorizontalDivider();Text("已隐藏的身体信息",style=MaterialTheme.typography.titleLarge)
        Text("点击对应眼睛可恢复显示，并移回上方。")
        hidden.forEach {(field,value)->BodyValue(field,value)}
    }
    HorizontalDivider()
    ExerciseSection(vm,s,day,busy)
    HorizontalDivider()
    var confirmIndulgence by rememberSaveable(day) {mutableStateOf(false)}
    OutlinedButton(onClick={confirmIndulgence=true},enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text("今天是放纵餐")}
    if(confirmIndulgence) AlertDialog(onDismissRequest={confirmIndulgence=false},title={Text("设为放纵餐？")},
        text={Text("今日页面将只显示放纵餐提示。已有身体数据、目标和摄入全部保留，可随时恢复；该天不计入平均热量缺口。")},
        confirmButton={TextButton(onClick={vm.indulgence(day,true);confirmIndulgence=false},enabled=!busy) {Text("确认")}},
        dismissButton={TextButton(onClick={confirmIndulgence=false}) {Text("取消")}})
}
@Composable fun EnergyDifference(label:String,difference:Double) {
    val direction=if(difference>=0) "热量缺口" else "热量盈余"
    Text("$label$direction：约 ${kotlin.math.abs(difference).energy(LocalEnergyUnit.current)}")
}
@Composable internal fun FoodEditor(food: Food?,busy: Boolean,zones:List<FoodZone> = emptyList(),initialZone:Long?=null,initialTags:String="",save: (Food)->Unit,close: ()->Unit) {
    val defaultUnit=LocalEnergyUnit.current
    var energyUnit by rememberSaveable {mutableStateOf(defaultUnit)}
    var name by rememberSaveable {mutableStateOf(food?.name ?: "")}; var unit by rememberSaveable {mutableStateOf(food?.unit ?: "g")}
    var basis by rememberSaveable {mutableStateOf(food?.basisAmount?.toString() ?: "100")}
    var canonicalKcal by rememberSaveable {mutableStateOf(food?.nutrition?.kcal)}
    var kcal by rememberSaveable {mutableStateOf(food?.nutrition?.kcal?.let {energyUnit.fromKcal(it).toString()} ?: "")}; var carbs by rememberSaveable {mutableStateOf(food?.nutrition?.carbs?.toString() ?: "")}
    var protein by rememberSaveable {mutableStateOf(food?.nutrition?.protein?.toString() ?: "")}; var fat by rememberSaveable {mutableStateOf(food?.nutrition?.fat?.toString() ?: "")}
    var step by rememberSaveable {mutableStateOf(food?.step?.toString() ?: "10")}; var max by rememberSaveable {mutableStateOf(food?.maximum?.toString() ?: "500")}
    var allowed by rememberSaveable {mutableStateOf(food?.allowed ?: true)}
    var zoneId by rememberSaveable {mutableStateOf(food?.zoneId ?: initialZone)}
    var zonePortion by rememberSaveable {mutableStateOf(food?.zonePortionSize?.toString() ?: if(unit=="g") "100" else "1")}
    var nutritionTags by rememberSaveable {mutableStateOf(food?.nutritionTags ?: initialTags)}
    var price by rememberSaveable {mutableStateOf(food?.pricePerUnitYuan)}
    var scanned by rememberSaveable {mutableStateOf(false)}
    var scanVersion by rememberSaveable {mutableIntStateOf(0)}
    var reviewed by rememberSaveable {mutableStateOf(false)}
    var scanWarnings by rememberSaveable {mutableStateOf("")}
    var scanSource by rememberSaveable {mutableStateOf("")}
    var showSource by rememberSaveable {mutableStateOf(false)}
    NutritionPhotoInput(!busy) {draft ->
        scanned=true;scanVersion++;reviewed=false;scanWarnings=draft.warnings.joinToString("\n");scanSource=draft.source
        energyUnit=draft.energyUnit;kcal=draft.energy?.toString() ?: ""
        canonicalKcal=draft.energy?.let(draft.energyUnit::toKcal)
        carbs=draft.carbs?.toString() ?: "";protein=draft.protein?.toString() ?: "";fat=draft.fat?.toString() ?: ""
        unit=draft.unit ?: "g";basis=draft.basis?.toString() ?: ""
        step=if(unit=="g") "10" else "1";max=if(unit=="g") "500" else "5";zonePortion=if(unit=="g") "100" else "1";price=null
    }
    if(scanned) {
        Text("请核对：每 100g／每份、kcal／kJ、小数点，以及是否错把 NRV% 当作营养值。",color=MaterialTheme.colorScheme.primary)
        if(scanWarnings.isNotEmpty()) Text(scanWarnings,color=MaterialTheme.colorScheme.error)
        TextButton(onClick={showSource=!showSource}) {Text(if(showSource) "收起识别原文" else "查看识别原文")}
        if(showSource) Text(scanSource)
    }
    OutlinedTextField(name,{name=it},label={Text("食品名称（可注明生重/熟重）")},modifier=Modifier.fillMaxWidth())
    Choice("计量",unit,listOf("g","份"),{it},enabled=!busy) {unit=it;basis=if(it=="g") "100" else "1";step=if(it=="g") "10" else "1";max=if(it=="g") "500" else "5";zonePortion=if(it=="g") "100" else "1";price=null}
    Choice("种类分区",zoneId,listOf<Long?>(null)+zones.map {it.id},{id->zones.find {it.id==id}?.name ?: "未分区"},enabled=!busy) {zoneId=it}
    if(zoneId!=null) {
        NumberField("1 分区份对应多少 $unit",zonePortion) {zonePortion=it}
        Text("分区上限统一按份相加。例如一片面包 50 g，就填 50；仅限制推荐，手动记录不受限制。")
    }
    NutritionTagEditor(nutritionTags,!busy) {nutritionTags=it}
    NumberField("以下营养值对应多少 $unit",basis) {basis=it}
    Choice("热量单位",energyUnit,EnergyUnit.entries.toList(),{it.label}) {newUnit ->
        canonicalKcal?.let {kcal=newUnit.fromKcal(it).toString()};energyUnit=newUnit
    }
    NumberField("热量 ${energyUnit.label}",kcal) {kcal=it;canonicalKcal=it.toDoubleOrNull()?.let(energyUnit::toKcal)}
    Text("1 kcal = 4.184 kJ；切换单位会自动换算。")
    NumberField("碳水 g",carbs) {carbs=it}; NumberField("蛋白质 g",protein) {protein=it}; NumberField("脂肪 g",fat) {fat=it}
    NumberField("推荐调整单位 $unit",step) {step=it}; NumberField("单次最大推荐量 $unit",max) {max=it}
    key(unit,scanVersion) {FoodPriceEditor(unit,price,!busy) {price=it}}
    Row {Checkbox(allowed,{allowed=it});Text("允许参与推荐",Modifier.padding(top=12.dp))}
    if(scanned) Row {Checkbox(reviewed,{reviewed=it});Text("我已对照包装核对单位、基准和所有营养数值",Modifier.padding(top=12.dp).weight(1f))}
    Row {Button(onClick={save(Food(food?.id ?: 0,name.trim(),unit,basis.number(),Nutrition(canonicalKcal ?: Double.NaN,carbs.number(),protein.number(),fat.number()),step.number(),max.number(),allowed,energyUnit.name,
        zoneId=zoneId,zonePortionSize=if(zoneId==null) 1.0 else zonePortion.number(),sortOrder=food?.sortOrder ?: 0,nutritionTags=nutritionTags,pricePerUnitYuan=price))},enabled=!busy && (!scanned || reviewed)) {Text("保存食品")};TextButton(onClick=close,enabled=!busy) {Text("关闭编辑")}}
}
@Composable private fun RecommendationScreen(vm: LedgerViewModel,s: LedgerState,busy: Boolean) {
    val day by vm.today.collectAsStateWithLifecycle()
    var preferValue by rememberSaveable(day) {mutableStateOf(false)}
    Text("帮我搭配",style=MaterialTheme.typography.titleLarge)
    Text("可锁定必吃食品。锁定量是本次还要吃的精确量，采用后计入摄入；不要重复记录。")
    ZoneQuotaOverview(s,day)
    Row {Checkbox(preferValue,{preferValue=it;vm.suggestion.value=null},enabled=!busy);Text("性价比优先",Modifier.padding(top=12.dp))}
    Text(if(preferValue) "在贴近营养目标的同时偏向较低费用，只使用已填写价格的食品；预算和营养上下限仍须满足。" else "优先贴近营养目标；可在设置中配置全天预算及碳水、蛋白质、脂肪允许的高低范围。")
    s.preferences.recommendationPolicy.dailyBudgetYuan?.let {budget->
        val entries=s.intakes.filter {it.day==day}
        val known=entries.sumOf {it.costYuan ?: 0.0}
        Text("全天预算 ¥${budget.money()} · 已计价 ¥${known.money()} · 剩余 ¥${(budget-known).money()}")
        if(entries.any {it.costYuan==null}) Text("今日有未计价摄入，暂时无法确认全天预算。旧记录不会按现价重算；可先取消预算限制。",color=MaterialTheme.colorScheme.error)
    }
    val allowed = s.foods.filter {it.allowed}
    val locks = remember {mutableStateMapOf<Long,String>()}
    val allowedIds=allowed.map {it.id}.toSet()
    LaunchedEffect(allowedIds) {
        val removed=locks.keys.filter {it !in allowedIds}
        removed.forEach {locks.remove(it)}
        if(removed.isNotEmpty()) vm.suggestion.value=null
    }
    allowed.forEach { f ->
        Row {Checkbox(f.id in locks,{if(it) locks[f.id]=f.step.toString() else locks.remove(f.id);vm.suggestion.value=null},enabled=!busy);Text("锁定 ${f.name}",Modifier.padding(top=12.dp))}
        if(f.id in locks) NumberField("必吃量 ${f.unit}（步长 ${f.step.f()}）",locks.getValue(f.id),enabled=!busy) {locks[f.id]=it;vm.suggestion.value=null}
    }
    Button(onClick={vm.recommend(locks.filterKeys {it in allowedIds}.mapValues {it.value.number()},preferValue)},enabled=!busy && allowed.isNotEmpty()) {Text("计算搭配")}
    val suggestion by vm.suggestion.collectAsStateWithLifecycle()
    suggestion?.let { result ->
        if(result.quantities.isEmpty()) {
            Text("当前食品未找到可添加的组合，请检查今日剩余目标、食品和限制条件。")
            return
        }
        result.quantities.forEach { (id,q) -> s.foods.find {it.id==id}?.let {Text("${it.name}　${q.f()} ${it.unit}")} }
        NutritionCard("推荐合计",result.nutrition)
        Text(result.costYuan?.let {"本次预计费用 ¥${it.money()}"} ?: "本次包含未计价食品，费用暂不完整")
        val target = s.targets.find {it.day==day}?.nutrition ?: return
        val eaten = s.intakes.filter {it.day==day}.fold(Nutrition()) {a,i->a+i.nutrition}
        NutritionCard("采用后剩余 · 负数表示超出",target-eaten-result.nutrition)
        Text("近似搭配，不保证存在完全匹配的组合。可调整食品库或锁定量后重算。")
        Button(onClick={vm.adopt()},enabled=!busy && result.quantities.isNotEmpty()) {Text("采用并记录全部食品")}
    }
}




