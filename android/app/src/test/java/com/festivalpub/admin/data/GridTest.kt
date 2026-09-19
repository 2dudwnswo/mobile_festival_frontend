package com.festivalpub.admin.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 대시보드 격자: 타일 한 변 = min(가로 폭 ÷ 열 수, 사용 가능한 높이 ÷ 줄 수) */
class GridTest {

    private val eps = 0.001f

    @Test
    fun `간격이 없으면 가로·세로 중 작은 쪽`() {
        assertEquals(50f, gridTileSize(300f, 1000f, rows = 5, cols = 6, gap = 0f), eps) // 폭 기준
        assertEquals(40f, gridTileSize(1000f, 240f, rows = 6, cols = 3, gap = 0f), eps) // 높이 기준
    }

    @Test
    fun `간격은 먼저 빼고 나눈다`() {
        // 폭 391, 열 6, 간격 6 → (391 - 30) / 6 = 60.1666
        assertEquals(60.1667f, gridTileSize(391f, 1000f, rows = 5, cols = 6, gap = 6f), eps)
        // 높이 560, 줄 6, 간격 6 → (560 - 30) / 6 = 88.333
        assertEquals(88.3333f, gridTileSize(391f, 560f, rows = 6, cols = 3, gap = 6f), eps)
    }

    @Test
    fun `요청된 네 가지 배치가 모두 화면 안에 들어간다`() {
        // 에뮬레이터(411dp 폭) 테이블 탭의 격자 영역 ≈ 391 × 560 dp, 간격 6dp
        val w = 391f
        val h = 560f
        val gap = 6f
        for ((rows, cols) in listOf(5 to 6, 4 to 5, 6 to 3, 3 to 10)) {
            val tile = gridTileSize(w, h, rows, cols, gap)
            val usedW = tile * cols + gap * (cols - 1)
            val usedH = tile * rows + gap * (rows - 1)
            assertTrue("${rows}x$cols 폭 초과: $usedW", usedW <= w + eps)
            assertTrue("${rows}x$cols 높이 초과: $usedH", usedH <= h + eps)
            assertTrue("${rows}x$cols 타일 크기 0", tile > 0f)
        }
    }

    @Test
    fun `공간이 모자라면 0 (음수가 되지 않음)`() {
        assertEquals(0f, gridTileSize(10f, 10f, rows = 3, cols = 10, gap = 6f), eps)
    }

    @Test
    fun `열이나 줄이 0 이하로 와도 1 로 취급`() {
        assertEquals(100f, gridTileSize(100f, 200f, rows = 0, cols = 0, gap = 6f), eps)
    }

    @Test
    fun `줄 수는 서버 rows, 테이블이 더 많으면 늘린다`() {
        assertEquals(5, gridRows(tableCount = 30, rows = 5, cols = 6))
        assertEquals(6, gridRows(tableCount = 18, rows = 6, cols = 3))
        assertEquals(6, gridRows(tableCount = 31, rows = 5, cols = 6)) // 한 줄 추가
        assertEquals(3, gridRows(tableCount = 10, rows = 3, cols = 10)) // 테이블이 적어도 rows 유지
        assertEquals(1, gridRows(tableCount = 0, rows = 0, cols = 0))
    }
}
