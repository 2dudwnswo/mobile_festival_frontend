package com.festivalpub.admin.data

/** 앱 상수. 원격 설정 문서는 사용하지 않는다. */
data class Settings(
    val rows: Int = 5, val cols: Int = 6,
    val rotationMinutes: Int = 100, val imminentMinutes: Int = 15, val noShowMinutes: Int = 3,
)
data class TableInfo(
    val no: Int, val status: String = "EMPTY", val startTime: Long? = null,
    val extendedMinutes: Long = 0, val paymentConfirmed: Boolean = false, val totalAmount: Long = 0,
) {
    val occupied: Boolean get() = status == "SEATED_PENDING_PAYMENT" || status == "IN_USE"
}
data class Waiting(
    val key: String, val phone: String, val partySize: Long,
    val isVip: Boolean = false, val status: String = "WAITING",
    val createdAt: Long = 0, val calledAt: Long? = null,
)
data class MenuItem(val id: String, val name: String, val price: Long)
/** 주문 문서 하나 = 메뉴 한 줄. 결제 상태는 주문에 없다. */
data class OrderLine(
    val id: String, val tableNo: Int, val menuId: String, val name: String,
    val price: Long, val qty: Long, val addedBy: String,
    val status: String = "PENDING", val createdAt: Long = 0,
) { val total: Long get() = price * qty }
/** 화면용 묶음. Firestore에 묶음 필드를 추가하지 않는다. */
data class KitchenGroup(val tableNo: Int, val createdAt: Long, val items: List<OrderLine>) {
    val key: String get() = "$tableNo:$createdAt"
}
data class Snapshot(
    val settings: Settings = Settings(), val tables: List<TableInfo> = emptyList(),
    val waitings: List<Waiting> = emptyList(), val orders: List<OrderLine> = emptyList(),
    val pendingOrders: List<OrderLine> = emptyList(), val menu: List<MenuItem> = emptyList(),
)
