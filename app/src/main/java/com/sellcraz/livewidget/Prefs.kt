package com.sellcraz.livewidget

import android.content.Context

/** What the app remembers. The session is copied in from the website. */
class Prefs(context: Context) {
    private val p = context.applicationContext
        .getSharedPreferences("sellcraz_app", Context.MODE_PRIVATE)

    val siteUrl: String get() = BuildConfig.SITE_URL.trimEnd('/')
    val supabaseUrl: String get() = BuildConfig.SUPABASE_URL.trimEnd('/')
    val anonKey: String get() = BuildConfig.SUPABASE_ANON_KEY

    var accessToken: String?
        get() = p.getString("at", null)
        set(v) = p.edit().putString("at", v).apply()

    var userId: String?
        get() = p.getString("uid", null)
        set(v) = p.edit().putString("uid", v).apply()

    var tokenExpMs: Long
        get() = p.getLong("exp", 0L)
        set(v) = p.edit().putLong("exp", v).apply()

    /** True when there is a token that is not about to expire. */
    fun hasFreshSession(marginMs: Long = 30_000): Boolean =
        accessToken != null && tokenExpMs > System.currentTimeMillis() + marginMs

    fun clearSession() {
        p.edit().remove("at").remove("uid").remove("exp").apply()
    }
}
