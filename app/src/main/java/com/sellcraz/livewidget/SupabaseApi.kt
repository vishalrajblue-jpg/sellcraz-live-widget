package com.sellcraz.livewidget

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class ApiException(val code: Int, message: String) : Exception(message)

/** Thrown when the copied website session has expired. Fix: open the app. */
class SessionExpiredException : Exception("Session expired. Tap ↗ to open SellCraz, then come back.")

class RpcResult(val body: String, val localTimeMs: Long)

/**
 * PostgREST RPC calls only. Blocking: background threads only.
 * The app never refreshes tokens itself. Refreshing rotates the refresh
 * token, and doing that behind the website's back can log the user out of
 * both. The website refreshes its own session whenever it is open, and the
 * app copies it (see SessionBridge).
 */
class SupabaseApi(private val prefs: Prefs) {

    fun widgetState(showId: String): WidgetState {
        val r = rpc("show_widget_state", JSONObject().put("p_show", showId), requireUser = false)
        return WidgetState.parse(JSONObject(r.body), r.localTimeMs)
    }

    fun placeBid(lotId: String, amount: Long) {
        rpc("place_bid", JSONObject().put("p_lot", lotId).put("p_amount", amount), requireUser = true)
    }

    fun advanceLot(showId: String): WidgetState {
        val r = rpc("seller_advance_lot", JSONObject().put("p_show", showId), requireUser = true)
        return WidgetState.parse(JSONObject(r.body), r.localTimeMs)
    }

    fun closeLotNow(lotId: String) {
        rpc("force_close_auction", JSONObject().put("p_lot", lotId), requireUser = true)
    }

    fun endShow(showId: String) {
        rpc("end_show", JSONObject().put("p_show", showId), requireUser = true)
    }

    private fun rpc(name: String, args: JSONObject, requireUser: Boolean): RpcResult {
        val token = if (prefs.hasFreshSession(5_000)) prefs.accessToken else null
        if (requireUser && token == null) throw SessionExpiredException()

        val c = URL("${prefs.supabaseUrl}/rest/v1/rpc/$name").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.doOutput = true
            c.setRequestProperty("apikey", prefs.anonKey)
            c.setRequestProperty("Authorization", "Bearer ${token ?: prefs.anonKey}")
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Accept", "application/json")
            c.outputStream.use { it.write(args.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val now = System.currentTimeMillis()
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code == 401 || text.contains("JWT expired")) {
                if (requireUser) throw SessionExpiredException()
            }
            if (code !in 200..299) throw ApiException(code, errorMessage(text))
            return RpcResult(text, now)
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private fun clean(s: String?): String? = s?.takeIf { it.isNotBlank() && it != "null" }

        fun errorMessage(body: String): String = try {
            val o = JSONObject(body)
            clean(o.optString("message")) ?: clean(o.optString("error")) ?: body.take(160)
        } catch (e: Exception) {
            body.take(160).ifBlank { "Request failed" }
        }
    }
}
