package com.festivalpub.admin.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.TimeUnit

class ApiException(message: String) : Exception(message)

/** 노트북 서버와의 REST + WebSocket 통신 */
class ApiClient(rawUrl: String) {

    val baseUrl: String = normalize(rawUrl)

    private val http = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS) // 끊긴 연결을 빨리 감지
        .build()

    /** 상태 변경 요청. 실패하면 서버의 error 메시지로 ApiException 을 던진다. */
    suspend fun send(method: String, path: String, body: JsonObject) = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl + path)
            .method(method, body.toString().toRequestBody(JSON_TYPE))
            .build()
        try {
            http.newCall(request).execute().use { res ->
                if (!res.isSuccessful) {
                    val text = res.body?.string().orEmpty()
                    val msg = runCatching {
                        json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content
                    }.getOrNull()
                    throw ApiException(msg ?: "요청 실패 (${res.code})")
                }
            }
        } catch (e: IOException) {
            throw ApiException("서버에 연결할 수 없습니다")
        }
    }

    fun openSocket(listener: WebSocketListener): WebSocket {
        val wsUrl = baseUrl.replaceFirst("http", "ws") + "/ws"
        return http.newWebSocket(Request.Builder().url(wsUrl).build(), listener)
    }

    fun shutdown() {
        http.dispatcher.cancelAll()
        http.connectionPool.evictAll()
    }

    companion object {
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

        val json = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            explicitNulls = false
        }

        /** "192.168.43.10" → "http://192.168.43.10:8080" */
        fun normalize(raw: String): String {
            var u = raw.trim().trimEnd('/')
            if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
            val hostPart = u.substringAfter("://")
            if (!Regex(":\\d+$").containsMatchIn(hostPart)) u += ":8080"
            return u
        }
    }
}
