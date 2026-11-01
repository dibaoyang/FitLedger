package com.fitledger.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordExportImagesTest {
    private val day=20000L
    private fun daily(series:List<RecordExportSeries>)=RecordExportReport(
        "所选每日记录","2024-10-04 至 2024-10-06",listOf("日期","热量差(kcal)"),emptyList(),series,
        listOf("仅导出所选指标；缺失记录留空。"),RecordExportKind.DAILY)
    private fun detail(rows:List<List<RecordExportCell>>)=RecordExportReport(
        "饮食明细导出","所选日期",listOf("日期","食品","数量","单位","热量(kcal)","费用(元)","来源"),rows,
        emptyList(),listOf("费用未知不表示免费。"),RecordExportKind.INTAKES)
    private fun row(name:String)=listOf(RecordExportCell("2024-10-04"),RecordExportCell(name),
        RecordExportCell("150",true),RecordExportCell("g"),RecordExportCell("232.5",true),
        RecordExportCell("未计价"),RecordExportCell("手动记录"))

    @Test fun dailyChartsWithMissingValuesNegativeValuesAndSinglePointCompressToPng() {
        val report=daily(listOf(
            RecordExportSeries("实际热量差","kcal",listOf(day to -300.0,day+1 to null,day+2 to 500.0)),
            RecordExportSeries("已记录摄入","kJ",listOf(day to 8368.0))))
        val bitmap=RecordExportImages.render(report)
        try {
            assertEquals(1080,bitmap.width)
            assertTrue(bitmap.height>0 && bitmap.height<=RecordExportImages.MAX_HEIGHT)
            assertTrue(bitmap.width.toLong()*bitmap.height<=RecordExportImages.MAX_PIXELS)
            val output=ByteArrayOutputStream()
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output))
            assertArrayEquals(byteArrayOf(0x89.toByte(),0x50,0x4e,0x47,0x0d,0x0a,0x1a,0x0a),output.toByteArray().take(8).toByteArray())
            assertFalse(bitmap.isRecycled)
        } finally {bitmap.recycle()}
    }

    @Test fun allMissingSeriesAndEmptyDetailRemainValidWithoutInventingData() {
        val chart=RecordExportImages.render(daily(listOf(RecordExportSeries("所选指标","g",listOf(day to null,day+1 to null)))))
        try {assertTrue(chart.height>0)} finally {chart.recycle()}
        val table=RecordExportImages.render(detail(emptyList()))
        try {assertTrue(table.height>0)} finally {table.recycle()}
    }

    @Test fun longChineseAndMultilineFoodNamesWrapRatherThanSilentlyDisappear() {
        val short=RecordExportImages.render(detail(listOf(row("米饭"))))
        val long=RecordExportImages.render(detail(listOf(row("自制熟重土豆泥，含牛奶与胡椒\n第二行备注：不另加酱料"),row("面包🥖"))))
        try {
            assertTrue(long.height>short.height)
            assertEquals(short.width,long.width)
            assertTrue(long.width.toLong()*long.height<=RecordExportImages.MAX_PIXELS)
        } finally {short.recycle();long.recycle()}
    }

    @Test fun tooManyDetailRowsAreRejectedBeforeCreatingAnImage() {
        val failure=runCatching {RecordExportImages.render(detail(List(101) {row("米饭")}))}.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("CSV"))
    }

    @Test fun excessiveWrappedHeightIsRejectedInsteadOfTruncatingRows() {
        val failure=runCatching {RecordExportImages.render(detail(List(100) {row("很长的食品名称".repeat(25))}))}.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("CSV"))
    }

    @Test fun invalidDimensionsAndNonFiniteOrUnorderedChartDataAreRejected() {
        assertTrue(runCatching {RecordExportImages.render(detail(listOf(row("米饭").dropLast(1))))}.isFailure)
        assertTrue(runCatching {RecordExportImages.render(daily(listOf(RecordExportSeries("指标","g",listOf(day to Double.NaN)))))}.isFailure)
        assertTrue(runCatching {RecordExportImages.render(daily(listOf(RecordExportSeries("指标","g",listOf(day+1 to 1.0,day to 2.0)))))}.isFailure)
    }

    @Test fun largeFinitePositiveAndNegativeValuesDoNotOverflowChartCoordinates() {
        val bitmap=RecordExportImages.render(daily(listOf(RecordExportSeries("热量差","kcal",listOf(day to -Double.MAX_VALUE,day+1 to Double.MAX_VALUE)))))
        try {assertTrue(bitmap.height>0)} finally {bitmap.recycle()}
    }
}
