package com.fitledger.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.fitledger.data.NutritionPhotoReader
import com.fitledger.domain.NutritionLabelDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.File

@Composable fun NutritionPhotoInput(enabled:Boolean,onResult:(NutritionLabelDraft)->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var reading by remember {mutableStateOf(false)}
    var error by rememberSaveable {mutableStateOf<String?>(null)}
    var captureName by rememberSaveable {mutableStateOf<String?>(null)}
    var result by remember {mutableStateOf<NutritionLabelDraft?>(null)}
    val directory=File(context.cacheDir,"nutrition-capture")
    fun read(uri:Uri,capture:File?=null) {
        reading=true;error=null
        scope.launch {
            try {result=NutritionPhotoReader.read(context.applicationContext,uri)}
            catch(e:CancellationException) {throw e}
            catch(_:OutOfMemoryError) {error="图片过大，内存暂时不足，请裁剪到营养成分表后重试"}
            catch(e:Exception) {error=e.localizedMessage ?: "识别失败，请换张清晰照片重试"}
            finally {capture?.delete();reading=false}
        }
    }
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) {ok ->
        val file=captureName?.let {File(directory,it)};captureName=null
        if(ok && file?.isFile==true) read(Uri.fromFile(file),file) else file?.delete()
    }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {uri ->if(uri!=null) read(uri)}
    Text("识别包装营养成分表",style=MaterialTheme.typography.titleMedium)
    Text("拍清表头、单位和数值。识别在手机本地完成，核对后才会保存；不支持拍菜品推算营养。")
    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled=enabled && !reading && captureName==null,onClick={
            try {
                directory.mkdirs()
                // Only expired temporary camera files, never user photos or database files.
                directory.listFiles()?.filter {it.name.startsWith("label-") && it.lastModified()<System.currentTimeMillis()-86_400_000L}?.forEach {it.delete()}
                val file=File.createTempFile("label-",".jpg",directory)
                captureName=file.name
                camera.launch(FileProvider.getUriForFile(context,"${context.packageName}.nutrition.photos",file))
            } catch(_:Exception) {captureName?.let {File(directory,it).delete()};captureName=null;error="无法打开相机，请改为从相册选择"}
        }) {Text("拍照识别")}
        OutlinedButton(enabled=enabled && !reading && captureName==null,onClick={
            try {picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))}
            catch(_:Exception) {error="无法打开图片选择器，请尝试拍照"}
        }) {Text("从相册识别")}
    }
    if(reading) {LinearProgressIndicator(Modifier.fillMaxWidth());Text("正在识别，请稍候…")}
    error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
    result?.let {draft -> AlertDialog(onDismissRequest={result=null},title={Text("将识别结果填入表单？")},
        text={Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text("会替换当前热量、三大营养素和计量基准，保留食品名称。未可靠识别的项目留空，请逐项核对后保存。")
            Text("热量 ${draft.energy?.f() ?: "未识别"} ${draft.energyUnit.label}\n碳水 ${draft.carbs?.f() ?: "未识别"} g\n蛋白质 ${draft.protein?.f() ?: "未识别"} g\n脂肪 ${draft.fat?.f() ?: "未识别"} g\n基准 ${draft.basis?.f() ?: "未识别"} ${draft.unit ?: ""}")
            if(draft.warnings.isNotEmpty()) Text("有项目需要手动核对，填入后会显示具体提示。")
        }},confirmButton={TextButton(onClick={onResult(draft);result=null}) {Text("填入并核对")}},
        dismissButton={TextButton(onClick={result=null}) {Text("取消")}})
    }
}
