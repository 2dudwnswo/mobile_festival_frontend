package com.festivalpub.admin.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

/** Firestore 문서(Map) ↔ 모델 변환 (docs/FIREBASE.md 1장) */
class FirestoreMapperTest {

    private val ts = Timestamp(1_726_700_000L, 123_000_000) // = 1726700000123 ms

    // ---------------- 시각 ----------------

    @Test
    fun `Timestamp·Date·숫자 모두 epoch ms 로`() {
        assertEquals(1_726_700_000_123L, FirestoreMapper.millis(ts))
        assertEquals(1_726_700_000_123L, FirestoreMapper.millis(Date(1_726_700_000_123L)))
        assertEquals(42L, FirestoreMapper.millis(42))
        assertNull(FirestoreMapper.millis(null))
        assertNull(FirestoreMapper.millis("2024-09-19"))
    }

    @Test
    fun `오늘 0시는 한국 시간 기준`() {
        // 2026-09-19 15:30 KST = 06:30 UTC
        val kst1530 = 1_789_799_400_000L
        val start = FirestoreMapper.todayStartKst(kst1530)
        assertEquals(1_789_743_600_000L, start) // 2026-09-19 00:00 KST = 2026-09-18 15:00 UTC
        // 한국 시간 새벽 0시 30분(= UTC 전날 15:30)도 같은 날로
        assertEquals(start, FirestoreMapper.todayStartKst(start + 30 * 60_000L))
        // 23:59:59.999 까지 같은 날, 다음 0시부터 다음 날
        assertEquals(start, FirestoreMapper.todayStartKst(start + 24 * 3_600_000L - 1))
        assertEquals(start + 24 * 3_600_000L, FirestoreMapper.todayStartKst(start + 24 * 3_600_000L))
    }

    // ---------------- 읽기 ----------------

    @Test
    fun `설정 문서가 없거나 필드가 빠지면 기본값`() {
        assertEquals(Settings(), FirestoreMapper.settings(null))
        val s = FirestoreMapper.settings(mapOf("rows" to 6L, "cols" to 3L, "rotationMinutes" to 90L))
        assertEquals(Settings(rows = 6, cols = 3, rotationMinutes = 90), s)
    }

    @Test
    fun `스태프 목록은 문자열만`() {
        assertEquals(listOf("동현", "영준"), FirestoreMapper.staff(mapOf("names" to listOf("동현", 3L, "영준"))))
        assertTrue(FirestoreMapper.staff(null).isEmpty())
    }

    @Test
    fun `테이블 문서 - 숫자는 Long 으로 와도 Int 로, 시각은 ms 로`() {
        val t = FirestoreMapper.table(
            "7",
            mapOf(
                "no" to 7L, "status" to "OCCUPIED", "seatedAt" to ts, "extendedMinutes" to 10L,
                "partySize" to 3L, "phone" to "01000000001", "waitingId" to 12L, "waitingIsVip" to true,
            ),
        )!!
        assertEquals(TableInfo(7, "OCCUPIED", 1_726_700_000_123L, 10, 3, "01000000001", 12, true), t)
        assertTrue(t.occupied)
    }

    @Test
    fun `빈 테이블 문서, no 필드가 없으면 문서 ID 사용`() {
        assertEquals(TableInfo(no = 3), FirestoreMapper.table("3", mapOf("status" to "EMPTY", "seatedAt" to null)))
        assertNull(FirestoreMapper.table("abc", mapOf()))
    }

    @Test
    fun `웨이팅 - 어느 컬렉션에서 왔는지로 VIP 구분 (필드 값은 믿지 않음)`() {
        val d = mapOf(
            "id" to 3L, "phone" to "01000000001", "partySize" to 2L, "isVip" to false,
            "status" to "CALLED", "createdAt" to ts, "calledAt" to ts,
        )
        val vip = FirestoreMapper.waiting("3", d, isVip = true)!!
        assertTrue(vip.isVip)
        assertEquals("v3", vip.key)
        assertEquals("V3", vip.label)
        assertEquals(1_726_700_000_123L, vip.calledAt)
        val normal = FirestoreMapper.waiting("3", d, isVip = false)!!
        assertFalse(normal.isVip)
        assertEquals("w3", normal.key)
        assertEquals("3", normal.label)
    }

    @Test
    fun `일반 3번과 VIP 3번은 key 가 달라 목록에서 섞이지 않는다`() {
        val d = mapOf("phone" to "01000000001", "partySize" to 2L, "createdAt" to ts)
        val a = FirestoreMapper.waiting("3", d, isVip = false)!!
        val b = FirestoreMapper.waiting("3", d, isVip = true)!!
        assertEquals(a.id, b.id)
        assertTrue(a.key != b.key)
    }

    @Test
    fun `필수 필드가 없는 웨이팅은 건너뜀`() {
        assertNull(FirestoreMapper.waiting("1", mapOf("partySize" to 2L), isVip = false)) // phone 없음
        assertNull(FirestoreMapper.waiting("1", mapOf("phone" to "01000000001"), isVip = false)) // 인원 없음
    }

    @Test
    fun `주문 문서 - 항목·금액·결제·조리 상태`() {
        val o = FirestoreMapper.order(
            "31",
            mapOf(
                "id" to 31L, "tableNo" to 7L, "total" to 35000L, "source" to "QR",
                "items" to listOf(
                    mapOf("menuId" to 1L, "name" to "해물파전", "price" to 15000L, "qty" to 2L),
                    mapOf("menuId" to 7L, "name" to "소주", "price" to 5000L, "qty" to 1L),
                ),
                "paymentStatus" to "PAID", "cookStatus" to "WAITING",
                "createdAt" to ts, "paidAt" to ts, "paidBy" to "서버",
            ),
        )!!
        assertEquals(31, o.id)
        assertEquals(7, o.tableNo)
        assertEquals(listOf(OrderLine(1, "해물파전", 15000, 2), OrderLine(7, "소주", 5000, 1)), o.items)
        assertEquals(35000, o.total)
        assertEquals("PAID", o.paymentStatus)
        assertEquals(1_726_700_000_123L, o.paidAt)
        assertNull(o.cookedAt)
    }

    @Test
    fun `total 이 없으면 항목 합계로`() {
        val o = FirestoreMapper.order(
            "1",
            mapOf("tableNo" to 1L, "items" to listOf(mapOf("menuId" to 3L, "name" to "닭꼬치", "price" to 5000L, "qty" to 3L))),
        )!!
        assertEquals(15000, o.total)
    }

    @Test
    fun `메뉴 문서`() {
        assertEquals(
            MenuItem(1, "해물파전", 15000, "안주", false),
            FirestoreMapper.menuItem("1", mapOf("name" to "해물파전", "price" to 15000L, "category" to "안주")),
        )
        assertTrue(FirestoreMapper.menuItem("2", mapOf("name" to "김치전", "price" to 12000L, "soldOut" to true))!!.soldOut)
        assertNull(FirestoreMapper.menuItem("3", mapOf("name" to "가격없음")))
    }

    @Test
    fun `테이블 목록은 1부터 rows×cols 까지, 문서 없는 번호는 빈자리`() {
        val docs = listOf(TableInfo(no = 2, status = "OCCUPIED", seatedAt = 1), TableInfo(no = 40))
        val list = FirestoreMapper.tablesForLayout(docs, Settings(rows = 2, cols = 3))
        assertEquals((1..6).toList(), list.map { it.no })
        assertEquals("OCCUPIED", list[1].status)
        assertEquals("EMPTY", list[0].status)
    }

    // ---------------- 쓰기 ----------------

    @Test
    fun `착석 필드 - 서버 시각, 연장 0, 웨이팅 연결`() {
        val f = FirestoreMapper.seatFields("영준", 3, "01000000001", 5, waitingIsVip = true)
        assertEquals("OCCUPIED", f["status"])
        assertEquals(FieldValue.serverTimestamp(), f["seatedAt"])
        assertEquals(0, f["extendedMinutes"])
        assertEquals(5, f["waitingId"])
        assertEquals(true, f["waitingIsVip"])
        assertEquals("영준", f["updatedBy"])
    }

    @Test
    fun `이용 종료 필드는 착석 정보를 모두 비운다 (주문은 건드리지 않음)`() {
        val f = FirestoreMapper.releaseFields("영준")
        assertEquals("EMPTY", f["status"])
        for (k in listOf("seatedAt", "partySize", "phone", "waitingId")) assertTrue(k, f.containsKey(k) && f[k] == null)
        assertEquals(0, f["extendedMinutes"])
        assertFalse(f.keys.any { it.contains("payment") })
    }

    @Test
    fun `웨이팅 상태 필드 - 호출은 calledAt 기록, 복귀는 calledAt 비움, 나머지는 그대로`() {
        val called = FirestoreMapper.waitingStatusFields("영준", "CALLED")
        assertEquals(FieldValue.serverTimestamp(), called["calledAt"])
        val restored = FirestoreMapper.waitingStatusFields("영준", "WAITING")
        assertTrue(restored.containsKey("calledAt") && restored["calledAt"] == null)
        val noShow = FirestoreMapper.waitingStatusFields("영준", "NO_SHOW")
        assertFalse(noShow.containsKey("calledAt")) // 호출 시각 유지
        assertEquals("영준", noShow["updatedBy"])
        // createdAt 은 절대 건드리지 않음 (복귀 시 원래 순서)
        assertFalse(restored.containsKey("createdAt"))
    }

    @Test
    fun `VIP 웨이팅 문서`() {
        val d = FirestoreMapper.vipWaitingDoc(4, "01000000009", 2, "영준")
        assertEquals(4, d["id"])
        assertEquals(true, d["isVip"])
        assertEquals("WAITING", d["status"])
        assertEquals(FieldValue.serverTimestamp(), d["createdAt"])
        assertEquals("영준", d["createdBy"])
    }

    @Test
    fun `직원 주문은 PENDING 으로만 생성, 합계 계산, 담당자 기록`() {
        val lines = listOf(OrderLine(1, "해물파전", 15000, 2), OrderLine(7, "소주", 5000, 1))
        val d = FirestoreMapper.staffOrderDoc(32, 7, lines, "영준")
        assertEquals("STAFF", d["source"])
        assertEquals("PENDING", d["paymentStatus"])
        assertEquals("WAITING", d["cookStatus"])
        assertEquals(35000, d["total"])
        assertEquals("영준", d["addedBy"])
        @Suppress("UNCHECKED_CAST")
        val items = d["items"] as List<Map<String, Any?>>
        assertEquals(mapOf("menuId" to 1, "name" to "해물파전", "price" to 15000, "qty" to 2), items[0])
    }

    @Test
    fun `앱이 쓰는 필드 묶음에는 paymentStatus 변경이 없다`() {
        val writes = listOf(
            FirestoreMapper.cookedFields("영준"),
            FirestoreMapper.seatFields("영준", null, null, null, false),
            FirestoreMapper.releaseFields("영준"),
            FirestoreMapper.extendFields("영준", 10),
            FirestoreMapper.waitingStatusFields("영준", "CALLED"),
            FirestoreMapper.timeSettingsFields(Settings(), "영준"),
        )
        writes.forEach { assertFalse(it.toString(), it.containsKey("paymentStatus")) }
    }

    @Test
    fun `시간 설정은 3종만, rows·cols 는 보내지 않음`() {
        val f = FirestoreMapper.timeSettingsFields(Settings(rows = 9, cols = 9, rotationMinutes = 90), "영준")
        assertEquals(90, f["rotationMinutes"])
        assertFalse(f.containsKey("rows"))
        assertFalse(f.containsKey("cols"))
    }

    @Test
    fun `조리완료 필드`() {
        val f = FirestoreMapper.cookedFields("주방1")
        assertEquals(setOf("cookStatus", "cookedAt", "cookedBy"), f.keys)
        assertEquals("DONE", f["cookStatus"])
    }
}
