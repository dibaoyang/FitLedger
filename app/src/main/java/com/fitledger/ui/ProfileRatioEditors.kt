package com.fitledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitledger.domain.*
import java.time.LocalDate
import kotlin.math.abs

@Composable fun ProfileEditor(vm:LedgerViewModel,profile:BodyProfile?,busy:Boolean,day:Long,firstSetup:Boolean=false):BodyProfile? {
    if(!firstSetup) Text("基础身体资料",style=MaterialTheme.typography.titleLarge)
    var sex by rememberSaveable(profile?.sex) {mutableStateOf(profile?.sex)}
    var height by rememberSaveable(profile?.heightCm) {mutableStateOf(profile?.heightCm?.toString() ?: "")}
    var birthday by rememberSaveable(profile?.birthday) {mutableStateOf(profile?.birthday?.let {LocalDate.ofEpochDay(it).toString()} ?: "")}
    var factor by rememberSaveable(profile?.activityFactor) {mutableStateOf(profile?.activityFactor?.toString() ?: "1.2")}
    var activity by rememberSaveable(profile?.activityFactor) {mutableStateOf(ActivityLevel.entries.find {it.factor==(profile?.activityFactor ?: 1.2)}?.name ?: "CUSTOM")}
    BodyValue(BodyField.GENDER,sex?.label ?: "未填写")
    if(LocalBodyVisibility.current.shows(BodyField.GENDER)) Choice("性别（计算公式）",sex,listOf<Sex?>(null)+Sex.entries,{it?.label ?: "请选择"}) {sex=it}
    NumberField("身高 cm",height,BodyField.HEIGHT) {height=it}
    val parsedBirthday=runCatching {BirthdayParser.parse(birthday,day)}
    BodyValue(BodyField.BIRTHDAY,parsedBirthday.getOrNull()?.let(BirthdayParser::display) ?: if(birthday.isBlank()) "未填写" else "待正确填写")
    if(LocalBodyVisibility.current.shows(BodyField.BIRTHDAY)) {
        OutlinedTextField(birthday,{birthday=it},label={Text("生日")},singleLine=true,modifier=Modifier.fillMaxWidth(),
            isError=birthday.isNotBlank() && parsedBirthday.isFailure,
            supportingText={Text(if(birthday.isNotBlank() && parsedBirthday.isFailure) parsedBirthday.exceptionOrNull()?.message ?: "请检查生日" else "支持 2000年1月1日、2000.1.1、2000-1-1、2000-01-01")})
    }
    val age=parsedBirthday.getOrNull()?.let {AgeCalculator.age(it.toEpochDay(),day)}
    BodyValue(BodyField.AGE,"${age?.toString() ?: "—"} 岁（自动计算）")
    Choice("活动等级",activity,ActivityLevel.entries.map {it.name}+"CUSTOM",{name -> ActivityLevel.entries.find {it.name==name}?.let {"${it.label} · ${it.factor}"} ?: "自定义系数"}) {
        activity=it;ActivityLevel.entries.find {level->level.name==it}?.let {level->factor=level.factor.toString()}
    }
    if(activity=="CUSTOM") NumberField("自定义活动系数（大于 0，不超过 5）",factor) {factor=it}
    Text("活动系数用于估算每日总消耗，不代表精确测量。")
    val result=runCatching {BodyProfile(requireNotNull(sex) {"请选择性别"},height.toDoubleOrNull() ?: Double.NaN,parsedBirthday.getOrThrow().toEpochDay(),factor.toDoubleOrNull() ?: Double.NaN).also {it.validate(day)}}
    if(result.isFailure && height.isNotBlank() && birthday.isNotBlank()) Text("请检查性别、身高、生日和活动系数是否完整有效。",color=MaterialTheme.colorScheme.error)
    val draft=result.getOrNull()
    Button(onClick={draft?.let {if(firstSetup) vm.completeFirstSetup(it) else vm.saveProfile(it)}},enabled=!busy && draft!=null) {
        Text(if(firstSetup) "保存并继续" else "仅保存资料（不改今日及历史快照）")
    }
    if(firstSetup) Text("年龄随生日自动计算。保存后进入每日身体录入；已有今日和历史记录不会改动。")
    else Text("资料也可随下方计划保存；只有确认立即生效才会更新今天的能量快照。")
    HorizontalDivider()
    return draft
}

@Composable private fun EnergyInput(label:String,kcal:Double,change:(Double)->Unit) {
    val unit=LocalEnergyUnit.current
    var raw by remember(kcal,unit) {mutableStateOf(if(kcal.isFinite()) unit.fromKcal(kcal).toString() else "")}
    NumberField("$label ${unit.label}",raw) {raw=it;change(it.toDoubleOrNull()?.takeIf {value->value>=0}?.let(unit::toKcal) ?: Double.NaN)}
}
@Composable fun RatioEditor(plan:Plan,change:(Plan)->Unit) {
    Text("先确定目标摄入，再按三项热量占比分配克数。")
    Choice("目标摄入方式",plan.intakeMode,IntakeMode.entries.toList(),{it.label}) {change(plan.copy(intakeMode=it))}
    if(plan.intakeMode==IntakeMode.FIXED) EnergyInput("每日目标摄入",plan.fixedIntakeKcal) {change(plan.copy(fixedIntakeKcal=it))}
    else {
        var kind by remember {mutableStateOf(if(plan.energyAdjustment<0) "缺口" else if(plan.energyAdjustment>0) "盈余" else "维持")}
        Choice("能量调整",kind,listOf("缺口","维持","盈余"),{it}) {
            kind=it;change(plan.copy(energyAdjustment=when(it) {"缺口"->-abs(plan.energyAdjustment);"盈余"->abs(plan.energyAdjustment);else->0.0}))
        }
        if(kind!="维持") EnergyInput("目标热量$kind",abs(plan.energyAdjustment)) {change(plan.copy(energyAdjustment=if(kind=="缺口") -it else it))}
        Text("目标摄入 = 当天估算消耗 ${if(kind=="缺口") "− 缺口" else if(kind=="盈余") "+ 盈余" else "（不调整）"}。计算结果必须大于 0。")
    }
    var carbs by remember(plan.ratio.carbs) {mutableStateOf(if(plan.ratio.carbs.isFinite()) plan.ratio.carbs.toString() else "")}
    var protein by remember(plan.ratio.protein) {mutableStateOf(if(plan.ratio.protein.isFinite()) plan.ratio.protein.toString() else "")}
    var fat by remember(plan.ratio.fat) {mutableStateOf(if(plan.ratio.fat.isFinite()) plan.ratio.fat.toString() else "")}
    NumberField("碳水比例",carbs) {carbs=it;change(plan.copy(ratio=plan.ratio.copy(carbs=it.toDoubleOrNull() ?: Double.NaN)))}
    NumberField("蛋白质比例",protein) {protein=it;change(plan.copy(ratio=plan.ratio.copy(protein=it.toDoubleOrNull() ?: Double.NaN)))}
    NumberField("脂肪比例",fat) {fat=it;change(plan.copy(ratio=plan.ratio.copy(fat=it.toDoubleOrNull() ?: Double.NaN)))}
    val fractions=runCatching {plan.ratio.fractions()}
    fractions.getOrNull()?.let {Text("热量占比：碳水 ${(it[0]*100).f()}% · 蛋白质 ${(it[1]*100).f()}% · 脂肪 ${(it[2]*100).f()}%")}
    fractions.exceptionOrNull()?.let {Text(it.message ?: "比例无效",color=MaterialTheme.colorScheme.error)}
}

