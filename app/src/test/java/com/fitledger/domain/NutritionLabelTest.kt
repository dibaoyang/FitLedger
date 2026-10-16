package com.fitledger.domain

import org.junit.Assert.*
import org.junit.Test

class NutritionLabelTest {
    private fun parse(vararg rows:String)=NutritionLabelParser.parse(rows.toList())
    @Test fun chineseKjTableUsesGramsNotNrvAndKeepsCanonicalConversion() {
        val d=parse("项目 每100g NRV%","能量 836.8 kJ 10%","蛋白质 12.5g 21%","脂肪 0g 0%","碳水化合物 37.5g 13%","钠 500mg 25%")
        assertEquals("g",d.unit);assertEquals(100.0,d.basis!!,0.0)
        assertEquals(200.0,d.energyUnit.toKcal(d.energy!!),1e-8);assertEquals(EnergyUnit.KJ,d.energyUnit)
        assertEquals(12.5,d.protein!!,0.0);assertEquals(37.5,d.carbs!!,0.0);assertEquals(0.0,d.fat!!,0.0)
        assertTrue(d.warnings.isEmpty())
    }
    @Test fun fullWidthCharactersAndPortionsAreRecognized() {
        val d=parse("每份","能量 ２００ 千卡 １０％","蛋白质 １０．５ 克","脂肪 ４ 克","碳水 ３０ 克")
        assertEquals("份",d.unit);assertEquals(1.0,d.basis!!,0.0)
        assertEquals(200.0,d.energy!!,0.0);assertEquals(10.5,d.protein!!,0.0)
    }
    @Test fun multipleColumnsAreLeftBlankInsteadOfChoosingOne() {
        val d=parse("每100g 每份","能量 100 kJ 200 kJ","蛋白质 3g 6g","脂肪 1g 2g","碳水 10g 20g")
        assertNull(d.energy);assertNull(d.protein);assertNull(d.fat);assertNull(d.carbs);assertNull(d.basis)
        assertTrue(d.warnings.any {it.contains("多种")})
    }
    @Test fun volumeNeverSilentlyBecomesWeight() {
        val d=parse("每100mL","能量 300kJ","蛋白质 3g","脂肪 4g","碳水 5g")
        assertNull(d.unit);assertNull(d.basis);assertEquals(3.0,d.protein!!,0.0)
        assertTrue(d.warnings.any {it.contains("毫升")})
    }
    @Test fun missingValuesPercentOnlyAndSaturatedFatDoNotFillMacros() {
        val d=parse("每100g","能量 10%","蛋白质 20%","饱和脂肪 2g","反式脂肪 0g","碳水 20")
        assertNull(d.energy);assertNull(d.protein);assertNull(d.fat);assertNull(d.carbs)
    }
    @Test fun duplicateRowsDoNotGuessAndFatSubtypesDoNotReplaceTotal() {
        val d=parse("每100g","脂肪 5g","饱和脂肪 1g","蛋白质 2g","蛋白质 3g")
        assertEquals(5.0,d.fat!!,0.0);assertNull(d.protein)
    }
    @Test fun negativeBoundsMalformedDecimalsAndApproximateAmountsStayBlank() {
        for(value in listOf("-3g","- 3g","<0.5g","≤0.5g","~3g","1,5g","1.2.3g")) {
            assertNull(value,parse("每100g","蛋白质 $value").protein)
        }
    }
    @Test fun EnglishAndNonHundredGramBasisAreSupported() {
        val d=parse("per 50 g","Energy 100 kcal","Protein 5 g","Total Fat 2 g","Carbohydrate 15 g")
        assertEquals(50.0,d.basis!!,0.0);assertEquals(100.0,d.energy!!,0.0);assertEquals(2.0,d.fat!!,0.0)
    }
    @Test fun missingHeaderDoesNotInventBasis() {
        val d=parse("能量 100kcal","蛋白质 5g")
        assertNull(d.basis);assertNull(d.unit);assertEquals(5.0,d.protein!!,0.0)
    }
}
