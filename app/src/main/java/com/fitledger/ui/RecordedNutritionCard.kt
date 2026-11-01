package com.fitledger.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.fitledger.data.*
import com.fitledger.domain.Nutrition

@Composable fun RecordedNutritionCard(title:String,entries:List<Intake>,target:Nutrition?=null) {
    val summary=IntakeNutritionTotals.calculate(entries)
    Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(title,style=MaterialTheme.typography.titleMedium)
        Text((target?.kcal?.minus(summary.kcal) ?: summary.kcal).energy(LocalEnergyUnit.current),style=MaterialTheme.typography.headlineSmall)
        if(summary.unknownMacroCount==0) {
            val n=if(target==null) summary.knownNutrition else target-summary.knownNutrition
            Text("碳水 ${n.carbs.f()} g　蛋白质 ${n.protein.f()} g　脂肪 ${n.fat.f()} g")
        } else {
            Text("碳水：未知　蛋白质：未知　脂肪：未知")
            Text("有${summary.unknownMacroCount}条仅热量记录，三大营养${if(target==null) "合计" else "剩余"}不完整。")
            if(target==null) Text("已知部分：碳水 ${summary.knownNutrition.carbs.f()} g／蛋白质 ${summary.knownNutrition.protein.f()} g／脂肪 ${summary.knownNutrition.fat.f()} g（非完整总量）",style=MaterialTheme.typography.bodySmall)
        }
    }}
}
