package com.fitledger.ui

import com.fitledger.data.Food
import com.fitledger.domain.Nutrition
import org.junit.Assert.*
import org.junit.Test

class FoodConvenienceTest {
    private fun food(id:Long,name:String,favorite:Boolean=false,tags:String="")=Food(id,name,"g",100.0,Nutrition(100.0,10.0,10.0,2.0),10.0,500.0,true,favorite=favorite,nutritionTags=tags)
    @Test fun searchCombinesWordsFavoritesAndTagsWithoutDuplicatingFood() {
        val a=food(1,"ABC 鸡胸肉",true,"PROTEIN,FAT");val b=food(2,"鸡胸肉",false,"PROTEIN")
        assertEquals(listOf(a),searchFoods(listOf(a,b),"abc 蛋白质",true))
        assertEquals(listOf(a,b),searchFoods(listOf(a,b),"鸡胸肉"))
        assertEquals(listOf(a),searchFoods(listOf(a,b),"",true))
    }
    @Test fun filteredSortLeavesHiddenFoodsInTheirSlots() {
        assertEquals(listOf(5L,2L,3L,4L,1L),mergeFilteredOrder(listOf(1L,2L,3L,4L,5L),listOf(5L,3L,1L)))
        assertEquals(listOf("zone:2","food:1","zone:1"),mergeFilteredOrder(listOf("zone:1","food:1","zone:2"),listOf("zone:2","zone:1")))
        assertThrows(IllegalArgumentException::class.java) {mergeFilteredOrder(listOf(1,2),listOf(3))}
    }
}
