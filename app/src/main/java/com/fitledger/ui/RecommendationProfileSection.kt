package com.fitledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitledger.data.*
import com.fitledger.domain.RecommendationPolicy
import java.util.UUID

@Composable fun RecommendationProfileSection(profiles:List<RecommendationProfile>,policy:RecommendationPolicy,extras:RecommendationExtras,busy:Boolean,
    onSave:(RecommendationProfile)->Unit,onDelete:(String)->Unit,onApply:(RecommendationProfile)->Unit) {
    var open by rememberSaveable {mutableStateOf(false)}
    var name by rememberSaveable {mutableStateOf("")}
    var applying by remember {mutableStateOf<RecommendationProfile?>(null)}
    var deleting by remember {mutableStateOf<RecommendationProfile?>(null)}
    var replacing by remember {mutableStateOf<RecommendationProfile?>(null)}
    Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("推荐条件档案",style=MaterialTheme.typography.titleMedium)
        Text("保存宿舍日、外出日等预算与营养容差，以及周预算、轮换、整套组合、比例、食品周期和搭配条件。切换后影响下一次推荐，饮食目标和历史摄入保持原样。")
        OutlinedButton(onClick={open=!open},enabled=!busy) {Text(if(open) "收起推荐档案" else "管理${profiles.size}套推荐档案")}
        if(open) {
            OutlinedTextField(name,{name=it},label={Text("新档案名称")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy)
            Button(onClick={onSave(RecommendationProfile(UUID.randomUUID().toString(),name.trim(),policy,extras));name=""},enabled=!busy && name.trim().isNotEmpty() && name.trim().length<=60 && profiles.size<50) {Text("将当前已保存条件存为新档案")}
            Text("请先保存上方设置中的修改，再存档案；最多50套。食品自身的最小量、准备标签仍使用食品库当前资料。",style=MaterialTheme.typography.bodySmall)
            profiles.forEach {profile->key(profile.id) {
                var editedName by rememberSaveable(profile.id,profile.name) {mutableStateOf(profile.name)}
                HorizontalDivider()
                OutlinedTextField(editedName,{editedName=it},label={Text("档案名称")},singleLine=true,modifier=Modifier.fillMaxWidth(),enabled=!busy)
                Text("${profile.extras.rules.cycleRules.size}项周期 · ${profile.extras.rules.conditions.size}条搭配 · ${profile.extras.comboIds.size}套组合"+
                    (profile.policy.dailyBudgetYuan?.let {" · 全天预算¥${it.money()}"}?:" · 全天预算不限"))
                Row {
                    Button(onClick={applying=profile},enabled=!busy) {Text("使用这套条件")}
                    TextButton(onClick={onSave(profile.copy(name=editedName.trim()))},enabled=!busy && editedName.trim().isNotEmpty() && editedName.trim().length<=60 && editedName.trim()!=profile.name) {Text("保存名称")}
                }
                Row {
                    TextButton(onClick={replacing=profile},enabled=!busy) {Text("用当前条件覆盖档案")}
                    TextButton(onClick={deleting=profile},enabled=!busy) {Text("删除档案")}
                }
            }}
        }
    }}
    applying?.let {profile->AlertDialog(onDismissRequest={if(!busy) applying=null},title={Text("使用${profile.name}？")},
        text={Text("将替换当前正式推荐条件，按最新食品、实际摄入和分区重新检查；已生成的推荐需重新计算。不会重算饮食目标、改动实际摄入或删除其他档案。")},
        confirmButton={TextButton(onClick={onApply(profile);applying=null},enabled=!busy) {Text("确认切换")}},dismissButton={TextButton(onClick={applying=null},enabled=!busy) {Text("取消")}})}
    replacing?.let {profile->AlertDialog(onDismissRequest={if(!busy) replacing=null},title={Text("覆盖${profile.name}的条件？")},
        text={Text("此档案的推荐条件将替换为当前已保存的条件，名称保留。其他档案、饮食目标和摄入记录不变。")},
        confirmButton={TextButton(onClick={onSave(profile.copy(policy=policy,extras=extras));replacing=null},enabled=!busy) {Text("确认覆盖档案")}},dismissButton={TextButton(onClick={replacing=null},enabled=!busy) {Text("取消")}})}
    deleting?.let {profile->AlertDialog(onDismissRequest={if(!busy) deleting=null},title={Text("删除${profile.name}？")},
        text={Text("只删除保存的推荐条件档案，当前正在使用的条件和全部实际记录保持不变。")},
        confirmButton={TextButton(onClick={onDelete(profile.id);deleting=null},enabled=!busy) {Text("删除档案")}},dismissButton={TextButton(onClick={deleting=null},enabled=!busy) {Text("取消")}})}
}
