package com.fitledger.data

import com.fitledger.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class RecordExportTest {
    private val day=LocalDate.now().minusDays(3).toEpochDay()
    private val energy=DailyEnergySnapshot(24,175.0,"MALE",10957,1.55,1733.3333,3200.0)
    private val body=BodyRecord(day,83.2173,19.8765,66.12345,50.12567,energy)
    private val target=DailyTarget(day,Nutrition(2000.0,250.0,120.0,57.777777),PlanCodec.encode(Plan()))
    private val meal=Intake(1,day,7,"米饭","g",200.0,Nutrition(232.0,52.0,5.2,0.6),costYuan=3.0)
    private fun source(bodies:List<BodyRecord> = listOf(body),targets:List<DailyTarget> = listOf(target),
                       intakes:List<Intake> = listOf(meal),exercises:List<Exercise> = emptyList()) =
        RecordExportSource(bodies,targets,intakes,exercises)
    private fun request(vararg metrics:RecordExportMetric,kind:RecordExportKind=RecordExportKind.DAILY,
                        start:Long=day,end:Long=day) = RecordExportRequest(start,end,kind,metrics.toSet())
    private fun report(request:RecordExportRequest,source:RecordExportSource=source(),
                       visibility:BodyVisibility=BodyVisibility(),unit:EnergyUnit=EnergyUnit.KCAL)=
        RecordExports.build(request,source,visibility,unit)

    @Test fun hiddenBodyFieldsLeaveNeitherValuesColumnsNorSeriesInTheSanitizedReport() {
        val sensitive=listOf(RecordExportMetric.WEIGHT,RecordExportMetric.BODY_FAT,RecordExportMetric.LEAN,
            RecordExportMetric.MUSCLE,RecordExportMetric.BMR)
        val r=report(RecordExportRequest(day,day,RecordExportKind.DAILY,(sensitive+RecordExportMetric.INTAKE_KCAL).toSet()),
            visibility=BodyVisibility.all(false))
        assertEquals(listOf("日期","已记录摄入热量（kcal）"),r.columns)
        assertEquals(listOf(RecordExportMetric.INTAKE_KCAL.label),r.series.map {it.label})
        val representations=r.toString()+RecordExports.csv(r)
        listOf("83.2173","19.8765","66.12345","50.12567","1733.3333","175.0","10957").forEach {
            assertFalse("Hidden value leaked: $it",representations.contains(it))
        }
        assertEquals("232",r.rows.single()[1].text)
        assertTrue(r.notes.any {it.contains("隐私")})
    }

    @Test fun privacyIsCheckedAtEachBuildEvenWhenTheSameRequestWasPreviouslyVisible() {
        val req=request(RecordExportMetric.WEIGHT,RecordExportMetric.CARBS)
        val visible=report(req)
        val hidden=report(req,visibility=BodyVisibility().with(BodyField.WEIGHT,false))
        assertEquals(3,visible.columns.size)
        assertTrue(RecordExports.csv(visible).contains("83.2173"))
        assertEquals(2,hidden.columns.size)
        assertFalse(RecordExports.csv(hidden).contains("83.2173"))
        assertEquals(listOf(RecordExportMetric.CARBS.label),hidden.series.map {it.label})
        assertTrue(runCatching {report(request(RecordExportMetric.WEIGHT),visibility=BodyVisibility.all(false))}.isFailure)
    }

    @Test fun exportsOnlyRequestedFieldsAndDoesNotReadAnUnselectedHistoricalEnergyFormula() {
        val r=report(request(RecordExportMetric.PROTEIN),source(
            bodies=listOf(body.copy(weight=Double.NaN)),targets=listOf(target.copy(planSnapshot="invalid old text"))))
        assertEquals(listOf("日期","蛋白质（g）"),r.columns)
        assertEquals(listOf("5.2"),r.rows.single().drop(1).map {it.text})
        assertEquals(1,r.series.size)
        assertFalse(r.toString().contains("NaN"))
    }

    @Test fun manualExpenditureAndOnlyIncludedExerciseMatchHomeAndAllEnergyColumnsConvertToKj() {
        val plan=Plan(advanced=AdvancedPlan(templates=listOf(PlanTemplate(
            budget=EnergyBudget(BudgetMode.FIXED,2000.0,ExpenditureSource.MANUAL,2400.0)))))
        val savedTarget=target.copy(planSnapshot=PlanCodec.encode(plan))
        val eaten=meal.copy(nutrition=Nutrition(2800.0,125.0,130.0,90.0))
        val exercises=listOf(Exercise(1,day,"MANUAL",null,null,null,300.0,true),Exercise(2,day,"MANUAL",null,null,null,100.0,false))
        val metrics=setOf(RecordExportMetric.BMR,RecordExportMetric.INTAKE_KCAL,RecordExportMetric.TARGET_KCAL,
            RecordExportMetric.TOTAL_EXPENDITURE,RecordExportMetric.EXTRA_EXERCISE,RecordExportMetric.DEFICIT,RecordExportMetric.CARBS)
        val r=report(RecordExportRequest(day,day,RecordExportKind.DAILY,metrics),
            source(targets=listOf(savedTarget),intakes=listOf(eaten),exercises=exercises),unit=EnergyUnit.KJ)
        fun point(metric:RecordExportMetric)=r.series.single {it.label==metric.label}.points.single().second!!
        assertEquals(2800.0*4.184,point(RecordExportMetric.INTAKE_KCAL),1e-7)
        assertEquals(2000.0*4.184,point(RecordExportMetric.TARGET_KCAL),1e-7)
        assertEquals(2700.0*4.184,point(RecordExportMetric.TOTAL_EXPENDITURE),1e-7)
        assertEquals(300.0*4.184,point(RecordExportMetric.EXTRA_EXERCISE),1e-7)
        assertEquals(-100.0*4.184,point(RecordExportMetric.DEFICIT),1e-7)
        assertEquals(energy.bmrKcal*4.184,point(RecordExportMetric.BMR),1e-7)
        assertEquals(125.0,point(RecordExportMetric.CARBS),0.0)
        assertTrue(r.columns.filter {it.contains("热量") || it.contains("消耗") || it.contains("BMR") || it.contains("运动")}.all {it.endsWith("（kJ）")})
        assertEquals("g",r.series.single {it.label==RecordExportMetric.CARBS.label}.unit)
    }

    @Test fun missingIntakesAndEnergyRemainBlankButAnExplicitlyFreeRecordedFoodIsZeroCost() {
        val unknown=meal.copy(day=day+1,costYuan=null)
        val free=meal.copy(id=2,day=day+2,costYuan=0.0)
        val req=request(RecordExportMetric.INTAKE_KCAL,RecordExportMetric.TOTAL_EXPENDITURE,RecordExportMetric.DEFICIT,
            RecordExportMetric.COST,start=day,end=day+2)
        val r=report(req,source(bodies=emptyList(),targets=emptyList(),intakes=listOf(unknown,free)))
        assertTrue(r.rows[0].drop(1).all {it.text.isEmpty() && !it.numeric})
        assertEquals("232",r.rows[1][1].text)
        assertEquals("",r.rows[1][2].text)
        assertEquals("",r.rows[1][3].text)
        assertTrue(r.rows[1][4].text.contains("未完整计价"))
        assertFalse(r.rows[1][4].numeric)
        assertEquals("0",r.rows[2][4].text)
        assertTrue(r.rows[2][4].numeric)
        assertEquals(listOf(null,null,0.0),r.series.single {it.label==RecordExportMetric.COST.label}.points.map {it.second})
    }

    @Test fun dailyCostsExposeIncompletePricingInsteadOfTreatingUnknownAsFree() {
        val r=report(request(RecordExportMetric.COST),source(intakes=listOf(meal,meal.copy(id=2,costYuan=null))))
        assertEquals("未完整计价（已知3元，1条未计价）",r.rows.single()[1].text)
        assertNull(r.series.single().points.single().second)
        assertTrue(r.notes.any {it.contains("未计价不视为免费")})
    }

    @Test fun detailsKeepNativeQuantitiesAndDirectNutrientsWithoutExportingInternalIds() {
        val portion=meal.copy(id=2,foodId=8,name="面包",unit="份",quantity=1.5,nutrition=Nutrition(300.0,40.0,12.0,8.0),source="ROUTINE")
        val direct=meal.copy(id=3,foodId=null,name="直接添加营养",unit="次",quantity=1.0,source="DIRECT",costYuan=null)
        val r=report(request(RecordExportMetric.INTAKE_KCAL,RecordExportMetric.COST,kind=RecordExportKind.INTAKES),
            source(intakes=listOf(direct,portion,meal)),visibility=BodyVisibility.all(false),unit=EnergyUnit.KJ)
        assertEquals(RecordExportKind.INTAKES,r.kind)
        assertEquals(listOf("米饭","面包","直接添加营养"),r.rows.map {it[1].text})
        assertEquals(listOf("200","1.5","1"),r.rows.map {it[2].text})
        assertEquals(listOf("g","份","次"),r.rows.map {it[3].text})
        assertEquals(listOf("食品记录","每日固定食品","直接添加营养"),r.rows.map {it[4].text})
        assertEquals("未计价",r.rows.last().last().text)
        assertTrue(r.series.isEmpty())
        assertTrue(r.columns.none {it.contains("id",ignoreCase=true) || it.contains("体重") || it.contains("BMR")})
        assertEquals(232.0*4.184,r.rows.first()[5].text.toDouble(),1e-7)
    }

    @Test fun csvQuotesNamesAndPreservesChineseBomWithoutAllowingSpreadsheetFormulas() {
        val names=listOf("\t =1+2","+SUM(1,2)","-面包","@SUM(1)","普通,食品\"名称\"\n第二行")
        val r=report(request(RecordExportMetric.COST,kind=RecordExportKind.INTAKES),
            source(intakes=names.mapIndexed {i,name->meal.copy(id=i.toLong()+1,name=name)}))
        val csv=RecordExports.csv(r)
        assertTrue(csv.startsWith("\uFEFF\"日期\""))
        assertTrue(csv.contains("\"'\t =1+2\""))
        assertTrue(csv.contains("\"'+SUM(1,2)\""))
        assertTrue(csv.contains("\"'-面包\""))
        assertTrue(csv.contains("\"'@SUM(1)\""))
        assertTrue(csv.contains("\"普通,食品\"\"名称\"\"\n第二行\""))
        assertTrue(csv.endsWith("\r\n"))
        assertEquals(names,r.rows.map {it[1].text})
    }

    @Test fun negativeDeficitRemainsANumberInCsvAndCannotBeForgedAsANumericFormulaCell() {
        val r=report(request(RecordExportMetric.DEFICIT),source(intakes=listOf(meal.copy(nutrition=Nutrition(4000.0)))))
        assertEquals("-800",r.rows.single()[1].text)
        assertTrue(r.rows.single()[1].numeric)
        val csv=RecordExports.csv(r)
        assertTrue(csv.contains("\"-800\""))
        assertFalse(csv.contains("'-800"))
        assertTrue(runCatching {RecordExports.csv(r.copy(rows=listOf(listOf(RecordExportCell("date"),RecordExportCell("=1+2",true)))))}.isFailure)
    }

    @Test fun dateRangeAndDetailLimitsRejectBeforeProducingPartialExports() {
        val metric=setOf(RecordExportMetric.COST)
        listOf(RecordExportRequest(day+1,day,RecordExportKind.DAILY,metric),
            RecordExportRequest(day-366,day,RecordExportKind.DAILY,metric),
            RecordExportRequest(day,LocalDate.now().plusDays(1).toEpochDay(),RecordExportKind.DAILY,metric),
            RecordExportRequest(Long.MIN_VALUE,day,RecordExportKind.DAILY,metric)).forEach {req->
                assertTrue(runCatching {report(req)}.isFailure)
            }
        assertEquals(366,report(RecordExportRequest(day-365,day,RecordExportKind.DAILY,metric)).rows.size)
        assertTrue(runCatching {report(request(RecordExportMetric.WEIGHT,kind=RecordExportKind.INTAKES))}.isFailure)
        assertTrue(runCatching {report(RecordExportRequest(day,day,RecordExportKind.DAILY,emptySet()))}.isFailure)
        assertTrue(runCatching {report(request(RecordExportMetric.COST,kind=RecordExportKind.INTAKES),
            source(intakes=List(RecordExports.MAX_DETAIL_ROWS+1) {meal.copy(id=it.toLong()+1)}))}.isFailure)
    }

    @Test fun emptyDetailsAreAnEmptyTableRatherThanAnInventedMealAndTodayIsMarkedIncomplete() {
        val r=report(request(RecordExportMetric.CARBS,kind=RecordExportKind.INTAKES),source(intakes=emptyList()))
        assertTrue(r.rows.isEmpty())
        assertEquals(6,r.columns.size)
        assertTrue(r.notes.any {it.contains("没有摄入记录")})
        assertEquals(1,RecordExports.csv(r).split("\r\n").filter {it.isNotEmpty()}.size)
        val today=LocalDate.now().toEpochDay()
        val current=report(RecordExportRequest(today,today,RecordExportKind.DAILY,setOf(RecordExportMetric.INTAKE_KCAL)))
        assertTrue(current.notes.any {it.contains("不是全天最终结果")})
    }

    @Test fun calorieOnlyDailyExportLeavesMacroSeriesBlankAndKeepsEnergyAndDeficit() {
        val unknown=energyIntake(day,500.0,"外食估计",12.0).copy(id=2)
        val metrics=setOf(RecordExportMetric.INTAKE_KCAL,RecordExportMetric.CARBS,RecordExportMetric.PROTEIN,
            RecordExportMetric.FAT,RecordExportMetric.DEFICIT,RecordExportMetric.COST)
        val r=report(RecordExportRequest(day,day,RecordExportKind.DAILY,metrics),source(intakes=listOf(meal,unknown)))
        fun value(metric:RecordExportMetric)=r.series.single {it.label==metric.label}.points.single().second
        assertEquals(732.0,value(RecordExportMetric.INTAKE_KCAL)!!,0.0)
        assertEquals(2468.0,value(RecordExportMetric.DEFICIT)!!,0.0)
        assertEquals(15.0,value(RecordExportMetric.COST)!!,0.0)
        for(metric in listOf(RecordExportMetric.CARBS,RecordExportMetric.PROTEIN,RecordExportMetric.FAT)) {
            assertNull(value(metric))
            val column=r.columns.indexOf("${metric.label}（g）")
            assertEquals("未知（存在仅热量记录）",r.rows.single()[column].text)
            assertFalse(r.rows.single()[column].numeric)
        }
        assertTrue(r.notes.any {it.contains("不作为0")})
        val csv=RecordExports.csv(r)
        assertTrue(csv.contains("未知（存在仅热量记录）"))
    }

    @Test fun calorieOnlyDetailExportsNullMacrosButExplicitKnownZeroRemainsNumericZero() {
        val unknown=energyIntake(day,500.0,"外食估计",null).copy(id=2)
        val knownZero=meal.copy(nutrition=Nutrition(0.0))
        val r=report(request(RecordExportMetric.INTAKE_KCAL,RecordExportMetric.CARBS,RecordExportMetric.PROTEIN,
            RecordExportMetric.FAT,kind=RecordExportKind.INTAKES),source(intakes=listOf(knownZero,unknown)),unit=EnergyUnit.KJ)
        assertEquals("仅热量记录",r.rows.last()[4].text)
        assertEquals(2092.0,r.rows.last()[5].text.toDouble(),0.0)
        assertTrue(r.rows.last().drop(6).all {it.text=="未知" && !it.numeric})
        assertTrue(r.rows.first().drop(6).all {it.text=="0" && it.numeric})
        assertTrue(r.series.isEmpty())
    }

    @Test fun nonFiniteEnergyOrTotalsCannotLeakIntoFilesAsInfinityOrNan() {
        assertTrue(runCatching {report(request(RecordExportMetric.INTAKE_KCAL),source(intakes=listOf(meal.copy(nutrition=Nutrition(Double.NaN)))))}.isFailure)
        assertTrue(runCatching {report(request(RecordExportMetric.COST),source(intakes=listOf(meal.copy(costYuan=Double.MAX_VALUE),meal.copy(id=2,costYuan=Double.MAX_VALUE))))}.isFailure)
        assertTrue(runCatching {report(request(RecordExportMetric.INTAKE_KCAL),source(intakes=listOf(meal.copy(nutrition=Nutrition(Double.MAX_VALUE)))),unit=EnergyUnit.KJ)}.isFailure)
    }
}
