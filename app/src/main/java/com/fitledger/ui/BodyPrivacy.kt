package com.fitledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.fitledger.domain.*

val LocalBodyVisibility=staticCompositionLocalOf {BodyVisibility.all(false)}
val LocalVisibilityAction=staticCompositionLocalOf<(BodyField,Boolean)->Unit> {{_,_->}}
val LocalPrivacyBusy=staticCompositionLocalOf {false}
@Composable fun VisibilityEye(field:BodyField) {
    val shown=LocalBodyVisibility.current.shows(field)
    val action=LocalVisibilityAction.current
    TextButton(onClick={action(field,!shown)},enabled=!LocalPrivacyBusy.current,
        modifier=Modifier.semantics {contentDescription=(if(shown) "隐藏" else "显示")+field.label}) {
        Text(if(shown) "👁 隐藏" else "👁 显示")
    }
}
@Composable fun BodyValue(field:BodyField,value:String) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
        Text("${field.label}  ${if(LocalBodyVisibility.current.shows(field)) value else "••••"}",Modifier.weight(1f))
        VisibilityEye(field)
    }
}
