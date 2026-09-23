package com.sellcraz.livewidget

import android.content.Context

/** Everything the app remembers between launches. */
class Prefs(context: Context) {
    private val p = context.applicationContext
        .getSharedPreferences("sellcraz_widget", Context.MODE_PRIVATE)

    private fun str(key: String, def: String): String =
        p.getString(key, null)?.takeIf { it.isNotBlank() } ?: def

    private fun put(key: String, v: String?) {
        p.edit().putString(key, v).apply()
    }

    var supabaseUrl: String
        get() = str("url", BuildConfig.SUPABASE_URL).trim().trimEnd('/')
        set(v) = put("url", v.trim().trimEnd('/'))

    var anonKey: String
        get() = str("anon", BuildConfig.SUPABASE_ANON_KEY).trim()
        set(v) = put("anon", v.trim())

    var accessToken: String?
        get() = p.getString("at", null)
        set(v) = put("at", v)

    var refreshToken: String?
        get() = p.getString("rt", null)
        set(v) = put("rt", v)

    var userId: String?
        get() = p.getString("uid", null)
        set(v) = put("uid", v)

    var email: String?
        get() = p.getString("email", null)
        set(v) = put("email", v)

    var lotId: String?
        get() = p.getString("lot", null)
        set(v) = put("lot", v)

    // Schema knobs. Defaults are best guesses; change in the app's Advanced
    // section if the database uses different names. No rebuild needed.
    var lotTable: String
        get() = str("lot_table", "lots")
        set(v) = put("lot_table", v.trim())

    var rpcName: String
        get() = str("rpc", "place_bid")
        set(v) = put("rpc", v.trim())

    var lotParam: String
        get() = str("lot_param", "p_lot_id")
        set(v) = put("lot_param", v.trim())

    var amountParam: String
        get() = str("amount_param", "p_amount")
        set(v) = put("amount_param", v.trim())

    var defaultIncrement: Long
        get() = str("inc", "50").toLongOrNull()?.takeIf { it > 0 } ?: 50L
        set(v) = put("inc", v.toString())

    fun signOut() {
        accessToken = null
        refreshToken = null
        userId = null
    }
}
