package com.fitledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.fitledger.data.Food
import com.fitledger.data.FoodShareCodec

@Composable fun CopyFoodCodeButton(food:Food,busy:Boolean,vm:LedgerViewModel) {
    val clipboard=LocalClipboardManager.current
    TextButton(onClick={
        runCatching {FoodShareCodec.encode(food)}.onSuccess {
            clipboard.setText(AnnotatedString(it));vm.message.value="食品代码已复制，对方可在食品库填写并确认添加（需支持分享码的版本）"
        }.onFailure {vm.message.value=it.message ?: "暂时无法生成食品代码"}
    },enabled=!busy) {Text("复制食品代码")}
}

@Composable fun FoodCodeImporter(vm:LedgerViewModel,foods:List<Food>,busy:Boolean) {
    var shown by rememberSaveable {mutableStateOf(false)}
    var code by rememberSaveable {mutableStateOf("")}
    var preview by remember {mutableStateOf<Food?>(null)}
    var allowed by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    val clipboard=LocalClipboardManager.current
    fun changeCode(value:String) {
        code=value.take(FoodShareCodec.MAX_CODE_CHARS+1);preview=null
        error=if(value.length>FoodShareCodec.MAX_CODE_CHARS) "食品代码过长，请只粘贴食品代码" else null
    }
    OutlinedButton(onClick={shown=!shown},enabled=!busy) {Text(if(shown) "收起食品代码" else "填写食品代码")}
    if(shown) Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("粘贴朋友分享的 FITFOOD1 食品代码，先预览，确认后加入自己的食品库。")
        OutlinedTextField(code,::changeCode,label={Text("食品代码")},modifier=Modifier.fillMaxWidth(),minLines=2,maxLines=4,
            enabled=!busy,isError=error!=null)
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            TextButton(onClick={changeCode(clipboard.getText()?.text ?: "")},enabled=!busy) {Text("粘贴")}
            TextButton(onClick={changeCode("")},enabled=!busy) {Text("清空")}
            Button(onClick={
                runCatching {FoodShareCodec.decode(code)}.onSuccess {preview=it;allowed=it.allowed;error=null}
                    .onFailure {preview=null;error=it.message ?: "食品代码无效"}
            },enabled=!busy && code.isNotBlank()) {Text("预览食品")}
        }
        error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        preview?.let {food ->
            Text(food.name,style=MaterialTheme.typography.titleMedium)
            Text("每 ${food.basisAmount.f()} ${food.unit}")
            NutritionCard("代码中的营养资料",food.nutrition)
            Text("推荐调整单位 ${food.step.f()} ${food.unit} · 最大推荐量 ${food.maximum.f()} ${food.unit}")
            Row {Switch(allowed,{allowed=it},enabled=!busy);Text("允许参与智能推荐",Modifier.padding(top=12.dp))}
            if(foods.any {it.name.trim().equals(food.name.trim(),ignoreCase=true)}) Text("食品库已有同名食品；确认后会新增一份，不会覆盖原食品。")
            Text("资料由分享者填写，请核对包装、品牌和生熟重量；添加后仍可编辑。代码只包含这一项食品资料，不含身体、饮食记录或计划。")
            Button(onClick={vm.importFoodCode(code,allowed) {changeCode("");shown=false}},enabled=!busy) {Text("确认添加到食品库")}
        }
    }}
}
