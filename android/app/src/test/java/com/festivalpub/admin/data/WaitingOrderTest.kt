package com.festivalpub.admin.data

import org.junit.Assert.*
import org.junit.Test

class WaitingOrderTest {
    private fun w(id: String, time: Long, vip: Boolean = false, status: String = "WAITING", called: Long? = null) =
        Waiting(id, "01000000001", 2, vip, status, time, called)
    private fun o(id: String, time: Long, table: Int = 7, status: String = "PENDING", price: Long = 5000, qty: Long = 2) =
        OrderLine(id, table, "1", "메뉴", price, qty, "영준", status, time)
    private val table = TableInfo(7, "SEATED_PENDING_PAYMENT", 1000)

    @Test fun `VIP 먼저 일반은 등록순`() {
        val s = Snapshot(waitings = listOf(w("a", 100), w("b", 200), w("v", 300, true)))
        assertEquals(listOf("v", "a", "b"), s.activeWaitings().map { it.key })
    }
    @Test fun `VIP끼리 등록순`() {
        val s = Snapshot(waitings = listOf(w("v2", 200, true), w("v1", 100, true)))
        assertEquals(listOf("v1", "v2"), s.activeWaitings().map { it.key })
    }
    @Test fun `호출된 WAITING은 남고 무응답은 분리`() {
        val s = Snapshot(waitings = listOf(w("a", 100, called = 200), w("b", 100, status = "NO_SHOW"),
            w("c", 100, status = "SEATED"), w("d", 100, status = "CANCELLED")))
        assertEquals(listOf("a"), s.activeWaitings().map { it.key })
        assertEquals(listOf("b"), s.noShowWaitings().map { it.key })
    }
    @Test fun `복귀 시 원래 등록순`() {
        val b = w("b", 200, status = "NO_SHOW")
        val s = Snapshot(waitings = listOf(w("a", 100), b.copy(status = "WAITING"), w("c", 300)))
        assertEquals(listOf("a", "b", "c"), s.activeWaitings().map { it.key })
    }
    @Test fun `현재 착석 이후 주문은 입금과 무관하게 포함`() {
        val s = Snapshot(orders = listOf(o("old", 999), o("now", 1000), o("done", 1001, status = "DONE"), o("other", 1002, table = 8)))
        assertEquals(listOf("now", "done"), s.ordersForTable(table).map { it.id })
    }
    @Test fun `주문 내역은 등록순`() {
        assertEquals(listOf("a", "b"), Snapshot(orders = listOf(o("b", 2000), o("a", 1500))).ordersForTable(table).map { it.id })
    }
    @Test fun `빈 테이블 내역 없음`() {
        assertTrue(Snapshot(orders = listOf(o("a", 2000))).ordersForTable(TableInfo(7)).isEmpty())
    }
    @Test fun `새 손님에게 이전 주문이 섞이지 않는다`() {
        val s = Snapshot(orders = listOf(o("old", 2000), o("new", 6000)))
        assertEquals(listOf("new"), s.ordersForTable(table.copy(startTime = 5000)).map { it.id })
    }
    @Test fun `주방은 입금 안 된 테이블 주문도 즉시 표시`() {
        val s = Snapshot(tables = listOf(table), pendingOrders = listOf(o("a", 1000)))
        assertFalse(table.paymentConfirmed)
        assertEquals(listOf("a"), s.kitchenOrders().map { it.id })
    }
    @Test fun `주방은 PENDING만 등록순`() {
        val s = Snapshot(pendingOrders = listOf(o("b", 2000), o("done", 900, status = "DONE"), o("a", 1000)))
        assertEquals(listOf("a", "b"), s.kitchenOrders().map { it.id })
    }
    @Test fun `같은 테이블 같은 시각만 묶는다`() {
        val s = Snapshot(pendingOrders = listOf(o("a", 1000), o("b", 1000), o("c", 1000, table = 8), o("d", 1001)))
        assertEquals(3, s.kitchenGroups().size)
        assertEquals(listOf("a", "b"), s.kitchenGroups().first().items.map { it.id })
    }
    @Test fun `처음 열면 주방 알림 없음`() { assertFalse(hasNewKitchenOrder(null, setOf("a"))) }
    @Test fun `새 줄 추가만 알림 완료로 제거된 줄은 무음`() {
        assertTrue(hasNewKitchenOrder(setOf("a"), setOf("a", "b")))
        assertFalse(hasNewKitchenOrder(setOf("a", "b"), setOf("a")))
        assertTrue(hasNewKitchenOrder(setOf("a"), setOf("b")))
    }
    @Test fun `대기번호는 ID와 분리`() {
        val a = w("random-auto-id", 100); val v = w("vip-auto-id", 200, true)
        val s = Snapshot(waitings = listOf(a, v))
        assertEquals("1", s.waitingLabel(a)); assertEquals("VIP", s.waitingLabel(v))
    }
    @Test fun `무응답 버튼은 호출 3분 정각부터`() {
        val a = w("a", 100, called = 1000)
        assertFalse(a.canMarkNoShow(180999)); assertTrue(a.canMarkNoShow(181000))
        assertFalse(a.copy(calledAt = null).canMarkNoShow(999999))
        assertFalse(a.copy(status = "NO_SHOW").canMarkNoShow(999999))
    }
    @Test fun `입금 미확인 배지는 착석 5분 정각부터`() {
        assertFalse(table.paymentOverdue(300999)); assertTrue(table.paymentOverdue(301000))
        assertFalse(table.copy(paymentConfirmed = true, status = "IN_USE").paymentOverdue(301000))
    }
    @Test fun `입금확인 전후 타이머는 동일`() {
        val paid = table.copy(status = "IN_USE", paymentConfirmed = true)
        assertEquals(table.endAt(Settings()), paid.endAt(Settings()))
        assertEquals(TableState.OVERTIME, paid.state(Settings(), 1000 + 100 * 60000L))
        assertEquals(TableState.OVERTIME, table.state(Settings(), 1000 + 100 * 60000L))
    }
    @Test fun `종료는 화면에서 본 착석 시각과 같아야 한다`() {
        assertTrue(canRelease(table, 1000)); assertFalse(canRelease(table, null))
        assertFalse(canRelease(table.copy(startTime = 5000), 1000))
        assertFalse(canRelease(TableInfo(7), 1000))
    }
    @Test fun `여러 줄 합계는 Long으로 계산`() {
        assertEquals(6_000_010_000L, orderTotal(listOf(o("a", 1, price = 3_000_000_000), o("b", 1))))
    }
    @Test(expected = ArithmeticException::class) fun `주문 합계 오버플로는 전송 전 거절`() {
        orderTotal(listOf(o("a", 1, price = Long.MAX_VALUE)))
    }
    @Test fun `주방 날짜 제한은 내역 조회와 독립`() {
        val s = Snapshot(orders = listOf(o("today", 1000, status = "DONE")), pendingOrders = listOf(o("yesterday", 1)))
        assertEquals("yesterday", s.kitchenOrders().single().id)
        assertEquals("today", s.ordersForTable(table).single().id)
    }
}
