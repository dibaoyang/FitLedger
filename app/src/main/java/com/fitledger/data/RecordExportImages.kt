package com.fitledger.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

/** Receives selected, privacy-filtered report values only. It never reads body records or settings. */
object RecordExportImages {
    const val WIDTH=1080
    const val MAX_HEIGHT=10000
    const val MAX_PIXELS=10_000_000L
    const val MAX_DETAIL_ROWS=100
    private const val PADDING=40f
    private const val CONTENT=WIDTH-PADDING*2
    private const val TOO_LARGE="图片内容过多，请缩小日期或使用CSV"
    private val INK=Color.rgb(30,51,44)
    private val MUTED=Color.rgb(85,105,96)
    private val GREEN=Color.rgb(22,105,83)

    /** The caller owns the result and must recycle it after PNG compression. */
    fun render(report:RecordExportReport):Bitmap {
        val safe=report.copy(columns=report.columns.toList(),
            rows=report.rows.map {it.toList()},
            series=report.series.map {it.copy(points=it.points.toList())},notes=report.notes.toList())
        validate(safe)
        val blocks=mutableListOf<Block>()
        blocks+=paragraph(safe.title,paint(38f,INK,true))
        blocks+=Gap(8f)
        blocks+=paragraph(safe.subtitle,paint(24f,MUTED))
        blocks+=Gap(20f)
        if(safe.kind==RecordExportKind.DAILY) {
            blocks+=paragraph("趋势图片展示所选指标；完整逐日数据请导出CSV。",paint(23f,MUTED))
            blocks+=Gap(16f)
            if(safe.series.isEmpty()) blocks+=paragraph("暂无可绘制的指标。",paint(28f,INK))
            safe.series.forEach {series->blocks+=Chart(series);blocks+=Gap(18f)}
        } else {
            blocks+=paragraph("共 ${safe.rows.size} 条所选摄入记录",paint(24f,MUTED))
            blocks+=Gap(16f)
            blocks+=Table(safe.columns,safe.rows)
            if(safe.rows.isEmpty()) {blocks+=Gap(12f);blocks+=paragraph("所选日期没有摄入记录。",paint(26f,INK))}
            blocks+=Gap(18f)
        }
        safe.notes.forEach {note->blocks+=paragraph("• $note",paint(22f,MUTED));blocks+=Gap(8f)}
        val height=ceil(blocks.sumOf {it.height.toDouble()}+PADDING*2).toInt()
        require(height in 1..MAX_HEIGHT && WIDTH.toLong()*height<=MAX_PIXELS) {TOO_LARGE}
        val bitmap=try {Bitmap.createBitmap(WIDTH,height,Bitmap.Config.ARGB_8888)}
            catch(e:OutOfMemoryError) {throw IllegalStateException("图片内存不足，请缩小日期或使用CSV",e)}
        try {
            val canvas=Canvas(bitmap)
            canvas.drawColor(Color.rgb(245,249,246))
            var top=PADDING
            blocks.forEach {block->block.draw(canvas,top);top+=block.height}
            return bitmap
        } catch(error:Throwable) {
            bitmap.recycle()
            throw error
        }
    }

    private fun validate(report:RecordExportReport) {
        require(report.title.isNotBlank() && report.columns.isNotEmpty() && report.columns.size<=16) {"图片报表结构无效"}
        require(report.rows.all {it.size==report.columns.size}) {"图片报表列数不一致"}
        val chars=report.title.length.toLong()+report.subtitle.length+
            report.columns.sumOf {it.length.toLong()}+report.rows.sumOf {row->row.sumOf {it.text.length.toLong()}}+
            report.notes.sumOf {it.length.toLong()}+report.series.sumOf {it.label.length.toLong()+it.unit.length}
        require(chars<=250000) {TOO_LARGE}
        if(report.kind==RecordExportKind.INTAKES) require(report.rows.size<=MAX_DETAIL_ROWS && report.series.isEmpty()) {TOO_LARGE}
        else {
            require(report.rows.size<=366 && report.series.size<=20) {TOO_LARGE}
            report.series.forEach {series->
                require(series.points.size<=366) {TOO_LARGE}
                series.points.forEach {(day,value)->
                    LocalDate.ofEpochDay(day)
                    require(value==null || value.isFinite()) {"图片指标含无效数值"}
                }
                require(series.points.zipWithNext().all {(a,b)->a.first<b.first}) {"图片指标日期顺序无效"}
                if(series.points.isNotEmpty()) require(series.points.last().first-series.points.first().first<366) {TOO_LARGE}
            }
        }
    }

    private fun paint(size:Float,color:Int=INK,bold:Boolean=false)=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize=size;this.color=color;typeface=Typeface.create("sans-serif",if(bold) Typeface.BOLD else Typeface.NORMAL)
    }
    private fun wrap(text:String,paint:Paint,width:Float):List<String> {
        require(width>=24f) {"图片列宽不足，请使用CSV"}
        val output=mutableListOf<String>()
        text.replace("\r\n","\n").replace('\r','\n').replace("\t","    ").split('\n').forEach {line->
            if(line.isEmpty()) output+=""
            var offset=0
            while(offset<line.length) {
                var count=paint.breakText(line,offset,line.length,true,width,null)
                if(count>0 && offset+count<line.length && Character.isHighSurrogate(line[offset+count-1]) && Character.isLowSurrogate(line[offset+count])) count--
                if(count==0) count=Character.charCount(Character.codePointAt(line,offset))
                output+=line.substring(offset,offset+count)
                require(output.size<=400) {TOO_LARGE}
                offset+=count
            }
        }
        return output
    }
    private sealed interface Block {val height:Float;fun draw(canvas:Canvas,top:Float)}
    private data class Gap(override val height:Float):Block {override fun draw(canvas:Canvas,top:Float) {}}
    private class Paragraph(val lines:List<String>,val paint:Paint):Block {
        private val lineHeight=paint.fontSpacing+6f
        override val height=lines.size*lineHeight
        override fun draw(canvas:Canvas,top:Float) {
            lines.forEachIndexed {index,line->canvas.drawText(line,PADDING,top-paint.fontMetrics.ascent+index*lineHeight,paint)}
        }
    }
    private fun paragraph(text:String,paint:Paint)=Paragraph(wrap(text,paint,CONTENT),paint)
    private fun number(value:Double):String {
        val magnitude=abs(value)
        return if(magnitude>=10_000_000 || (magnitude>0 && magnitude<0.001)) String.format(Locale.CHINA,"%.3g",value)
            else String.format(Locale.CHINA,"%.2f",value)
    }

    private class Chart(private val series:RecordExportSeries):Block {
        private val titlePaint=paint(28f,INK,true)
        private val bodyPaint=paint(22f,MUTED)
        private val labelPaint=paint(18f,MUTED)
        private val titleLines=wrap("${series.label}（${series.unit}）",titlePaint,CONTENT-36f)
        private val titleHeight=titleLines.size*(titlePaint.fontSpacing+4f)
        private val valid=series.points.mapNotNull {it.second}
        private val low=valid.minOrNull()
        private val high=valid.maxOrNull()
        private val stats=if(valid.isEmpty()) "暂无有效记录；缺失值未按0绘制"
            else "最低 ${number(low!!)} · 最高 ${number(high!!)} · 最近 ${number(valid.last())} ${series.unit}"
        private val statLines=wrap(stats,bodyPaint,CONTENT-36f)
        private val statHeight=statLines.size*(bodyPaint.fontSpacing+3f)
        override val height=titleHeight+statHeight+244f

        override fun draw(canvas:Canvas,top:Float) {
            val background=Paint().apply {color=Color.WHITE}
            canvas.drawRoundRect(RectF(PADDING,top,WIDTH-PADDING,top+height),18f,18f,background)
            var cursor=top+14f
            titleLines.forEach {line->canvas.drawText(line,PADDING+18f,cursor-titlePaint.fontMetrics.ascent,titlePaint);cursor+=titlePaint.fontSpacing+4f}
            cursor+=4f
            statLines.forEach {line->canvas.drawText(line,PADDING+18f,cursor-bodyPaint.fontMetrics.ascent,bodyPaint);cursor+=bodyPaint.fontSpacing+3f}
            val left=PADDING+148f
            val right=WIDTH-PADDING-22f
            val chartTop=cursor+15f
            val bottom=top+height-48f
            val grid=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=Color.rgb(219,230,223);strokeWidth=1f}
            for(i in 0..2) {
                val y=chartTop+(bottom-chartTop)*i/2f
                canvas.drawLine(left,y,right,y,grid)
            }
            if(low!=null && high!=null) {
                // Scaling first avoids overflow for valid but very large positive/negative values.
                val scale=max(1.0,max(abs(low),abs(high)))
                val normalizedLow=low/scale
                val normalizedHigh=high/scale
                val span=normalizedHigh-normalizedLow
                val label=Paint(labelPaint).apply {textAlign=Paint.Align.RIGHT}
                for(i in 0..2) {
                    val fraction=1.0-i/2.0
                    val value=(normalizedLow*(1-fraction)+normalizedHigh*fraction)*scale
                    val y=chartTop+(bottom-chartTop)*i/2f
                    canvas.drawText(number(value),left-10f,y+6f,label)
                }
                val firstDay=series.points.first().first
                val lastDay=series.points.last().first
                fun x(day:Long)=if(firstDay==lastDay) (left+right)/2f else left+((day-firstDay).toDouble()/(lastDay-firstDay)*(right-left)).toFloat()
                fun y(value:Double)=if(span==0.0) (chartTop+bottom)/2f
                    else bottom-(((value/scale-normalizedLow)/span)*(bottom-chartTop)).toFloat()
                val curve=Paint(Paint.ANTI_ALIAS_FLAG).apply {color=GREEN;strokeWidth=3f;strokeCap=Paint.Cap.ROUND}
                var previous:Pair<Long,Double>?=null
                series.points.forEach {(day,value)->
                    if(value==null) previous=null
                    else {
                        previous?.takeIf {day-it.first==1L}?.let {old->canvas.drawLine(x(old.first),y(old.second),x(day),y(value),curve)}
                        canvas.drawCircle(x(day),y(value),3.5f,curve)
                        previous=day to value
                    }
                }
            } else canvas.drawText("无有效数据",left+24f,(chartTop+bottom)/2f,bodyPaint)
            series.points.firstOrNull()?.let {canvas.drawText(LocalDate.ofEpochDay(it.first).toString(),left,bottom+30f,labelPaint)}
            series.points.lastOrNull()?.takeIf {it.first!=series.points.first().first}?.let {
                val endPaint=Paint(labelPaint).apply {textAlign=Paint.Align.RIGHT}
                canvas.drawText(LocalDate.ofEpochDay(it.first).toString(),right,bottom+30f,endPaint)
            }
        }
    }

    private class Table(columns:List<String>,rows:List<List<RecordExportCell>>):Block {
        private val headerPaint=paint(20f,INK,true)
        private val bodyPaint=paint(20f,INK)
        private val lineHeight=bodyPaint.fontSpacing+4f
        private val weights=columns.map {label->when {
            label.contains("食品") || label.contains("名称")->2.4f
            label.contains("日期")->1.6f
            label=="单位"->0.7f
            label.contains("来源")->1.3f
            else->1.1f
        }}
        private val widths=weights.map {it/weights.sum()*CONTENT}
        private val headings=columns.mapIndexed {index,text->wrap(text,headerPaint,widths[index]-16f)}
        private val headerHeight=headings.maxOf {it.size}*(headerPaint.fontSpacing+4f)+20f
        private val data=rows.map {row->row.mapIndexed {index,cell->wrap(cell.text,bodyPaint,widths[index]-16f)}}
        private val heights=data.map {row->row.maxOf {it.size}*lineHeight+20f}
        override val height=headerHeight+heights.sum()
        override fun draw(canvas:Canvas,top:Float) {
            val header=Paint().apply {color=Color.rgb(225,237,230)}
            canvas.drawRect(PADDING,top,WIDTH-PADDING,top+headerHeight,header)
            drawRow(canvas,headings,headerPaint,top,headerPaint.fontSpacing+4f)
            var cursor=top+headerHeight
            val fill=Paint()
            val grid=Paint().apply {color=Color.rgb(216,227,220);strokeWidth=1f}
            data.forEachIndexed {index,row->
                fill.color=if(index%2==0) Color.WHITE else Color.rgb(238,245,240)
                canvas.drawRect(PADDING,cursor,WIDTH-PADDING,cursor+heights[index],fill)
                drawRow(canvas,row,bodyPaint,cursor,lineHeight)
                cursor+=heights[index]
                canvas.drawLine(PADDING,cursor,WIDTH-PADDING,cursor,grid)
            }
        }
        private fun drawRow(canvas:Canvas,cells:List<List<String>>,paint:Paint,top:Float,lineHeight:Float) {
            var left=PADDING
            cells.forEachIndexed {index,lines->
                lines.forEachIndexed {line,text->canvas.drawText(text,left+8f,top+10f-paint.fontMetrics.ascent+line*lineHeight,paint)}
                left+=widths[index]
            }
        }
    }
}
