package com.festivalpub.admin.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 웨이팅 정렬(VIP 우선)과 주문 필터(PAID 만, 현재 착석 이후만) */
class WaitingOrderTest {

    private fun w(id: Int, createdAt: Long, vip: Boolean = false, status: String = "WAITING") =
        Waiting(id = id, phone = "0101111000$id", partySize = 2, isVip = vip, status = status, createdAt = createdAt)

    private fun o(
        id: Int,
        tableNo: Int = 7,
        createdAt: Long,
        pay: String = "PAID",
        cook: String = "WAITING",
        paidAt: Long? = null,
    ) = Order(id = id, tableNo = tableNo, createdAt = createdAt, paymentStatus = pay, cookStatus = cook, paidAt = paidAt)

    // ---------------- 웨이팅 ----------------

    @Test
    fun `VIP가 맨 위, 일반은 등록순 (시나리오 a)`() {
        val snap = Snapshot(
            waitings = listOf(w(1, 100), w(2, 200), w(3, 300), w(4, 400, vip = true)),
        )
        assertEquals(listOf(4, 1, 2, 3), snap.activeWaitings().map { it.id })
    }

    @Test
    fun `VIP가 여럿이면 VIP끼리도 등록순`() {
        val snap = Snapshot(
            waitings = listOf(w(1, 100), w(2, 500, vip = true), w(3, 300, vip = true)),
        )
        assertEquals(listOf(3, 2, 1), snap.activeWaitings().map { it.id })
    }

    @Test
    fun `호출됨(CALLED)은 대기 목록에 남고 무응답·착석·취소는 빠진다`() {
        val snap = Snapshot(
            waitings = listOf(
                w(1, 100, status = "CALLED"),
                w(2, 200, status = "NO_SHOW"),
                w(3, 300, status = "SEATED"),
                w(4, 400, status = "CANCELLED"),
                w(5, 500),
            ),
        )
        assertEquals(listOf(1, 5), snap.activeWaitings().map { it.id })
        assertEquals(listOf(2), snap.noShowWaitings().map { it.id })
    }

    @Test
    fun `무응답 팀이 대기로 복귀하면 createdAt 순서 그대로 원래 자리 (시나리오 b)`() {
        val before = Snapshot(waitings = listOf(w(1, 100), w(2, 200, status = "NO_SHOW"), w(3, 300)))
        assertEquals(listOf(1, 3), before.activeWaitings().map { it.id })
        val restored = before.copy(waitings = before.waitings.map { if (it.id == 2) it.copy(status = "WAITING") else it })
        assertEquals(listOf(1, 2, 3), restored.activeWaitings().map { it.id })
        assertTrue(restored.noShowWaitings().isEmpty())
    }

    // ---------------- 주문: 앱은 PAID 만 ----------------

    private val table = TableInfo(no = 7, status = "OCCUPIED", seatedAt = 1_000)

    @Test
    fun `테이블 주문 내역은 현재 착석 이후의 PAID 주문만`() {
        val snap = Snapshot(
            orders = listOf(
                o(1, createdAt = 500),                   // 이전 손님 (착석 전)
                o(2, createdAt = 1_000),                 // 착석 순간 = 포함
                o(3, createdAt = 1_500, pay = "PENDING"), // 입금 전 → 무시
                o(4, createdAt = 1_600, pay = "CANCELLED"),
                o(5, createdAt = 1_700, cook = "DONE"),  // 조리완료도 내역에는 남음
                o(6, tableNo = 8, createdAt = 1_800),    // 다른 테이블
            ),
        )
        assertEquals(listOf(2, 5), snap.ordersForTable(table).map { it.id })
    }

    @Test
    fun `주문 내역은 주문 시각 순`() {
        val snap = Snapshot(orders = listOf(o(1, createdAt = 3_000), o(2, createdAt = 2_000)))
        assertEquals(listOf(2, 1), snap.ordersForTable(table).map { it.id })
    }

    @Test
    fun `빈 테이블은 주문 내역이 없다`() {
        val snap = Snapshot(orders = listOf(o(1, createdAt = 2_000)))
        assertTrue(snap.ordersForTable(TableInfo(no = 7)).isEmpty())
    }

    @Test
    fun `다음 손님 착석 후엔 이전 손님 주문이 섞이지 않는다 (시나리오 f)`() {
        val next = table.copy(seatedAt = 5_000)
        val snap = Snapshot(orders = listOf(o(1, createdAt = 2_000), o(2, createdAt = 6_000)))
        assertEquals(listOf(2), snap.ordersForTable(next).map { it.id })
    }

    @Test
    fun `서버가 PENDING 을 PAID 로 바꾸면 그때 나타난다`() {
        val pending = Snapshot(orders = listOf(o(1, createdAt = 2_000, pay = "PENDING")))
        assertTrue(pending.ordersForTable(table).isEmpty())
        assertTrue(pending.kitchenOrders().isEmpty())
        val paid = Snapshot(orders = listOf(o(1, createdAt = 2_000, paidAt = 2_500)))
        assertEquals(listOf(1), paid.ordersForTable(table).map { it.id })
        assertEquals(listOf(1), paid.kitchenOrders().map { it.id })
    }

    @Test
    fun `주방은 PAID 이면서 조리 전인 것만, 입금 순서대로`() {
        val snap = Snapshot(
            orders = listOf(
                o(1, createdAt = 100, paidAt = 900),
                o(2, createdAt = 200, paidAt = 300),
                o(3, createdAt = 300, pay = "PENDING"),
                o(4, createdAt = 400, pay = "CANCELLED"),
                o(5, createdAt = 500, paidAt = 600, cook = "DONE"),
                o(6, tableNo = 3, createdAt = 50, paidAt = 700), // 테이블과 무관하게 모두
            ),
        )
        assertEquals(listOf(2, 6, 1), snap.kitchenOrders().map { it.id })
    }

    @Test
    fun `paidAt 이 없으면 주문 시각으로 정렬`() {
        val snap = Snapshot(orders = listOf(o(1, createdAt = 900), o(2, createdAt = 100, paidAt = 500)))
        assertEquals(listOf(2, 1), snap.kitchenOrders().map { it.id })
    }

    // ---------------- 주방 새 주문 알림음 ----------------

    @Test
    fun `주방 화면을 처음 열 때는 알림음 없음`() {
        assertFalse(hasNewKitchenOrder(null, setOf(1, 2)))
    }

    @Test
    fun `새 주문 id 가 생기면 알림음, 조리완료로 줄기만 하면 없음`() {
        assertTrue(hasNewKitchenOrder(setOf(1), setOf(1, 2)))
        assertFalse(hasNewKitchenOrder(setOf(1, 2), setOf(2)))
        assertFalse(hasNewKitchenOrder(setOf(1, 2), setOf(1, 2)))
        // 하나 완료 + 하나 새로 들어옴 (개수는 같아도 새 주문)
        assertTrue(hasNewKitchenOrder(setOf(1, 2), setOf(2, 3)))
    }
}
