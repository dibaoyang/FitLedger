package com.fitledger.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import com.fitledger.domain.NutritionLabelDraft
import com.fitledger.domain.NutritionLabelParser
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

object NutritionPhotoReader {
    suspend fun read(context:Context,uri:Uri):NutritionLabelDraft=withContext(Dispatchers.IO) {
        val bitmap=decode(context,uri)
        try {
            val recognizer=TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            try {
                // Wait for the native task even if the screen leaves; only then release its bitmap/client.
                val text=suspendCoroutine<Text> {continuation ->
                    recognizer.process(InputImage.fromBitmap(bitmap,0))
                        .addOnSuccessListener {continuation.resume(it)}
                        .addOnFailureListener {continuation.resumeWithException(it)}
                        .addOnCanceledListener {continuation.resumeWithException(IllegalStateException("识别已取消"))}
                }
                require(text.text.isNotBlank()) {"未识别出文字，请拍清晰、平整的营养成分表后重试"}
                NutritionLabelParser.parse(tableRows(text))
            } finally {recognizer.close()}
        } finally {bitmap.recycle()}
    }

    private fun decode(context:Context,uri:Uri):Bitmap {
        val resolver=context.contentResolver
        val bounds=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        requireNotNull(resolver.openInputStream(uri)) {"无法打开图片"}.use {BitmapFactory.decodeStream(it,null,bounds)}
        require(bounds.outWidth>0 && bounds.outHeight>0) {"图片格式无法读取，请选择 JPG 或 PNG 照片"}
        var sample=1
        while(maxOf(bounds.outWidth,bounds.outHeight)/sample>2048) sample*=2
        val options=BitmapFactory.Options().apply {inSampleSize=sample;inPreferredConfig=Bitmap.Config.ARGB_8888}
        val bitmap=requireNotNull(resolver.openInputStream(uri)?.use {BitmapFactory.decodeStream(it,null,options)}) {"图片无法解码"}
        try {
            val orientation=try {resolver.openInputStream(uri)?.use {ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION,1)} ?: 1}
                catch(_:java.io.IOException) {1}
            val matrix=Matrix().apply {
                when(orientation) {
                    2 -> setScale(-1f,1f)
                    3 -> setRotate(180f)
                    4 -> setScale(1f,-1f)
                    5 -> {setRotate(90f);postScale(-1f,1f)}
                    6 -> setRotate(90f)
                    7 -> {setRotate(-90f);postScale(-1f,1f)}
                    8 -> setRotate(-90f)
                }
            }
            if(matrix.isIdentity) return bitmap
            return Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,matrix,true).also {if(it!==bitmap) bitmap.recycle()}
        } catch(e:Exception) {bitmap.recycle();throw e}
    }

    /** ML Kit can split a table into vertical blocks. Rejoin elements by their physical row. */
    private fun tableRows(text:Text):List<String> {
        val elements=text.textBlocks.flatMap {it.lines}.flatMap {it.elements}.filter {it.boundingBox!=null}
            .sortedBy {it.boundingBox!!.centerY()}
        if(elements.isEmpty()) return text.text.lines()
        val rows=mutableListOf<MutableList<Text.Element>>()
        elements.forEach {element ->
            val box=element.boundingBox!!
            val row=rows.lastOrNull()
            val anchor=row?.firstOrNull()?.boundingBox
            if(anchor!=null && kotlin.math.abs(box.centerY()-anchor.centerY())<=minOf(box.height(),anchor.height())*0.55f) row.add(element)
            else rows.add(mutableListOf(element))
        }
        return rows.map {row -> row.sortedBy {it.boundingBox!!.left}.joinToString(" ") {it.text}
            .replace(Regex("(?<=[\\p{IsHan}])\\s+(?=[\\p{IsHan}])"),"")}
    }
}
