package com.cloudframe.tv

import android.content.Context

/** App settings and the Degoo session, stored privately on the TV.
 *  The password itself is kept encrypted by [CredentialStore]. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("cloudframe", Context.MODE_PRIVATE)

    val accessToken: String get() = sp.getString("access", "") ?: ""
    val refreshToken: String get() = sp.getString("refresh", "") ?: ""

    fun setTokens(access: String, refresh: String) =
        sp.edit().putString("access", access).putString("refresh", refresh)
            .putBoolean("sessionExpired", false).apply()

    /** Degoo stopped accepting our session. Kept separate from signing out, so the
     *  screensaver can carry on with photos already saved on the TV. */
    var sessionExpired: Boolean
        get() = sp.getBoolean("sessionExpired", false)
        set(v) = sp.edit().putBoolean("sessionExpired", v).apply()

    fun signOut() = sp.edit().remove("access").remove("refresh").remove("email").apply()

    var email: String
        get() = sp.getString("email", "") ?: ""
        set(v) = sp.edit().putString("email", v).apply()

    var folderId: String
        get() = sp.getString("folderId", "") ?: ""
        set(v) = sp.edit().putString("folderId", v).apply()

    var folderName: String
        get() = sp.getString("folderName", "") ?: ""
        set(v) = sp.edit().putString("folderName", v).apply()

    var slideSeconds: Int
        get() = sp.getInt("slideSeconds", 15)
        set(v) = sp.edit().putInt("slideSeconds", v).apply()

    var shuffle: Boolean
        get() = sp.getBoolean("shuffle", true)
        set(v) = sp.edit().putBoolean("shuffle", v).apply()

    var fillScreen: Boolean
        get() = sp.getBoolean("fillScreen", false)
        set(v) = sp.edit().putBoolean("fillScreen", v).apply()

    var showClock: Boolean
        get() = sp.getBoolean("showClock", true)
        set(v) = sp.edit().putBoolean("showClock", v).apply()

    var slowZoom: Boolean
        get() = sp.getBoolean("slowZoom", true)
        set(v) = sp.edit().putBoolean("slowZoom", v).apply()

    companion object {
        val SLIDE_CHOICES = listOf(10, 15, 30, 60, 120, 300)
    }
}
