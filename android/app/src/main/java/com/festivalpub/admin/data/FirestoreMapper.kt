package com.festivalpub.admin.data

import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

/**
 * Firestore 경로·이름 상수. docs/FIREBASE.md 초안 기준이며 "친구 확인 필요" 항목이 섞여 있다.
 * 구조가 확정되어 바뀌면 이 파일(Fs, FirestoreMapper)만 고치면 되도록 모아 두었다.
 */
object Fs {
    /** 스태프 공용 계정 이메일. firebase/firestore.rules 의 staff() 와 같아야 한다. 비밀번호는 코드에 두지 않는다. */
    const val STAFF_EMAIL = "staff@festival-pub.local"

    const val CONFIG = "config"
    const val SETTINGS_DOC = "settings"
    const val STAFF_DOC = "staff"

    const val COUNTERS = "counters"
    const val COUNTER_VIP = "vip"        // 앱 전용 (VIP 번호)
    const val COUNTER_ORDERS = "orders"  // 🔶 QR 주문 웹과 공유 — 양쪽 모두 트랜잭션으로 증가

    const val MENU = "menu"
    const val TABLES = "tables"
    const val WAITINGS = "waitings"          // 일반 손님: 친구 서버만 생성
    const val VIP_WAITINGS = "vipWaitings"   // VIP: 앱만 생성·수정
    const val ORDERS = "orders"
    const val CLOCKS = "clocks"              // 기기 시계 보정용 (기기마다 자기 문서)

    /** 앱이 구독하는 웨이팅 상태 (착석·취소된 팀은 쓰지 않음) */
    val ACTIVE_WAITING = listOf("WAITING", "CALLED", "NO_SHOW")

    /** 주문 구독 범위의 기준 시간대: 오늘 0시(한국 시간) 이후 주문만 */
    val KST: TimeZone = TimeZone.getTimeZone("Asia/Seoul")
}

/** Firestore 문서(Map) ↔ 앱 모델 변환. Firebase 없이 단위 테스트할 수 있게 Map 만 다룬다. */
object FirestoreMapper {

    // ---------------- 읽기: 문서 → 모델 ----------------

    /** Timestamp / Date / 숫자(epoch ms) 를 epoch ms 로. 없거나 모르는 형식이면 null */
    fun millis(v: Any?): Long? = when (v) {
        is Timestamp -> v.seconds * 1000 + v.nanoseconds / 1_000_000
        is Date -> v.time
        is Number -> v.toLong()
        else -> null
    }

    private fun int(v: Any?): Int? = (v as? Number)?.toInt()
    private fun str(v: Any?): String? = v as? String

    fun settings(d: Map<String, Any?>?): Settings {
        val def = Settings()
        if (d == null) return def
        return Settings(
            rows = int(d["rows"]) ?: def.rows,
            cols = int(d["cols"]) ?: def.cols,
            rotationMinutes = int(d["rotationMinutes"]) ?: def.rotationMinutes,
            imminentMinutes = int(d["imminentMinutes"]) ?: def.imminentMinutes,
            noShowMinutes = int(d["noShowMinutes"]) ?: def.noShowMinutes,
        )
    }

    fun staff(d: Map<String, Any?>?): List<String> =
        (d?.get("names") as? List<*>)?.filterIsInstance<String>().orEmpty()

    fun menuItem(docId: String, d: Map<String, Any?>): MenuItem? {
        val id = int(d["id"]) ?: docId.toIntOrNull() ?: return null
        return MenuItem(
            id = id,
            name = str(d["name"]) ?: return null,
            price = int(d["price"]) ?: return null,
            category = str(d["category"]).orEmpty(),
            soldOut = d["soldOut"] as? Boolean ?: false,
        )
    }

    fun table(docId: String, d: Map<String, Any?>): TableInfo? {
        val no = int(d["no"]) ?: docId.toIntOrNull() ?: return null
        return TableInfo(
            no = no,
            status = str(d["status"]) ?: "EMPTY",
            seatedAt = millis(d["seatedAt"]),
            extendedMinutes = int(d["extendedMinutes"]) ?: 0,
            partySize = int(d["partySize"]),
            phone = str(d["phone"]),
            waitingId = int(d["waitingId"]),
            waitingIsVip = d["waitingIsVip"] as? Boolean ?: false,
        )
    }

    /** [isVip] 는 문서가 어느 컬렉션(vipWaitings / waitings)에서 왔는지로 정한다. 필드 값은 믿지 않는다. */
    fun waiting(docId: String, d: Map<String, Any?>, isVip: Boolean): Waiting? {
        val id = int(d["id"]) ?: docId.toIntOrNull() ?: return null
        return Waiting(
            id = id,
            phone = str(d["phone"]) ?: return null,
            partySize = int(d["partySize"]) ?: return null,
            isVip = isVip,
            status = str(d["status"]) ?: "WAITING",
            createdAt = millis(d["createdAt"]) ?: 0,
            calledAt = millis(d["calledAt"]),
            tableNo = int(d["tableNo"]),
        )
    }

    fun order(docId: String, d: Map<String, Any?>): Order? {
        val id = int(d["id"]) ?: docId.toIntOrNull() ?: return null
        val lines = (d["items"] as? List<*>).orEmpty().mapNotNull { raw ->
            val m = raw as? Map<*, *> ?: return@mapNotNull null
            OrderLine(
                menuId = int(m["menuId"]) ?: return@mapNotNull null,
                name = m["name"] as? String ?: "",
                price = int(m["price"]) ?: 0,
                qty = int(m["qty"]) ?: return@mapNotNull null,
            )
        }
        return Order(
            id = id,
            tableNo = int(d["tableNo"]) ?: return null,
            items = lines,
            total = int(d["total"]) ?: lines.sumOf { it.price * it.qty },
            source = str(d["source"]) ?: "QR",
            paymentStatus = str(d["paymentStatus"]) ?: "PENDING",
            cookStatus = str(d["cookStatus"]) ?: "WAITING",
            createdAt = millis(d["createdAt"]) ?: 0,
            addedBy = str(d["addedBy"]),
            paidAt = millis(d["paidAt"]),
            paidBy = str(d["paidBy"]),
            cookedAt = millis(d["cookedAt"]),
            cookedBy = str(d["cookedBy"]),
        )
    }

    /**
     * 화면에 보일 테이블 목록: 1..rows×cols 번호 순.
     * 🔶 문서가 없는 번호는 빈자리로 채운다 (배치 변경 시 문서 관리는 서버 쪽 담당, 친구 확인 필요).
     */
    fun tablesForLayout(docs: Collection<TableInfo>, s: Settings): List<TableInfo> {
        val byNo = docs.associateBy { it.no }
        val n = (s.rows * s.cols).coerceAtLeast(0)
        return (1..n).map { byNo[it] ?: TableInfo(no = it) }
    }

    /** [nowMs] 가 속한 날의 0시(한국 시간) epoch ms. 주문 구독 범위(오늘 것만)에 쓴다. */
    fun todayStartKst(nowMs: Long): Long {
        val c = Calendar.getInstance(Fs.KST)
        c.timeInMillis = nowMs
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    // ---------------- 쓰기: 모델 → 문서 필드 ----------------
    // 모든 쓰기에 담당자 이름(updatedBy 등)과 서버 시각을 남긴다. 시각은 항상 serverTimestamp().

    private val now: FieldValue get() = FieldValue.serverTimestamp()

    fun seatFields(staff: String, partySize: Int?, phone: String?, waitingId: Int?, waitingIsVip: Boolean): Map<String, Any?> = mapOf(
        "status" to "OCCUPIED",
        "seatedAt" to now,
        "extendedMinutes" to 0,
        "partySize" to partySize,
        "phone" to phone,
        "waitingId" to waitingId,
        "waitingIsVip" to waitingIsVip,
        "updatedBy" to staff,
        "updatedAt" to now,
    )

    fun releaseFields(staff: String): Map<String, Any?> = mapOf(
        "status" to "EMPTY",
        "seatedAt" to null,
        "extendedMinutes" to 0,
        "partySize" to null,
        "phone" to null,
        "waitingId" to null,
        "waitingIsVip" to false,
        "updatedBy" to staff,
        "updatedAt" to now,
    )

    fun extendFields(staff: String, minutes: Int): Map<String, Any?> = mapOf(
        "extendedMinutes" to FieldValue.increment(minutes.toLong()),
        "updatedBy" to staff,
        "updatedAt" to now,
    )

    /** 호출·무응답·복귀·취소. 호출은 calledAt 을 서버 시각으로, 복귀는 calledAt 을 비운다. */
    fun waitingStatusFields(staff: String, status: String): Map<String, Any?> = buildMap {
        put("status", status)
        when (status) {
            "CALLED" -> put("calledAt", now)
            "WAITING" -> put("calledAt", null)
        }
        put("updatedBy", staff)
        put("updatedAt", now)
    }

    fun seatedWaitingFields(staff: String, tableNo: Int): Map<String, Any?> = mapOf(
        "status" to "SEATED",
        "tableNo" to tableNo,
        "updatedBy" to staff,
        "updatedAt" to now,
    )

    fun vipWaitingDoc(id: Int, phone: String, partySize: Int, staff: String): Map<String, Any?> = mapOf(
        "id" to id,
        "phone" to phone,
        "partySize" to partySize,
        "isVip" to true,
        "status" to "WAITING",
        "createdAt" to now,
        "calledAt" to null,
        "tableNo" to null,
        "createdBy" to staff,
        "updatedBy" to staff,
        "updatedAt" to now,
    )

    /** 1-5 직원 주문. 입금확인은 서버가 하므로 항상 PENDING 으로 만든다 (paymentStatus 는 이후 절대 쓰지 않음). */
    fun staffOrderDoc(id: Int, tableNo: Int, lines: List<OrderLine>, staff: String): Map<String, Any?> = mapOf(
        "id" to id,
        "tableNo" to tableNo,
        "items" to lines.map { mapOf("menuId" to it.menuId, "name" to it.name, "price" to it.price, "qty" to it.qty) },
        "total" to lines.sumOf { it.price * it.qty },
        "source" to "STAFF",
        "paymentStatus" to "PENDING",
        "cookStatus" to "WAITING",
        "createdAt" to now,
        "addedBy" to staff,
        "paidAt" to null,
        "paidBy" to null,
        "cookedAt" to null,
        "cookedBy" to null,
    )

    fun cookedFields(staff: String): Map<String, Any?> = mapOf(
        "cookStatus" to "DONE",
        "cookedAt" to now,
        "cookedBy" to staff,
    )

    /** 앱은 시간 값 3종만 쓴다. rows/cols 는 서버 쪽이 관리하므로 절대 보내지 않는다. */
    fun timeSettingsFields(s: Settings, staff: String): Map<String, Any?> = mapOf(
        "rotationMinutes" to s.rotationMinutes,
        "imminentMinutes" to s.imminentMinutes,
        "noShowMinutes" to s.noShowMinutes,
        "updatedBy" to staff,
        "updatedAt" to now,
    )
}
