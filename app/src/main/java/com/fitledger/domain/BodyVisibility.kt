package com.fitledger.domain

enum class BodyField(val key:String,val label:String) {
    GENDER("showGender","性别"), HEIGHT("showHeight","身高"), BIRTHDAY("showBirthday","生日"),
    AGE("showAge","年龄"), WEIGHT("showWeight","体重"), BODY_FAT("showBodyFat","体脂率"),
    LEAN("showLeanBodyMass","瘦体重"), MUSCLE("showMuscleMass","肌肉量"), BMR("showBmr","BMR")
}
data class BodyVisibility(val values:Map<BodyField,Boolean> = BodyField.entries.associateWith {true}) {
    fun shows(field:BodyField)=values[field] ?: true
    fun with(field:BodyField,show:Boolean)=copy(values=values+(field to show))
    companion object {fun all(show:Boolean)=BodyVisibility(BodyField.entries.associateWith {show})}
}
