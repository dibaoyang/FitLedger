package com.fitledger.domain

import java.text.Normalizer

/** A draft, never a database food. Missing or ambiguous numbers stay null for manual review. */
data class NutritionLabelDraft(
    val energy:Double?=null,val energyUnit:EnergyUnit=EnergyUnit.KCAL,
    val carbs:Double?=null,val protein:Double?=null,val fat:Double?=null,
    val unit:String?=null,val basis:Double?=null,
    val warnings:List<String> = emptyList(),val source:String=""
)

object NutritionLabelParser {
    private val number="(?<![\\d.,+\\-])[+-]?(?:\\d+(?:\\.\\d+)?|\\.\\d+)"
    private val nutrient=Regex("碳水化合物|碳水|蛋白质|蛋白質|脂肪|能量|热量|熱量|carbohydrates?|protein|total\\s*fat|energy|calories",RegexOption.IGNORE_CASE)
    private val gramValue=Regex("($number)\\s*(?:克|g)(?![a-z])",RegexOption.IGNORE_CASE)
    private val energyValue=Regex("($number)\\s*(kcal|kj|千焦|千卡|大卡)(?![a-z])",RegexOption.IGNORE_CASE)

    fun parse(rows:List<String>):NutritionLabelDraft {
        val lines=rows.take(300).map {Normalizer.normalize(it,Normalizer.Form.NFKC).replace('−','-').trim()}
        val text=lines.joinToString("\n")
        val warnings=mutableListOf<String>()
        val perWeight=Regex("(?:每\\s*)?(\\d+(?:\\.\\d+)?)\\s*(?:g|克)(?![a-z])",RegexOption.IGNORE_CASE)
        // Only headers participate: nutrient rows also contain gram values.
        val headers=lines.filter {nutrient.find(it)==null || it.contains("每") || it.contains("项目") || it.contains("項目")}
        val header=headers.joinToString(" ")
        val weightBases=perWeight.findAll(header).mapNotNull {it.groupValues[1].toDoubleOrNull()}.toList()
        val hasPortion=Regex("每\\s*(?:[一1]\\s*)?(?:份|包)|per\\s+serving",RegexOption.IGNORE_CASE).containsMatchIn(header)
        val hasVolume=Regex("\\d\\s*(?:ml|毫升)",RegexOption.IGNORE_CASE).containsMatchIn(header)
        val multipleBasis=weightBases.size>1 || (hasPortion && weightBases.isNotEmpty())
        var unit:String?=null;var basis:Double?=null
        when {
            hasVolume -> warnings.add("检测到毫升标注。毫升不能直接当作克；请手动设置每份对应的容量或换算称重基准。")
            multipleBasis -> warnings.add("检测到多种营养基准，无法确定对应列。请只截取所需的一列重新识别，或手动填写。")
            weightBases.singleOrNull()?.let {it>0 && it.isFinite()}==true -> {unit="g";basis=weightBases.single()}
            hasPortion -> {unit="份";basis=1.0}
            else -> warnings.add("未识别出每 100g／每份等基准，请对照包装填写。")
        }
        fun candidates(pattern:Regex,label:String,exclude:Regex?=null):List<String> = lines.filter {
            pattern.containsMatchIn(it) && (exclude==null || !exclude.containsMatchIn(it))
        }.also {if(it.size>1) warnings.add("$label 出现多行，已留空，请核对。")}
        fun grams(pattern:Regex,label:String,exclude:Regex?=null):Double? {
            val row=candidates(pattern,label,exclude).singleOrNull() ?: return null
            val tail=row.substring(pattern.find(row)!!.range.last+1)
            val values=gramValue.findAll(tail).toList()
            if(values.size!=1 || ambiguous(tail) || multipleBasis) return null
            return values.single().groupValues[1].toDoubleOrNull()?.takeIf {it.isFinite() && it>=0}
        }
        val carbs=grams(Regex("碳水化合物|碳水|carbohydrates?",RegexOption.IGNORE_CASE),"碳水")
        val protein=grams(Regex("蛋白质|蛋白質|protein",RegexOption.IGNORE_CASE),"蛋白质")
        val fat=grams(Regex("脂肪|total\\s*fat",RegexOption.IGNORE_CASE),"脂肪",Regex("饱和|飽和|反式|saturated|trans",RegexOption.IGNORE_CASE))
        val row=candidates(Regex("能量|热量|熱量|energy|calories",RegexOption.IGNORE_CASE),"热量").singleOrNull()
        val energies=row?.let {energyValue.findAll(it).toList()} ?: emptyList()
        var energy:Double?=null;var energyUnit=EnergyUnit.KCAL
        if(energies.size==1 && !multipleBasis && row?.let(::ambiguous)!=true) {
            val match=energies.single()
            energy=match.groupValues[1].toDoubleOrNull()?.takeIf {it.isFinite() && it>=0}
            energyUnit=if(match.groupValues[2].equals("kj",true) || match.groupValues[2]=="千焦") EnergyUnit.KJ else EnergyUnit.KCAL
        }
        val missing=listOf("热量" to energy,"碳水" to carbs,"蛋白质" to protein,"脂肪" to fat).filter {it.second==null}.map {it.first}
        if(missing.isNotEmpty()) warnings.add("${missing.joinToString("、")}未能可靠识别，已留空，请手动填写；不会用 NRV 百分比代替克数。")
        return NutritionLabelDraft(energy,energyUnit,carbs,protein,fat,unit,basis,warnings,text)
    }
    private fun ambiguous(text:String)=Regex("[<>≤≥~～]|\\d,\\d|[+-]\\s+\\d").containsMatchIn(text)
}
