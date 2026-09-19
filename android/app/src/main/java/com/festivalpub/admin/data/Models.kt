package com.festivalpub.admin.data

import kotlinx.serialization.Serializable

// docs/API.md 의 데이터 모델과 1:1 대응

@Serializable
data class Settings(
    val rows: Int = 5,
    val cols: Int = 6,
    val rotationMinutes: Int = 100,
    val imminentMinutes: Int = 15,
    val noShowMinutes: Int = 3,
)

@Serializable
data class TableInfo(
    val no: Int,
    val status: String = "EMPTY",          // EMPTY | OCCUPIED
    val seatedAt: Long? = null,
    val extendedMinutes: Int = 0,
    val partySize: Int? = null,
    val phone: String? = null,
    val waitingId: Int? = null,
) {
    val occupied: Boolean get() = status == "OCCUPIED"
}

@Serializable
data class Waiting(
    val id: Int,
    val phone: String,
    val partySize: Int,
    val isVip: Boolean = false,
    val status: String = "WAITING",        // WAITING | CALLED | NO_SHOW | SEATED | CANCELLED
    val createdAt: Long = 0,
    val calledAt: Long? = null,
    val tableNo: Int? = null,
)

@Serializable
data class MenuItem(
    val id: Int,
    val name: String,
    val price: Int,
    val category: String = "",
    val soldOut: Boolean = false,
)

@Serializable
data class OrderLine(
    val menuId: Int,
    val name: String,
    val price: Int,
    val qty: Int,
)

@Serializable
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

@Serializable
data class Snapshot(
    val serverTime: Long = 0,
    val settings: Settings = Settings(),
    val tables: List<TableInfo> = emptyList(),
    val waitings: List<Waiting> = emptyList(),
    val orders: List<Order> = emptyList(),
    val menu: List<MenuItem> = emptyList(),
    val staff: List<String> = emptyList(),
)

@Serializable
data class WsMessage(
    val type: String,
    val data: Snapshot? = null,
)
