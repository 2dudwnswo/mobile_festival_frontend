package com.festivalpub.admin.data

// docs/FIREBASE.md 의 데이터 구조와 대응. Firestore 문서 ↔ 모델 변환은 FirestoreMapper.kt 에 모여 있다.
// 시각은 모두 epoch ms(Long). Firestore 에는 Timestamp(serverTimestamp)로 저장된다.

data class Settings(
    val rows: Int = 5,
    val cols: Int = 6,
    val rotationMinutes: Int = 100,
    val imminentMinutes: Int = 15,
    val noShowMinutes: Int = 3,
)

data class TableInfo(
    val no: Int,
    val status: String = "EMPTY",          // EMPTY | OCCUPIED
    val seatedAt: Long? = null,
    val extendedMinutes: Int = 0,
    val partySize: Int? = null,
    val phone: String? = null,
    val waitingId: Int? = null,
    val waitingIsVip: Boolean = false,      // waitingId 가 vipWaitings 번호인지
) {
    val occupied: Boolean get() = status == "OCCUPIED"
}

/**
 * 웨이팅 한 팀. 일반 손님은 `waitings`(친구 서버가 생성), VIP 는 `vipWaitings`(앱만 생성)에 있다.
 * 두 컬렉션은 번호를 따로 매기므로 id 가 겹칠 수 있다 → 화면에서 구분할 때는 [key] 를 쓴다.
 */
data class Waiting(
    val id: Int,
    val phone: String,
    val partySize: Int,
    val isVip: Boolean = false,             // true = vipWaitings 문서
    val status: String = "WAITING",        // WAITING | CALLED | NO_SHOW | SEATED | CANCELLED
    val createdAt: Long = 0,
    val calledAt: Long? = null,
    val tableNo: Int? = null,
) {
    /** 두 컬렉션을 합친 목록에서 겹치지 않는 식별자 */
    val key: String get() = if (isVip) "v$id" else "w$id"

    /** 화면에 보이는 대기번호: 일반 "12", VIP "V3" */
    val label: String get() = if (isVip) "V$id" else "$id"
}

data class MenuItem(
    val id: Int,
    val name: String,
    val price: Int,
    val category: String = "",
    val soldOut: Boolean = false,
)

data class OrderLine(
    val menuId: Int,
    val name: String,
    val price: Int,
    val qty: Int,
)

data class Order(
    val id: Int,
    val tableNo: Int,
    val items: List<OrderLine> = emptyList(),
    val total: Int = 0,
    val source: String = "QR",             // QR | STAFF
    val paymentStatus: String = "PENDING", // PENDING | PAID | CANCELLED
    val cookStatus: String = "WAITING",    // WAITING | DONE
    val createdAt: Long = 0,
    val addedBy: String? = null,
    val paidAt: Long? = null,
    val paidBy: String? = null,
    val cookedAt: Long? = null,
    val cookedBy: String? = null,
)

/** 화면이 그리는 전체 상태. Firebase 리스너 결과를 FirebaseRepository 가 합쳐 만든다. */
data class Snapshot(
    val settings: Settings = Settings(),
    val tables: List<TableInfo> = emptyList(),
    val waitings: List<Waiting> = emptyList(),   // 일반 + VIP 합친 목록
    val orders: List<Order> = emptyList(),       // 오늘의 PAID 주문만
    val menu: List<MenuItem> = emptyList(),
    val staff: List<String> = emptyList(),
)
