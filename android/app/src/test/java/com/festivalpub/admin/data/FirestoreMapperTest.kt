package com.festivalpub.admin.data

import org.junit.Assert.*
import org.junit.Test
import java.time.OffsetDateTime

class FirestoreMapperTest {
    private fun epoch(s: String) = OffsetDateTime.parse(s).toInstant().toEpochMilli()
    private val empty = mapOf<String, Any?>("table_no" to 1L, "status" to "EMPTY", "start_time" to null,
        "payment_confirmed" to false, "extended_minutes" to 0L, "total_amount" to 0L)
    private val waiting = mapOf<String, Any?>("phone" to "01000000001", "party_size" to 3L, "is_vip" to true,
        "status" to "WAITING", "created_at" to 123L, "called_at" to null, "public_id" to "pub-1")
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
        assertNull(FirestoreMapper.waiting("01000000001", waiting + ("created_at" to "123")))
        assertNull(FirestoreMapper.waiting("01000000001", waiting + ("created_at" to 123.5)))
    }
    @Test fun `v3 waiting_private - 문서 ID 가 전화번호, public_id 연결, 문서 is_vip 사용`() {
        val w = FirestoreMapper.waiting("01000000001", waiting)!!
        assertEquals("01000000001", w.key); assertEquals("01000000001", w.phone)
        assertTrue(w.isVip); assertEquals(3L, w.partySize); assertEquals("pub-1", w.publicId)
        assertFalse(FirestoreMapper.waiting("01000000001", waiting + ("is_vip" to false))!!.isVip)
    }
    @Test fun `v3 public_id 가 없거나 비어 있어도 목록에서 빠지지 않는다`() {
        assertNull(FirestoreMapper.waiting("01000000001", waiting - "public_id")!!.publicId)
        assertNull(FirestoreMapper.waiting("01000000001", waiting + ("public_id" to ""))!!.publicId)
    }
    @Test fun `v3 문서 ID 가 전화번호 형식이 아니거나 phone 필드와 다르면 거절`() {
        assertNull(FirestoreMapper.waiting("aRandomId", waiting))
        assertNull(FirestoreMapper.waiting("010-0000-0001", waiting))
        assertNull(FirestoreMapper.waiting("01000000002", waiting))
        // phone 필드가 없으면 문서 ID 를 쓴다
        assertEquals("01000000001", FirestoreMapper.waiting("01000000001", waiting - "phone")!!.phone)
    }
    @Test fun `웨이팅 NO_SHOW 허용 CALLED 거절`() {
        assertEquals("NO_SHOW", FirestoreMapper.waiting("01000000001", waiting + ("status" to "NO_SHOW"))!!.status)
        assertNull(FirestoreMapper.waiting("01000000001", waiting + ("status" to "CALLED")))
    }
    @Test fun `전화번호 정규화 - 숫자만, 하이픈·공백 제거, 길이 확인`() {
        assertEquals("01000000001", FirestoreMapper.normalizePhone("010-0000-0001"))
        assertEquals("01000000001", FirestoreMapper.normalizePhone(" 010 0000 0001 "))
        assertEquals("0212345678", FirestoreMapper.normalizePhone("02-1234-5678"))
        assertNull(FirestoreMapper.normalizePhone("010-000"))
        assertNull(FirestoreMapper.normalizePhone("010000000012"))
        assertNull(FirestoreMapper.normalizePhone(""))
    }
    @Test fun `상태 변경 대상 - public_id 가 있으면 private·public 둘 다 같은 status`() {
        for (status in listOf("NO_SHOW", "WAITING", "CANCELLED", "SEATED")) {
            val plan = FirestoreMapper.statusPlan("pub-1", status)
            assertEquals(mapOf("status" to status), plan.privateFields)
            assertEquals("pub-1", plan.publicId)
            assertEquals(plan.privateFields, plan.publicFields)
        }
    }
    @Test fun `상태 변경 대상 - public_id 가 없거나 비면 private 만`() {
        for (pid in listOf(null, "", "  ")) {
            val plan = FirestoreMapper.statusPlan(pid, "CANCELLED")
            assertNull(plan.publicId); assertNull(plan.publicFields)
            assertEquals(mapOf("status" to "CANCELLED"), plan.privateFields)
        }
    }
    @Test fun `VIP 등록 가능 여부 - 없음·CANCELLED·SEATED 는 등록, WAITING·NO_SHOW 는 차단`() {
        assertTrue(FirestoreMapper.canRegisterVip(null))
        assertTrue(FirestoreMapper.canRegisterVip("CANCELLED"))
        assertTrue(FirestoreMapper.canRegisterVip("SEATED"))
        assertFalse(FirestoreMapper.canRegisterVip("WAITING"))
        assertFalse(FirestoreMapper.canRegisterVip("NO_SHOW"))
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
    @Test fun `VIP private 는 스펙 v3 일곱 필드, public 은 전화번호 없이 세 필드`() {
        assertEquals(waiting, FirestoreMapper.vipPrivateDoc("01000000001", 3, 123, "pub-1"))
        assertEquals(mapOf("is_vip" to true, "status" to "WAITING", "created_at" to 123L), FirestoreMapper.vipPublicDoc(123))
        assertFalse(FirestoreMapper.vipPublicDoc(123).containsKey("phone"))
    }
    @Test fun `party_size 는 정수(Long)로 저장 - 규칙의 is int 검사`() {
        assertTrue(FirestoreMapper.vipPrivateDoc("01000000001", 3, 123, "p")["party_size"] is Long)
    }
    @Test fun `호출은 private 의 called_at 만`() {
        assertEquals(setOf("called_at"), FirestoreMapper.callFields(100).keys)
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
