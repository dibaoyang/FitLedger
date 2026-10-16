package com.fitledger.domain

import java.time.DateTimeException
import java.time.LocalDate

/** Parse human input strictly; persisted birthdays remain epoch-day values. */
object BirthdayParser {
    private val formats=listOf(
        Regex("^([0-9]{4})-([0-9]{1,2})-([0-9]{1,2})$"),
        Regex("^([0-9]{4})\\.([0-9]{1,2})\\.([0-9]{1,2})$"),
        Regex("^([0-9]{4})\\s*年\\s*([0-9]{1,2})\\s*月\\s*([0-9]{1,2})\\s*日$")
    )

    fun parse(input:String,day:Long):LocalDate {
        val match=formats.firstNotNullOfOrNull {it.matchEntire(input.trim())}
        requireNotNull(match) {"生日支持 2000年1月1日、2000.1.1 或 2000-01-01"}
        val date=try {
            LocalDate.of(match.groupValues[1].toInt(),match.groupValues[2].toInt(),match.groupValues[3].toInt())
        } catch (_:DateTimeException) {
            throw IllegalArgumentException("生日日期不存在，请检查年、月、日")
        }
        AgeCalculator.age(date.toEpochDay(),day)
        return date
    }

    fun display(date:LocalDate):String="${date.year}年${date.monthValue}月${date.dayOfMonth}日"
}
