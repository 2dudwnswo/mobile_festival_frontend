package com.festivalpub.admin.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** 표시용 포맷: 전화번호, 남은 시간, 금액 */
class FormatTest {

    private val sec = 1_000L
    private val min = 60_000L

    // ---------------- 전화번호 ----------------

    @Test
    fun `휴대폰 11자리는 3-4-4`() {
        assertEquals("010-1234-5678", formatPhone("01012345678"))
    }

    @Test
    fun `10자리는 3-3-4`() {
        assertEquals("011-123-4567", formatPhone("0111234567"))
    }

    @Test
    fun `그 외 길이는 그대로`() {
        assertEquals("", formatPhone(""))
        assertEquals("12345", formatPhone("12345"))
        assertEquals("010123456789", formatPhone("010123456789"))
    }

    // ---------------- 남은 시간 ----------------

    @Test
    fun `남은 시간은 분_초, 초는 두 자리`() {
        assertEquals("100:00", formatRemaining(100 * min))
        assertEquals("42:10", formatRemaining(42 * min + 10 * sec))
        assertEquals("0:05", formatRemaining(5 * sec))
    }

    @Test
    fun `분은 60을 넘어도 시간으로 바꾸지 않는다`() {
        assertEquals("130:00", formatRemaining(130 * min))
    }

    @Test
    fun `1초 미만은 버린다 (착석 직후 99분59초로 보임)`() {
        assertEquals("99:59", formatRemaining(100 * min - 1))
        assertEquals("0:00", formatRemaining(999))
    }

    @Test
    fun `정각 0은 부호 없이, 초과는 앞에 +`() {
        assertEquals("0:00", formatRemaining(0))
        assertEquals("+0:00", formatRemaining(-1))
        assertEquals("+3:05", formatRemaining(-(3 * min + 5 * sec)))
        assertEquals("+61:00", formatRemaining(-61 * min))
    }

    @Test
    fun `경과 시간은 음수면 0으로`() {
        assertEquals("3:14", formatElapsed(3 * min + 14 * sec))
        assertEquals("0:00", formatElapsed(-5 * sec))
    }

    @Test
    fun `몇 분 전은 내림, 음수는 0`() {
        assertEquals(0L, minutesAgo(59 * sec))
        assertEquals(1L, minutesAgo(min))
        assertEquals(15L, minutesAgo(15 * min + 59 * sec))
        assertEquals(0L, minutesAgo(-min))
    }

    // ---------------- 금액 ----------------

    @Test
    fun `금액은 천 단위 쉼표 + 원`() {
        assertEquals("0원", formatWon(0))
        assertEquals("5,000원", formatWon(5000))
        assertEquals("15,000원", formatWon(15000))
        assertEquals("1,234,567원", formatWon(1_234_567))
    }

    // ---------------- 시각 ----------------

    @Test
    fun `시각이 없으면 대시`() {
        assertEquals("-", formatClock(null))
    }
}
