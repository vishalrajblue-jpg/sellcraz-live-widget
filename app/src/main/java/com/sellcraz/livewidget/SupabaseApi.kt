package com.sellcraz.livewidget

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class ApiException(val code: Int, message: String) : Exception(message)

class Resp(val code: Int, val body: String, val serverTimeMs: Long?, val localTimeMs: Long)

class LotFetch(val json: JSONObject, val serverTimeMs: Long?, val localTimeMs: Long)

/**
 * Minimal Supabase client: GoTrue password login + PostgREST.
 * Blocking calls, so only ever call from a background thread.
 */
class SupabaseApi(private val prefs: Prefs) {

    fun signIn(email: String, password: String) {
        val body = JSONObject().put("email", email).put("password", password).toString()
        val r = request("POST", "/auth/v1/token?grant_type=password", body, useUserToken = false)
        if (r.code !in 200..299) throw ApiException(r.code, errorMessage(r.body))
        storeSession(JSONObject(r.body))
        prefs.email = email
    }

    private fun refreshSession(): Boolean {
        val rt = prefs.refreshToken ?: return false
        val body = JSONObject().put("refresh_token", rt).toString()
        val r = try {
            request("POST", "/auth/v1/token?grant_type=refresh_token", body, useUserToken = false)
        } catch (e: Exception) {
            return false
        }
        if (r.code !in 200..299) return false
        storeSession(JSONObject(r.body))
        return true
    }

    private fun storeSession(o: JSONObject) {
        prefs.accessToken = o.optString("access_token").takeIf { it.isNotBlank() }
        prefs.refreshToken = o.optString("refresh_token").takeIf { it.isNotBlank() }
        val uid = o.optJSONObject("user")?.optString("id")
        if (!uid.isNullOrBlank()) prefs.userId = uid
    }

    fun fetchLot(lotId: String): LotFetch {
        val path = "/rest/v1/${enc(prefs.lotTable)}?id=eq.${enc(lotId)}&select=*"
        val r = authed("GET", path, null)
        if (r.code !in 200..299) throw ApiException(r.code, errorMessage(r.body))
        val arr = JSONArray(r.body)
        if (arr.length() == 0) throw ApiException(404, "Lot not found, or this account can't see it")
        return LotFetch(arr.getJSONObject(0), r.serverTimeMs, r.localTimeMs)
    }

    /** Calls the same place_bid RPC the web app uses. Returns the raw response body. */
    fun placeBid(lotId: String, amount: Long): String {
        val body = JSONObject()
            .put(prefs.lotParam, lotId)
            .put(prefs.amountParam, amount)
            .toString()
        val r = authed("POST", "/rest/v1/rpc/${enc(prefs.rpcName)}", body)
        if (r.code !in 200..299) throw ApiException(r.code, errorMessage(r.body))
        val t = r.body.trim()
        // Some RPCs report failure as {"ok": false, "error": "..."} with HTTP 200.
        if (t.startsWith("{")) {
            val o = JSONObject(t)
            if (o.has("ok") && !o.optBoolean("ok", true)) {
                throw ApiException(r.code, clean(o.optString("error")) ?: clean(o.optString("message")) ?: "Bid rejected")
            }
            val err = clean(o.optString("error"))
            if (err != null) throw ApiException(r.code, err)
        }
        return t
    }

    private fun authed(method: String, path: String, body: String?): Resp {
        var r = request(method, path, body, useUserToken = true)
        if (r.code == 401 && refreshSession()) r = request(method, path, body, useUserToken = true)
        return r
    }

    private fun request(method: String, path: String, body: String?, useUserToken: Boolean): Resp {
        val base = prefs.supabaseUrl
        val key = prefs.anonKey
        if (base.isBlank() || key.isBlank()) {
            throw ApiException(0, "Supabase URL or anon key missing (see Connect in the app)")
        }
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("apikey", key)
            val bearer = if (useUserToken) prefs.accessToken ?: key else key
            c.setRequestProperty("Authorization", "Bearer $bearer")
            c.setRequestProperty("Accept", "application/json")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val now = System.currentTimeMillis()
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            val date = c.getHeaderFieldDate("Date", 0L)
            return Resp(code, text, if (date > 0) date else null, now)
        } finally {
            c.disconnect()
        }
    }

    companion object {
        private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

        private fun clean(s: String?): String? =
            s?.takeIf { it.isNotBlank() && it != "null" }

        /** Turns a Supabase error body into something readable on a phone. */
        fun errorMessage(body: String): String {
            return try {
                val o = JSONObject(body)
                val main = listOf("message", "msg", "error_description", "error")
                    .firstNotNullOfOrNull { clean(o.optString(it)) }
                val hint = clean(o.optString("hint"))
                when {
                    main != null && hint != null -> "$main ($hint)"
                    main != null -> main
                    else -> body.take(200)
                }
            } catch (e: Exception) {
                body.take(200).ifBlank { "Request failed" }
            }
        }
    }
}
