package com.fitledger.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitledger.data.*
import java.time.LocalDate

enum class RecordExportFormat { CSV, PNG }

private fun defaultExportMetrics(kind:RecordExportKind)=when(kind) {
    RecordExportKind.DAILY->listOf("INTAKE_KCAL","CARBS","PROTEIN","FAT","TOTAL_EXPENDITURE","DEFICIT","COST")
    RecordExportKind.INTAKES->listOf("INTAKE_KCAL","CARBS","PROTEIN","FAT","COST")
}

@Composable fun RecordExportScreen(vm:LedgerViewModel,s:LedgerState,today:Long,busy:Boolean) {
    var kindName by rememberSaveable {mutableStateOf(RecordExportKind.DAILY.name)}
    val kind=RecordExportKind.valueOf(kindName)
    var range by rememberSaveable {mutableIntStateOf(30)}
    var fromText by rememberSaveable {mutableStateOf(LocalDate.ofEpochDay(today-29).toString())}
    var toText by rememberSaveable {mutableStateOf(LocalDate.ofEpochDay(today).toString())}
    var selectedNames by rememberSaveable {mutableStateOf(defaultExportMetrics(kind))}
    var pendingStart by rememberSaveable {mutableStateOf<Long?>(null)}
    var pendingEnd by rememberSaveable {mutableStateOf<Long?>(null)}
    var pendingKind by rememberSaveable {mutableStateOf("")}
    var pendingNames by rememberSaveable {mutableStateOf(emptyList<String>())}
    var pendingFormat by rememberSaveable {mutableStateOf("")}
    fun finish(uri:Uri?) {
        if(uri!=null) {
            val selection=runCatching {
                RecordExportRequest(requireNotNull(pendingStart),requireNotNull(pendingEnd),
                    RecordExportKind.valueOf(pendingKind),pendingNames.map {RecordExportMetric.valueOf(it)}.toSet())
            }.getOrNull()
            val format=runCatching {RecordExportFormat.valueOf(pendingFormat)}.getOrNull()
            if(selection!=null && format!=null) vm.exportRecords(uri,selection,format)
            else vm.message.value="导出选择已失效，请重新选择日期和内容后导出"
        }
        pendingStart=null;pendingEnd=null;pendingKind="";pendingNames=emptyList();pendingFormat=""
    }
    val csvPicker=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv"),::finish)
    val imagePicker=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png"),::finish)
    val allowed=RecordExports.allowedMetrics(kind)
    val metrics=selectedNames.mapNotNull {name->RecordExportMetric.entries.find {it.name==name}}.toSet()
    val requestResult=runCatching {
        val start=if(range==0) LocalDate.parse(fromText.trim()).toEpochDay() else today-range+1
        val end=if(range==0) LocalDate.parse(toText.trim()).toEpochDay() else today
        RecordExportRequest(start,end,kind,metrics)
    }
    val request=requestResult.getOrNull()
    val preview=remember(request,s.bodies,s.targets,s.intakes,s.exercises,s.preferences.visibility,s.preferences.energyUnit) {
        request?.let {runCatching {
            RecordExports.build(it,RecordExportSource(s.bodies,s.targets,s.intakes,s.exercises),
                s.preferences.visibility,s.preferences.energyUnit)
        }}
    }
    val report=preview?.getOrNull()
    val error=requestResult.exceptionOrNull()?.let {"日期格式为YYYY-MM-DD，请检查起止日期"}
        ?: preview?.exceptionOrNull()?.message

    Text("记录导出",style=MaterialTheme.typography.titleLarge)
    Text("选择日期和项目，保存为CSV表格或PNG图片。只导出实际记录，不把未来预设当成已吃；导出不会修改数据。")
    Choice("内容",kind,RecordExportKind.entries.toList(),{if(it==RecordExportKind.DAILY) "每日汇总与趋势" else "饮食明细"},enabled=!busy) {
        kindName=it.name;selectedNames=defaultExportMetrics(it)
    }
    Choice("日期范围",range,listOf(7,30,90,0),{if(it==0) "自定义" else "最近${it}天"},enabled=!busy) {next->
        if(next==0 && range!=0) {
            fromText=LocalDate.ofEpochDay(today-range+1).toString();toText=LocalDate.ofEpochDay(today).toString()
        }
        range=next
    }
    if(range==0) {
        OutlinedTextField(fromText,{fromText=it},label={Text("开始日期 YYYY-MM-DD")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy)
        OutlinedTextField(toText,{toText=it},label={Text("结束日期 YYYY-MM-DD")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy)
    }
    Text("范围最多366天，结束日期不能晚于今天。缺失记录保留空值；未计价费用不会算作免费。")
    Text("选择导出项目",style=MaterialTheme.typography.titleMedium)
    allowed.forEach {metric->
        val hidden=metric.bodyField?.let {!s.preferences.visibility.shows(it)}==true
        Row(Modifier.fillMaxWidth()) {
            Checkbox(metric.name in selectedNames && !hidden,{checked->
                selectedNames=if(checked) (selectedNames+metric.name).distinct() else selectedNames-metric.name
            },enabled=!busy && !hidden)
            Text(metric.label+(if(hidden) "（已隐藏，不导出）" else ""),Modifier.weight(1f).padding(top=12.dp))
        }
    }
    Text("身体项目默认不选。隐藏的体重、体脂、瘦体重、肌肉量或BMR不会出现在表格、图片或曲线中；导出时会再次按当前隐私设置检查。")
    Text("热量单位：${s.preferences.energyUnit.label}，跟随设置。CSV保存完整逐行数据；汇总PNG展示所选指标的趋势，饮食明细PNG最多100条，过多时请缩小日期或使用CSV。")
    if(error!=null) Text(error,color=MaterialTheme.colorScheme.error)
    if(report!=null) {
        Text("预览 · 前${minOf(report.rows.size,5)}条，共${report.rows.size}条",style=MaterialTheme.typography.titleMedium)
        if(report.rows.isEmpty()) Text("所选范围没有饮食明细。")
        else Column(Modifier.horizontalScroll(rememberScrollState())) {
            Row {report.columns.forEach {Text(it,Modifier.width(150.dp).padding(6.dp),style=MaterialTheme.typography.labelLarge)}}
            report.rows.take(5).forEach {row->Row {
                row.forEach {Text(it.text.ifEmpty {"—"},Modifier.width(150.dp).padding(6.dp))}
            }}
        }
        report.notes.forEach {Text(it,style=MaterialTheme.typography.bodySmall)}
    }
    fun prepare(format:RecordExportFormat) {
        val chosen=request ?: return
        if(report==null) return
        pendingStart=chosen.startDay;pendingEnd=chosen.endDay;pendingKind=chosen.kind.name
        pendingNames=chosen.metrics.map {it.name};pendingFormat=format.name
        val filename="FitLedger-${LocalDate.ofEpochDay(chosen.startDay)}-${LocalDate.ofEpochDay(chosen.endDay)}"
        if(format==RecordExportFormat.CSV) csvPicker.launch(filename+".csv")
        else imagePicker.launch(filename+".png")
    }
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Button(onClick={prepare(RecordExportFormat.CSV)},enabled=!busy && report!=null) {Text("导出CSV表格")}
        val imageAllowed=report!=null && (kind!=RecordExportKind.INTAKES || report.rows.size<=100)
        OutlinedButton(onClick={prepare(RecordExportFormat.PNG)},enabled=!busy && imageAllowed) {Text("导出PNG图片")}
    }
}
