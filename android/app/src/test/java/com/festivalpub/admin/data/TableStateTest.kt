package com.festivalpub.admin.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 테이블 상태 계산: 착석 순간 100분, 종료 15분 전 임박, 시간 경과 시 초과 (CLAUDE.md 3장) */
class TableStateTest {

    private val min = 60_000L
    private val seatedAt = 1_000_000_000L
    private val settings = Settings() // 회전 100분, 임박 15분

    private fun occupied(extended: Int = 0) =
        TableInfo(no = 1, status = "OCCUPIED", seatedAt = seatedAt, extendedMinutes = extended)

    private fun stateAt(elapsedMs: Long, extended: Int = 0) =
        occupied(extended).state(settings, seatedAt + elapsedMs)

    @Test
    fun `빈 테이블은 시간과 무관하게 빈자리`() {
        val t = TableInfo(no = 1)
        assertEquals(TableState.EMPTY, t.state(settings, seatedAt + 500 * min))
        assertNull(t.endAt(settings))
        assertNull(t.remainingMs(settings, seatedAt))
    }

    @Test
    fun `착석 순간 100분 타이머 시작`() {
        val t = occupied()
        assertEquals(seatedAt + 100 * min, t.endAt(settings))
        assertEquals(100 * min, t.remainingMs(settings, seatedAt))
        assertEquals(TableState.IN_USE, stateAt(0))
    }

    @Test
    fun `85분 직전까지 이용중, 85분 정각에 임박`() {
        assertEquals(TableState.IN_USE, stateAt(85 * min - 1))
        assertEquals(TableState.IMMINENT, stateAt(85 * min))
    }

    @Test
    fun `100분 직전까지 임박, 100분 정각에 초과`() {
        assertEquals(TableState.IMMINENT, stateAt(100 * min - 1))
        assertEquals(TableState.OVERTIME, stateAt(100 * min))
        assertEquals(TableState.OVERTIME, stateAt(180 * min))
    }

    @Test
    fun `10분 연장하면 경계가 95분(임박)과 110분(초과)으로 밀린다`() {
        assertEquals(seatedAt + 110 * min, occupied(10).endAt(settings))
        assertEquals(TableState.IN_USE, stateAt(85 * min, extended = 10))
        assertEquals(TableState.IN_USE, stateAt(95 * min - 1, extended = 10))
        assertEquals(TableState.IMMINENT, stateAt(95 * min, extended = 10))
        assertEquals(TableState.IMMINENT, stateAt(110 * min - 1, extended = 10))
        assertEquals(TableState.OVERTIME, stateAt(110 * min, extended = 10))
    }

    @Test
    fun `초과된 테이블도 연장하면 다시 이용중으로 돌아온다`() {
        assertEquals(TableState.OVERTIME, stateAt(101 * min))
        // +30분 (누적 연장): 종료 130분 → 101분 시점엔 남은 29분
        assertEquals(TableState.IN_USE, stateAt(101 * min, extended = 30))
    }

    @Test
    fun `연장은 누적 합계로 계산 (10+20+30 = 60분)`() {
        assertEquals(seatedAt + 160 * min, occupied(60).endAt(settings))
        assertEquals(TableState.IMMINENT, stateAt(145 * min, extended = 60))
    }

    @Test
    fun `서버 설정(회전·임박 시간)이 바뀌면 그 값으로 계산`() {
        val s = Settings(rotationMinutes = 90, imminentMinutes = 10)
        val t = occupied()
        assertEquals(TableState.IN_USE, t.state(s, seatedAt + 80 * min - 1))
        assertEquals(TableState.IMMINENT, t.state(s, seatedAt + 80 * min))
        assertEquals(TableState.OVERTIME, t.state(s, seatedAt + 90 * min))
    }

    @Test
    fun `OCCUPIED 인데 seatedAt 이 없으면 이용중으로 취급 (잘못된 데이터 방어)`() {
        val t = TableInfo(no = 1, status = "OCCUPIED", seatedAt = null)
        assertEquals(TableState.IN_USE, t.state(settings, seatedAt))
    }
}
