package com.festivalpub.admin.data

import com.google.firebase.firestore.FieldValue
import java.time.Instant
import java.time.ZoneId

/** 동현 스펙 v3 경로와 필드 문자열은 이 파일에서만 정의한다. */
object Fs {
    const val TABLES = "tables"
    /** 전화번호가 있는 웨이팅 원본. 문서 ID = 숫자만 남긴 전화번호. 앱은 이 컬렉션을 구독한다. */
    const val WAITING_PRIVATE = "waiting_private"
    /** 전화번호 없는 사본(손님 웹의 "내 앞 대기"용). 앱은 쓰기만 한다. */
    const val WAITING_PUBLIC = "waiting_public"
    const val PUBLIC_ID = "public_id"
    const val MENU = "menu"
    const val ORDERS = "orders"
    const val STATUS = "status"
    const val TABLE_NO = "table_no"
    const val START_TIME = "start_time"
    const val PAYMENT_CONFIRMED = "payment_confirmed"
    const val EXTENDED_MINUTES = "extended_minutes"
    const val TOTAL_AMOUNT = "total_amount"
    const val PHONE = "phone"
    const val PARTY_SIZE = "party_size"
    const val IS_VIP = "is_vip"
    const val CALLED_AT = "called_at"
    const val CREATED_AT = "created_at"
    const val NAME = "name"
    const val PRICE = "price"
    const val TABLE_ID = "table_id"
    const val MENU_ID = "menu_id"
    const val MENU_NAME = "menu_name"
    const val MENU_PRICE = "menu_price"
    const val QUANTITY = "quantity"
    const val ADDED_BY = "added_by"
    val ACTIVE_WAITING = listOf("WAITING", "NO_SHOW")
}
object FirestoreMapper {
    private fun long(v: Any?): Long? = when (v) { is Long -> v; is Int -> v.toLong(); else -> null }
    private fun tableNumber(v: Any?): Int? = long(v)?.takeIf { it in 1..30 }?.toInt()
    fun table(docId: String, d: Map<String, Any?>): TableInfo? {
        val no = tableNumber(d[Fs.TABLE_NO]) ?: return null
        if (docId != no.toString()) return null
        val status = d[Fs.STATUS] as? String ?: return null
        if (status !in listOf("EMPTY", "SEATED_PENDING_PAYMENT", "IN_USE")) return null
        val start = long(d[Fs.START_TIME])
        if (status != "EMPTY" && start == null) return null
        return TableInfo(no, status, start, long(d[Fs.EXTENDED_MINUTES]) ?: return null,
            d[Fs.PAYMENT_CONFIRMED] as? Boolean ?: return null, long(d[Fs.TOTAL_AMOUNT]) ?: return null)
    }
    /**
     * 전화번호를 문서 ID 형식(숫자만)으로 정규화한다. 하이픈·공백 등은 제거한다.
     * 한국 휴대폰·일반 번호 길이(9~11자리)가 아니면 null.
     */
    fun normalizePhone(raw: String): String? = raw.filter { it.isDigit() }.takeIf { it.length in 9..11 }

    /** waiting_private 문서 → 모델. 문서 ID(전화번호)를 기준으로 한다. */
    fun waiting(docId: String, d: Map<String, Any?>): Waiting? {
        val status = d[Fs.STATUS] as? String ?: return null
        if (status !in listOf("WAITING", "NO_SHOW", "SEATED", "CANCELLED")) return null
        val phone = normalizePhone(docId)?.takeIf { it == docId } ?: return null
        (d[Fs.PHONE] as? String)?.let { if (it != docId) return null }
        return Waiting(docId, phone,
            long(d[Fs.PARTY_SIZE])?.takeIf { it > 0 } ?: return null,
            d[Fs.IS_VIP] as? Boolean ?: return null, status,
            long(d[Fs.CREATED_AT]) ?: return null, long(d[Fs.CALLED_AT]),
            (d[Fs.PUBLIC_ID] as? String)?.takeIf { it.isNotBlank() })
    }
    fun menuItem(docId: String, d: Map<String, Any?>): MenuItem? {
        return MenuItem(docId, d[Fs.NAME] as? String ?: return null,
            long(d[Fs.PRICE])?.takeIf { it >= 0 } ?: return null)
    }
    fun order(docId: String, d: Map<String, Any?>): OrderLine? {
        val status = d[Fs.STATUS] as? String ?: return null
        if (status !in listOf("PENDING", "DONE")) return null
        val price = long(d[Fs.MENU_PRICE])?.takeIf { it >= 0 } ?: return null
        val qty = long(d[Fs.QUANTITY])?.takeIf { it > 0 } ?: return null
        if (price > 0 && qty > Long.MAX_VALUE / price) return null
        return OrderLine(docId, tableNumber(d[Fs.TABLE_ID]) ?: return null,
            d[Fs.MENU_ID] as? String ?: return null, d[Fs.MENU_NAME] as? String ?: return null,
            price, qty, d[Fs.ADDED_BY] as? String ?: return null, status,
            long(d[Fs.CREATED_AT]) ?: return null)
    }
    /** 18시~다음 날 01시 행사: 한국 시간 오전 6시를 영업일 경계로 사용. */
    fun businessDayStartKst(nowMs: Long): Long {
        val now = Instant.ofEpochMilli(nowMs).atZone(ZoneId.of("Asia/Seoul"))
        val date = if (now.hour < 6) now.toLocalDate().minusDays(1) else now.toLocalDate()
        return date.atTime(6, 0).atZone(now.zone).toInstant().toEpochMilli()
    }
    fun seatFields(nowMs: Long): Map<String, Any?> = mapOf(Fs.STATUS to "SEATED_PENDING_PAYMENT", Fs.START_TIME to nowMs)
    fun releaseFields(): Map<String, Any?> = mapOf(Fs.STATUS to "EMPTY", Fs.START_TIME to null,
        Fs.PAYMENT_CONFIRMED to false, Fs.EXTENDED_MINUTES to 0L, Fs.TOTAL_AMOUNT to 0L)
    fun confirmPaymentFields(): Map<String, Any?> = mapOf(Fs.PAYMENT_CONFIRMED to true, Fs.STATUS to "IN_USE")
    fun extendFields(minutes: Int): Map<String, Any?> {
        require(minutes in listOf(10, 20, 30))
        return mapOf(Fs.EXTENDED_MINUTES to FieldValue.increment(minutes.toLong()))
    }
    /** 호출은 waiting_private 에만 기록한다 (public 에는 called_at 이 없다). */
    fun callFields(nowMs: Long): Map<String, Any?> = mapOf(Fs.CALLED_AT to nowMs)
    // 복귀는 status만 변경한다. 등록·호출 시각은 보존한다.
    fun waitingStatusFields(status: String): Map<String, Any?> {
        require(status in listOf("WAITING", "NO_SHOW", "SEATED", "CANCELLED"))
        return mapOf(Fs.STATUS to status)
    }

    /**
     * 무응답·복귀·취소·착석 때 바꿀 문서. private 는 항상, public 은 짝(public_id)이 있을 때만 같은 status 로.
     * public_id 가 없으면 [publicId] = null → private 만 바꾸고 호출 쪽에서 경고를 남긴다.
     */
    data class StatusPlan(val privateFields: Map<String, Any?>, val publicId: String?, val publicFields: Map<String, Any?>?)
    fun statusPlan(publicId: String?, status: String): StatusPlan {
        val fields = waitingStatusFields(status)
        val pid = publicId?.takeIf { it.isNotBlank() }
        return StatusPlan(fields, pid, if (pid != null) fields else null)
    }

    /** VIP 등록 가능 여부: 번호 문서가 없거나 CANCELLED/SEATED 면 새로 만든다(덮어쓰기), WAITING/NO_SHOW 면 막는다. */
    fun canRegisterVip(existingStatus: String?): Boolean = existingStatus == null || existingStatus in listOf("CANCELLED", "SEATED")

    /** VIP public 사본 (전화번호 없음). private 와 같은 created_at 을 쓴다. */
    fun vipPublicDoc(nowMs: Long): Map<String, Any?> = mapOf(
        Fs.IS_VIP to true, Fs.STATUS to "WAITING", Fs.CREATED_AT to nowMs)

    /** VIP private 원본. 문서 ID = [phone]. party_size 는 정수(규칙이 is int 검사). */
    fun vipPrivateDoc(phone: String, partySize: Int, nowMs: Long, publicId: String): Map<String, Any?> = mapOf(
        Fs.PHONE to phone, Fs.PARTY_SIZE to partySize.toLong(), Fs.IS_VIP to true,
        Fs.STATUS to "WAITING", Fs.CALLED_AT to null, Fs.CREATED_AT to nowMs, Fs.PUBLIC_ID to publicId)
    fun orderDoc(line: OrderLine): Map<String, Any?> = mapOf(
        Fs.TABLE_ID to line.tableNo.toLong(), Fs.MENU_ID to line.menuId, Fs.MENU_NAME to line.name,
        Fs.MENU_PRICE to line.price, Fs.QUANTITY to line.qty, Fs.ADDED_BY to line.addedBy,
        Fs.STATUS to "PENDING", Fs.CREATED_AT to line.createdAt)
    fun amountFields(amount: Long): Map<String, Any?> = mapOf(Fs.TOTAL_AMOUNT to FieldValue.increment(amount))
    fun cookedFields(): Map<String, Any?> = mapOf(Fs.STATUS to "DONE")
}
