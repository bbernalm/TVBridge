package com.tvbridge.app

import android.content.Context

/**
 * Owns the single TV connection, shared by the screen (on-screen remote) and the voice
 * service. The connection stays up while at least one of them needs it.
 */
object TvHub {
    @Volatile var controller: TvController? = null
        private set

    private var client: WebOsClient? = null
    private val holders = HashSet<String>()

    /** "ui" and "service" call this; the first one opens the connection. */
    @Synchronized
    fun acquire(ctx: Context, who: String) {
        holders.add(who)
        if (client == null) build(ctx.applicationContext)
    }

    @Synchronized
    fun release(who: String) {
        holders.remove(who)
        if (holders.isEmpty()) teardown()
    }

    /** Rebuilds the connection after the settings changed (new IP, for example). */
    @Synchronized
    fun restart(ctx: Context) {
        if (holders.isEmpty()) return
        teardown()
        build(ctx.applicationContext)
    }

    private fun build(app: Context) {
        val cfg = Config.load(app)
        if (!cfg.tvReady) return
        val manifest = app.resources.openRawResource(R.raw.webos_manifest)
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val c = WebOsClient(
            ip = cfg.tvIp,
            manifestJson = manifest,
            loadKey = { Config.clientKey(app) },
            saveKey = { Config.saveClientKey(app, it) },
        )
        client = c
        controller = TvController(c, cfg)
        c.start()
    }

    private fun teardown() {
        client?.stop()
        client = null
        controller = null
        BridgeState.tvConnected = false
        BridgeState.tvPairing = false
    }
}
