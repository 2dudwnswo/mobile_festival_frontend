package com.festivalpub.admin.data

import org.junit.Assert.*
import org.junit.Test
import java.time.OffsetDateTime

class FirestoreMapperTest {
    private fun epoch(s: String) = OffsetDateTime.parse(s).toInstant().toEpochMilli()
    private val empty = mapOf<String, Any?>("table_no" to 1L, "status" to "EMPTY", "start_time" to null,
        "payment_confirmed" to false, "extended_minutes" to 0L, "total_amount" to 0L)
    private val waiting = mapOf<String, Any?>("phone" to "01000000001", "party_size" to 3L, "is_vip" to true,
        "status" to "WAITING", "created_at" to 123L, "called_at" to null)
    private val line = OrderLine("auto-order", 1, "menu-string", "메뉴", 3_000_000_000L, 2, "영준", createdAt = 1234)

    @Test fun `테이블 문자열 ID와 숫자 table_no 일치`() {
        assertEquals(TableInfo(1), FirestoreMapper.table("1", empty))
        assertNull(FirestoreMapper.table("2", empty))
        assertNull(FirestoreMapper.table("1", empty - "table_no"))
    }
    @Test fun `착석 시각과 금액은 Long`() {
        val t = FirestoreMapper.table("1", empty + mapOf("status" to "IN_USE", "start_time" to 123L,
            "total_amount" to 3_000_000_000L, "extended_minutes" to 20L, "payment_confirmed" to true))!!
        assertTrue(t.occupied); assertEquals(123L, t.startTime); assertEquals(3_000_000_000L, t.totalAmount)
    }
    @Test fun `과거 상태와 시각 없는 착석은 거절`() {
        assertNull(FirestoreMapper.table("1", empty + ("status" to "OCCUPIED")))
        assertNull(FirestoreMapper.table("1", empty + ("status" to "IN_USE")))
    }
    @Test fun `시각의 문자열과 소수는 임의 변환하지 않는다`() {
        assertNull(FirestoreMapper.waiting("auto", waiting + ("created_at" to "123")))
        assertNull(FirestoreMapper.waiting("auto", waiting + ("created_at" to 123.5)))
    }
    @Test fun `자동 ID 웨이팅과 문서 is_vip 사용`() {
        val w = FirestoreMapper.waiting("aRandomId", waiting)!!
        assertEquals("aRandomId", w.key); assertTrue(w.isVip); assertEquals(3L, w.partySize)
        assertFalse(FirestoreMapper.waiting("another", waiting + ("is_vip" to false))!!.isVip)
    }
    @Test fun `웨이팅 NO_SHOW 허용 CALLED 거절`() {
        assertEquals("NO_SHOW", FirestoreMapper.waiting("a", waiting + ("status" to "NO_SHOW"))!!.status)
        assertNull(FirestoreMapper.waiting("a", waiting + ("status" to "CALLED")))
    }
    @Test fun `메뉴 ID는 숫자로 변환하지 않고 금액은 Long`() {
        assertEquals(MenuItem("abc", "메뉴", 3_000_000_000), FirestoreMapper.menuItem("abc", mapOf("name" to "메뉴", "price" to 3_000_000_000L)))
    }
    @Test fun `한 주문 문서 한 줄 왕복과 32비트 초과 합계`() {
        assertEquals(line, FirestoreMapper.order(line.id, FirestoreMapper.orderDoc(line)))
        assertEquals(6_000_000_000, line.total)
    }
    @Test fun `주문 필수 필드 누락은 거절`() {
        assertNull(FirestoreMapper.order("auto", FirestoreMapper.orderDoc(line) - "menu_id"))
        assertNull(FirestoreMapper.order("auto", FirestoreMapper.orderDoc(line) + ("quantity" to 0L)))
    }
    @Test fun `주문 금액 곱 오버플로 거절`() {
        assertNull(FirestoreMapper.order("auto", FirestoreMapper.orderDoc(line) + mapOf("menu_price" to Long.MAX_VALUE, "quantity" to 2L)))
    }
    @Test fun `착석은 상태와 기기 millis만 기록`() {
        assertEquals(mapOf("status" to "SEATED_PENDING_PAYMENT", "start_time" to 123L), FirestoreMapper.seatFields(123))
    }
    @Test fun `입금확인은 start_time과 합계를 변경하지 않는다`() {
        assertEquals(mapOf("status" to "IN_USE", "payment_confirmed" to true), FirestoreMapper.confirmPaymentFields())
    }
    @Test fun `종료는 v2의 다섯 필드만 초기화`() {
        assertEquals(empty - "table_no", FirestoreMapper.releaseFields())
    }
    @Test fun `호출은 상태를 변경하지 않는다`() {
        assertEquals(mapOf("called_at" to 100L), FirestoreMapper.callFields(100))
    }
    @Test fun `복귀는 등록과 호출 시각을 보존한다`() {
        assertEquals(mapOf("status" to "WAITING"), FirestoreMapper.waitingStatusFields("WAITING"))
    }
    @Test fun `VIP는 스펙의 여섯 필드만 생성`() {
        assertEquals(waiting, FirestoreMapper.vipWaitingDoc("01000000001", 3, 123))
    }
    @Test fun `주문에는 담당자 added_by만 기록`() {
        assertEquals(setOf("table_id", "menu_id", "menu_name", "menu_price", "quantity", "added_by", "status", "created_at"), FirestoreMapper.orderDoc(line).keys)
    }
    @Test fun `조리완료는 상태만 변경`() {
        assertEquals(mapOf("status" to "DONE"), FirestoreMapper.cookedFields())
    }
    @Test fun `자정 전후와 다음 날 01시는 같은 영업일`() {
        val expected = epoch("2026-09-19T06:00:00+09:00")
        listOf("2026-09-19T18:00:00+09:00", "2026-09-19T23:59:59+09:00", "2026-09-20T00:00:00+09:00", "2026-09-20T01:00:00+09:00").forEach {
            assertEquals(expected, FirestoreMapper.businessDayStartKst(epoch(it)))
        }
    }
    @Test fun `오전 6시 정각 영업일 전환`() {
        val six = epoch("2026-09-20T06:00:00+09:00")
        assertEquals(six - 86_400_000L, FirestoreMapper.businessDayStartKst(six - 1))
        assertEquals(six, FirestoreMapper.businessDayStartKst(six))
    }
    @Test fun `UTC 입력도 한국 시간 경계 사용`() {
        assertEquals(epoch("2026-09-19T06:00:00+09:00"), FirestoreMapper.businessDayStartKst(epoch("2026-09-19T16:00:00Z")))
    }
}
