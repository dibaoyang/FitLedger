package com.fitledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Displayed before both the daily body gate and the tabs, until its durable acknowledgement. */
@Composable fun IntroScreen(busy:Boolean,onRead:()->Unit) {
    Text("开始使用轻衡",style=MaterialTheme.typography.headlineSmall)
    Text("先了解基本用法，阅读到最下方后点击「我已阅读」进入。阅读一次后，再次打开或更新 App 都不会重复出现。")

    Text("控制饮食不需要用到的都可以乱填或者省略，包括肌肉量（肌肉的质量）等。")
    Text("用于计算的体重、体脂率、身高等请按真实情况填写，否则会影响结果；可省略的项目以表单提示为准。肌肉量可在设置中开启「省略肌肉量输入」。",style=MaterialTheme.typography.bodySmall)

    Text("五个页面",style=MaterialTheme.typography.titleLarge)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("今日 · 今天吃了什么\n记录当天食品，查看今日摄入、估算消耗、热量缺口及智能推荐。")
            Text("食品库 · 有哪些能吃\n添加自己的食品、营养信息和计量方式，决定哪些食品允许参与推荐。")
            Text("每日吃的 · 固定要吃什么\n设置每天固定添加的食品和数量，称重后自动记录；今日仍可修改或删除。")
            Text("趋势 · 变化趋势\n查看身体与营养指标的历史变化，自选指标和 7／30／90 天范围。")
            Text("设置 · 计划改变\n调整饮食计划、身体资料和录入偏好，也可管理提醒、备份与恢复。")
        }
    }

    Text("怎么开始",style=MaterialTheme.typography.titleLarge)
    Text("填写自己的身高体重等信息会自动生成每日估算消耗和营养目标。在网上选择一个自己喜欢的方式（如碳循环等），设置好后添加食品，每次吃了后添加，会显示今日摄入、今日消耗、热量缺口等信息。")
    Text("也能智能推荐（可以加入限制，比如我就要吃两包面，也能自动推荐）。先在食品库里设置这款面的每份营养和推荐单位，再锁定必吃数量，让 App 搭配剩余食品。")
    Text("这些食品都要自己设定，因为每家店情况不同，不能做到准确，只能估算；或者是买速食，按照包装上的营养成分表填写。")
    Text("可以在让店家不要酱的情况下依次测量每个食材重量，计算出营养成分表，也能相对准确地进行控制。")
    Text("如果要更准确可以吃之前过水，口味不会差很多。")
    Text("补充说明：称重还需对应食材的营养数据，并区分生重、熟重及烹调用油。过水无法保证营养估算更准确，口味变化也因人而异；摄入和消耗显示值都是估算。",style=MaterialTheme.typography.bodySmall)

    Button(onClick=onRead,enabled=!busy,modifier=Modifier.fillMaxWidth()) {Text("我已阅读")}
}
