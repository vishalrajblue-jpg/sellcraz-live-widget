package com.sellcraz.livewidget

import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/**
 * Copies the website's Supabase session into the app so the widget is signed
 * in as the same person. Reads the auth cookie (@supabase/ssr, possibly split
 * into .0/.1 chunks and base64-encoded) and falls back to localStorage
 * (plain supabase-js). Read-only: never writes the session back.
 */
object SessionBridge {

    private fun storageKey(supabaseUrl: String): String {
        val host = URI(supabaseUrl).host ?: ""
        return "sb-" + host.substringBefore('.') + "-auth-token"
    }

    /** Try cookies first; if nothing there, ask the page for localStorage. */
    fun sync(prefs: Prefs, webView: WebView?, done: (Boolean) -> Unit = {}) {
        val key = storageKey(prefs.supabaseUrl)
        val fromCookie = readCookie(prefs.siteUrl, key)
        if (fromCookie != null) {
            save(prefs, fromCookie)
            done(true)
            return
        }
        if (webView == null) {
            done(false)
            return
        }
        webView.evaluateJavascript("(function(){try{return localStorage.getItem('$key')}catch(e){return null}})()") { result ->
            val raw = try {
                JSONArray("[$result]").let { if (it.isNull(0)) null else it.getString(0) }
            } catch (e: Exception) {
                null
            }
            val s = raw?.let { parse(it) }
            if (s != null) {
                save(prefs, s)
                done(true)
            } else {
                // Signed out on the website: signed out in the widget too.
                prefs.clearSession()
                done(false)
            }
        }
    }

    private class Session(val accessToken: String, val userId: String?, val expMs: Long)

    private fun save(prefs: Prefs, s: Session) {
        prefs.accessToken = s.accessToken
        prefs.userId = s.userId
        prefs.tokenExpMs = s.expMs
    }

    private fun readCookie(siteUrl: String, key: String): Session? {
        val raw = CookieManager.getInstance().getCookie(siteUrl) ?: return null
        val jar = HashMap<String, String>()
        for (part in raw.split(';')) {
            val i = part.indexOf('=')
            if (i > 0) jar[part.substring(0, i).trim()] = part.substring(i + 1).trim()
        }
        val whole = jar[key] ?: buildString {
            var n = 0
            while (true) {
                val chunk = jar["$key.$n"] ?: break
                append(chunk)
                n++
            }
        }
        if (whole.isEmpty()) return null
        return parse(whole)
    }

    private fun parse(value: String): Session? {
        var v = try {
            URLDecoder.decode(value, "UTF-8")
        } catch (e: Exception) {
            value
        }
        if (v.startsWith("base64-")) v = decodeB64(v.removePrefix("base64-")) ?: return null
        return try {
            val t = v.trim()
            val access: String
            if (t.startsWith("[")) {
                access = JSONArray(t).getString(0)
            } else {
                access = JSONObject(t).getString("access_token")
            }
            val claims = jwtClaims(access) ?: return null
            Session(
                accessToken = access,
                userId = claims.optString("sub").takeIf { it.isNotBlank() },
                expMs = claims.optLong("exp", 0L) * 1000L,
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeB64(s: String): String? = try {
        val padded = s + "=".repeat((4 - s.length % 4) % 4)
        String(Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }

    private fun jwtClaims(jwt: String): JSONObject? {
        val parts = jwt.split('.')
        if (parts.size < 2) return null
        return decodeB64(parts[1])?.let {
            try {
                JSONObject(it)
            } catch (e: Exception) {
                null
            }
        }
    }
}
