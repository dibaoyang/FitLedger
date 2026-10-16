package com.fitledger.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class BirthdayParserTest {
    private val today=LocalDate.of(2026,10,11).toEpochDay()

    @Test fun requestedFormatsProduceSameStoredBirthday() {
        val expected=LocalDate.of(2000,1,1)
        listOf("2000年1月1日","2000.1.1","2000-1-1","2000-01-01").forEach {
            val parsed=BirthdayParser.parse(it,today)
            assertEquals(expected,parsed)
            assertEquals(expected.toEpochDay(),parsed.toEpochDay())
            assertEquals("2000年1月1日",BirthdayParser.display(parsed))
            assertEquals(26,AgeCalculator.age(parsed.toEpochDay(),today))
        }
    }

    @Test fun acceptsWhitespaceAroundInputAndChineseComponents() {
        val expected=LocalDate.of(2000,1,1)
        listOf(" 2000.1.1 ","2000 年 1 月 1 日","2000年01月01日","2000.01.01").forEach {
            assertEquals(expected,BirthdayParser.parse(it,today))
        }
    }

    @Test fun completedYearsChangeAtBirthdayAndWhenBirthdayIsEdited() {
        val birthday=BirthdayParser.parse("2000年10月11日",today).toEpochDay()
        assertEquals(25,AgeCalculator.age(birthday,today-1))
        assertEquals(26,AgeCalculator.age(birthday,today))
        assertEquals(25,AgeCalculator.age(BirthdayParser.parse("2000.10.12",today).toEpochDay(),today))
    }

    @Test fun leapDayIsStrictAndUsesCompletedCalendarYears() {
        val birthday=BirthdayParser.parse("2000.2.29",today)
        assertEquals(LocalDate.of(2000,2,29),birthday)
        assertEquals(24,AgeCalculator.age(birthday.toEpochDay(),LocalDate.of(2025,2,28).toEpochDay()))
        assertEquals(25,AgeCalculator.age(birthday.toEpochDay(),LocalDate.of(2025,3,1).toEpochDay()))
        assertTrue(runCatching {BirthdayParser.parse("2001年2月29日",today)}.isFailure)
    }

    @Test fun rejectsImpossibleDatesWithoutRollingToAnotherMonth() {
        listOf("2000-02-30","2000.4.31","2000年13月1日","2000-0-1","2000-1-0","2000-1-32").forEach {
            assertTrue("Must reject $it",runCatching {BirthdayParser.parse(it,today)}.isFailure)
        }
    }

    @Test fun rejectsPartialMixedAndAmbiguousFormats() {
        listOf("","2000","00-1-1","2000-1","2000-01-01 trailing","2000.1-1","2000/1/1","1-1-2000","2000年1月1").forEach {
            assertTrue("Must reject $it",runCatching {BirthdayParser.parse(it,today)}.isFailure)
        }
    }

    @Test fun futureAndOutOfRangeBirthdaysAreRejectedButTodayIsValid() {
        assertEquals(LocalDate.ofEpochDay(today),BirthdayParser.parse("2026年10月11日",today))
        assertTrue(runCatching {BirthdayParser.parse("2026-10-12",today)}.isFailure)
        assertTrue(runCatching {BirthdayParser.parse("1900.1.1",today)}.isFailure)
    }
}
