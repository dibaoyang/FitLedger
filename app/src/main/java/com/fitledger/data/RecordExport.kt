package com.fitledger.data

import com.fitledger.domain.BodyField
import com.fitledger.domain.BodyVisibility
import com.fitledger.domain.EnergyUnit
import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class RecordExportKind { DAILY, INTAKES }

enum class RecordExportMetric(
    val label:String, val bodyField:BodyField?=null,
    private val energy:Boolean=false, private val fixedUnit:String="g"
) {
    WEIGHT("体重",BodyField.WEIGHT,fixedUnit="kg"),
    BODY_FAT("体脂率",BodyField.BODY_FAT,fixedUnit="%"),
    LEAN("瘦体重",BodyField.LEAN,fixedUnit="kg"),
    MUSCLE("肌肉量",BodyField.MUSCLE,fixedUnit="kg"),
    BMR("基础代谢BMR",BodyField.BMR,energy=true),
    INTAKE_KCAL("已记录摄入热量",energy=true),
    CARBS("碳水"), PROTEIN("蛋白质"), FAT("脂肪"),
    TARGET_KCAL("目标摄入热量",energy=true),
    TOTAL_EXPENDITURE("估算总消耗",energy=true),
    EXTRA_EXERCISE("计入的额外运动",energy=true),
    DEFICIT("实际热量差（消耗－已记摄入）",energy=true),
    COST("已记录费用",fixedUnit="元");

    fun unit(energyUnit:EnergyUnit):String=if(energy) energyUnit.label else fixedUnit
    internal fun display(value:Double,energyUnit:EnergyUnit):Double=if(energy) energyUnit.fromKcal(value) else value
}

data class RecordExportRequest(val startDay:Long,val endDay:Long,val kind:RecordExportKind,val metrics:Set<RecordExportMetric>)
data class RecordExportSource(val bodies:List<BodyRecord>,val targets:List<DailyTarget>,val intakes:List<Intake>,val exercises:List<Exercise>)
data class RecordExportCell(val text:String,val numeric:Boolean=false)
data class RecordExportSeries(val label:String,val unit:String,val points:List<Pair<Long,Double?>>)
/** Only sanitized selected values are retained here; bitmap/CSV renderers never receive raw records. */
data class RecordExportReport(val title:String,val subtitle:String,val columns:List<String>,
    val rows:List<List<RecordExportCell>>,val series:List<RecordExportSeries>,val notes:List<String>,val kind:RecordExportKind)

object RecordExports {
    const val MAX_DAYS=366
    const val MAX_DETAIL_ROWS=10000
    private const val MAX_CSV_CHARS=10*1024*1024
    private const val MAX_CELL_CHARS=20000
    private val detailMetrics=listOf(RecordExportMetric.INTAKE_KCAL,RecordExportMetric.CARBS,
        RecordExportMetric.PROTEIN,RecordExportMetric.FAT,RecordExportMetric.COST)

    fun allowedMetrics(kind:RecordExportKind):List<RecordExportMetric> =
        if(kind==RecordExportKind.INTAKES) detailMetrics else RecordExportMetric.entries.toList()

    fun build(request:RecordExportRequest,source:RecordExportSource,visibility:BodyVisibility,energyUnit:EnergyUnit):RecordExportReport {
        val first=validDate(request.startDay)
        val last=validDate(request.endDay)
        val today=LocalDate.now()
        require(!last.isAfter(today)) {"只能导出今天及以前已经保存的记录"}
        val distance=ChronoUnit.DAYS.between(first,last)
        require(distance in 0 until MAX_DAYS.toLong()) {"导出范围须为1至366天，请检查起止日期"}
        val allowed=allowedMetrics(request.kind)
        require(request.metrics.isNotEmpty() && allowed.containsAll(request.metrics)) {"请选择适用于当前导出内容的指标"}
        val selected=allowed.filter {it in request.metrics && (it.bodyField==null || visibility.shows(it.bodyField))}
        require(selected.isNotEmpty()) {"所选身体指标已经隐藏，请选择其他可导出的项目"}
        val notes=mutableListOf("仅导出已保存的实际记录，不含未来预设；空白表示缺失或无摄入记录，不等于0。")
        if(selected.size!=request.metrics.size) notes+="已按当前隐私设置排除隐藏的身体指标，其数值与曲线均不会导出。"
        if(RecordExportMetric.COST in selected) notes+="未计价不视为免费；费用包含未知项目时显示已知部分，并标明总费用不完整。"
        if(RecordExportMetric.DEFICIT in selected) notes+="实际热量差正数表示缺口，负数表示盈余；缺少基础消耗或摄入时留空。"
        if(last==today) notes+="今日记录可能尚未完成，今日热量差不是全天最终结果。"
        if(source.intakes.any {it.day in request.startDay..request.endDay && !it.macrosKnown}) notes+="仅热量记录的碳水、蛋白质和脂肪未知；相关日期的宏量合计不完整，曲线留空，不作为0。"
        val title=if(request.kind==RecordExportKind.DAILY) "每日记录导出" else "饮食明细导出"
        val subtitle="$first 至 $last"
        return if(request.kind==RecordExportKind.DAILY) daily(request,source,selected,energyUnit,title,subtitle,notes)
            else detail(request,source,selected,energyUnit,title,subtitle,notes)
    }

    private data class Value(val number:Double?,val text:String="")

    private fun daily(request:RecordExportRequest,source:RecordExportSource,selected:List<RecordExportMetric>,energyUnit:EnergyUnit,
                      title:String,subtitle:String,notes:List<String>):RecordExportReport {
        val bodies=source.bodies.associateBy {it.day}
        val targets=source.targets.associateBy {it.day}
        val intakes=source.intakes.filter {it.day in request.startDay..request.endDay}.groupBy {it.day}
        val exercises=source.exercises.filter {it.day in request.startDay..request.endDay && it.included}.groupBy {it.day}
        val days=(request.startDay..request.endDay).toList()
        val values=days.map {day ->
            val body=bodies[day]
            val target=targets[day]
            val eaten=intakes[day].orEmpty()
            val macrosKnown=eaten.all {it.macrosKnown}
            val extra by lazy {finite(exercises[day].orEmpty().sumOf {it.kcal})}
            val expenditure by lazy {TargetPlanning.baseExpenditure(target,body)?.let {finite(it+extra)}}
            selected.map {metric ->
                val raw=when(metric) {
                    RecordExportMetric.WEIGHT -> Value(body?.weight)
                    RecordExportMetric.BODY_FAT -> Value(body?.fatPercent)
                    RecordExportMetric.LEAN -> Value(body?.lean)
                    RecordExportMetric.MUSCLE -> Value(body?.muscle)
                    RecordExportMetric.BMR -> Value(body?.energy?.bmrKcal)
                    RecordExportMetric.INTAKE_KCAL -> Value(eaten.takeIf {it.isNotEmpty()}?.sumOf {it.nutrition.kcal})
                    RecordExportMetric.CARBS -> if(!macrosKnown) Value(null,"未知（存在仅热量记录）") else Value(eaten.takeIf {it.isNotEmpty()}?.sumOf {it.nutrition.carbs})
                    RecordExportMetric.PROTEIN -> if(!macrosKnown) Value(null,"未知（存在仅热量记录）") else Value(eaten.takeIf {it.isNotEmpty()}?.sumOf {it.nutrition.protein})
                    RecordExportMetric.FAT -> if(!macrosKnown) Value(null,"未知（存在仅热量记录）") else Value(eaten.takeIf {it.isNotEmpty()}?.sumOf {it.nutrition.fat})
                    RecordExportMetric.TARGET_KCAL -> Value(target?.nutrition?.kcal)
                    RecordExportMetric.TOTAL_EXPENDITURE -> Value(expenditure)
                    RecordExportMetric.EXTRA_EXERCISE -> Value(if(body!=null) extra else null)
                    RecordExportMetric.DEFICIT -> Value(if(eaten.isEmpty()) null else expenditure?.minus(eaten.sumOf {it.nutrition.kcal}))
                    RecordExportMetric.COST -> cost(eaten)
                }
                raw.number?.let {Value(finite(metric.display(finite(it),energyUnit)))} ?: raw
            }
        }
        val rows=days.mapIndexed {index,day -> listOf(text(validDate(day).toString()))+values[index].map(::cell)}
        val series=selected.mapIndexed {index,metric->RecordExportSeries(metric.label,metric.unit(energyUnit),
            days.mapIndexed {i,day->day to values[i][index].number})}
        return RecordExportReport(title,subtitle,listOf("日期")+selected.map {header(it,energyUnit)},rows,series,notes,request.kind)
    }

    private fun detail(request:RecordExportRequest,source:RecordExportSource,selected:List<RecordExportMetric>,energyUnit:EnergyUnit,
                       title:String,subtitle:String,notes:List<String>):RecordExportReport {
        val intakes=source.intakes.filter {it.day in request.startDay..request.endDay}
            .sortedWith(compareBy<Intake> {it.day}.thenBy {it.id})
        require(intakes.size<=MAX_DETAIL_ROWS) {"饮食明细超过10000条，请缩小日期范围后导出"}
        val rows=intakes.map {item ->
            val origin=when(item.source) {"FOOD"->"食品记录";"ROUTINE"->"每日固定食品";"DIRECT"->"直接添加营养";"ENERGY"->"仅热量记录";else->"其他摄入"}
            listOf(text(validDate(item.day).toString()),text(item.name),number(item.quantity),text(item.unit),text(origin))+
                selected.map {metric ->
                    val value=when(metric) {
                        RecordExportMetric.INTAKE_KCAL -> Value(item.nutrition.kcal)
                        RecordExportMetric.CARBS -> if(item.macrosKnown) Value(item.nutrition.carbs) else Value(null,"未知")
                        RecordExportMetric.PROTEIN -> if(item.macrosKnown) Value(item.nutrition.protein) else Value(null,"未知")
                        RecordExportMetric.FAT -> if(item.macrosKnown) Value(item.nutrition.fat) else Value(null,"未知")
                        RecordExportMetric.COST -> item.costYuan?.let {Value(it)} ?: Value(null,"未计价")
                        else -> error("饮食明细不支持此指标")
                    }
                    cell(value.number?.let {Value(finite(metric.display(finite(it),energyUnit)))} ?: value)
                }
        }
        val actualNotes=if(intakes.isEmpty()) notes+"所选日期范围没有摄入记录。" else notes
        return RecordExportReport(title,subtitle,listOf("日期","食品／记录名称","数量","单位","来源")+selected.map {header(it,energyUnit)},
            rows,emptyList(),actualNotes,request.kind)
    }

    private fun cost(entries:List<Intake>):Value {
        if(entries.isEmpty()) return Value(null)
        val known=finite(entries.sumOf {it.costYuan ?: 0.0})
        val unknown=entries.count {it.costYuan==null}
        return if(unknown==0) Value(known) else Value(null,"未完整计价（已知${decimal(known)}元，${unknown}条未计价）")
    }
    private fun header(metric:RecordExportMetric,unit:EnergyUnit)="${metric.label}（${metric.unit(unit)}）"
    private fun validDate(day:Long):LocalDate=runCatching {LocalDate.ofEpochDay(day)}.getOrElse {throw IllegalArgumentException("导出日期无效")}
    private fun finite(value:Double):Double=value.also {require(it.isFinite()) {"记录数值或合计超出可导出范围，请核对数据或缩小日期范围"}}
    private fun decimal(value:Double):String=BigDecimal.valueOf(finite(value)).stripTrailingZeros().toPlainString()
    private fun text(value:String)=RecordExportCell(value.also {require(it.length<=MAX_CELL_CHARS) {"单项文字过长，无法导出"}})
    private fun number(value:Double)=RecordExportCell(decimal(value),numeric=true)
    private fun cell(value:Value)=value.number?.let(::number) ?: text(value.text)

    /** CSV is a table, not a backup. Quoted text cannot inject formulas into spreadsheet viewers. */
    fun csv(report:RecordExportReport):String {
        require(report.columns.isNotEmpty() && report.rows.size<=MAX_DETAIL_ROWS && report.rows.all {it.size==report.columns.size}) {"导出表格结构无效"}
        fun escaped(cell:RecordExportCell):String {
            require(cell.text.length<=MAX_CELL_CHARS) {"单项文字过长，无法导出"}
            val value=if(cell.numeric) {
                require(cell.text.toBigDecimalOrNull()!=null && cell.text.toDoubleOrNull()?.isFinite()==true) {"导出数值无效"}
                cell.text
            } else {
                val leading=cell.text.dropWhile {it.isWhitespace() || it.code<32 || it=='\uFEFF'}.firstOrNull()
                if(leading in setOf('=','+','-','@')) "'"+cell.text else cell.text
            }
            return "\""+value.replace("\"","\"\"")+"\""
        }
        return buildString {
            append('\uFEFF')
            fun row(cells:List<RecordExportCell>) {
                append(cells.joinToString(",",transform=::escaped));append("\r\n")
                require(length<=MAX_CSV_CHARS) {"导出内容过大，请缩小日期范围"}
            }
            row(report.columns.map(::text))
            report.rows.forEach(::row)
        }
    }
}
