package com.tvbridge.app

import android.content.Context

/** Settings the user types on the main screen. */
data class Config(
    val tvIp: String,
    val tvMac: String,
    val appKey: String,
    val appSecret: String,
    val deviceId: String,
) {
    /** Enough to control the TV from the phone (buttons on screen). */
    val tvReady: Boolean
        get() = tvIp.isNotBlank()

    /** Enough to connect to Sinric Pro (voice with Google Home). */
    val cloudReady: Boolean
        get() = appKey.isNotBlank() && appSecret.isNotBlank() && deviceId.isNotBlank()

    val isComplete: Boolean
        get() = tvReady && cloudReady

    companion object {
        private const val FILE = "tvbridge"
        private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

        fun load(c: Context): Config {
            val p = sp(c)
            return Config(
                tvIp = p.getString("tvIp", "") ?: "",
                tvMac = p.getString("tvMac", "") ?: "",
                appKey = p.getString("appKey", "") ?: "",
                appSecret = p.getString("appSecret", "") ?: "",
                deviceId = p.getString("deviceId", "") ?: "",
            )
        }

        fun save(c: Context, cfg: Config) {
            val old = load(c)
            val ip = cfg.tvIp.trim()
            val e = sp(c).edit()
                .putString("tvIp", ip)
                .putString("tvMac", cfg.tvMac.trim())
                .putString("appKey", cfg.appKey.trim())
                .putString("appSecret", cfg.appSecret.trim())
                .putString("deviceId", cfg.deviceId.trim())
            // A different TV needs a new pairing, so forget the old key.
            if (old.tvIp != ip) e.remove("clientKey")
            e.apply()
        }

        /** Pairing key the TV gives us the first time the user accepts the prompt. */
        fun clientKey(c: Context): String? = sp(c).getString("clientKey", null)

        fun saveClientKey(c: Context, key: String) {
            sp(c).edit().putString("clientKey", key).apply()
        }

        fun clearClientKey(c: Context) {
            sp(c).edit().remove("clientKey").apply()
        }
    }
}
