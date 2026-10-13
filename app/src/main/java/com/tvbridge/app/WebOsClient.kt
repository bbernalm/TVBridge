package com.tvbridge.app

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Client for the LG webOS "SSAP" protocol: a WebSocket on the TV (ws://ip:3000 on older
 * models such as the UH603T, wss://ip:3001 on newer ones). The first time, the TV shows an
 * "allow this device?" prompt and hands back a client key that we save, so later connections
 * need no prompt.
 *
 * Callbacks passed to [request] may run on any thread; they get null on timeout/disconnect.
 *
 * The wss:// variant uses the TV's self-signed certificate, so this client (and only this one,
 * which only ever talks to the TV address the user typed) does not verify certificates.
 */
class WebOsClient(
    private val ip: String,
    private val manifestJson: String,
    private val loadKey: () -> String?,
    private val saveKey: (String) -> Unit,
) {
    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
    }

    private val http: OkHttpClient = run {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        OkHttpClient.Builder()
            .sslSocketFactory(ctx.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(5, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val nextId = AtomicInteger(0)
    private val pending = ConcurrentHashMap<String, (JSONObject?) -> Unit>()

    @Volatile
    var connected = false
        private set

    /** Runs (on a background thread) every time the TV accepts us. */
    @Volatile
    var onReady: (() -> Unit)? = null

    @Volatile
    private var running = false
    private var ws: WebSocket? = null
    private var urlIndex = 0
    private var registeredThisAttempt = false
    private var lastLostLog = ""

    private fun urlFor(i: Int): String = if (i % 2 == 0) "ws://$ip:3000" else "wss://$ip:3001"

    fun start() {
        running = true
        handler.post { connect() }
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        val w = ws
        ws = null
        updateConnected(false)
        try {
            w?.close(1000, "stop")
        } catch (e: Exception) {
            // ignore
        }
        closePointer()
        failPending()
    }

    private fun updateConnected(value: Boolean) {
        connected = value
        BridgeState.tvConnected = value
        if (!value) BridgeState.tvPairing = false
    }

    private fun failPending() {
        val keys = pending.keys.toList()
        for (k in keys) pending.remove(k)?.invoke(null)
    }

    // ---------------------------------------------------------------- conexión

    private fun connect() {
        if (!running) return
        val url = urlFor(urlIndex)
        if (lastLostLog.isEmpty()) BridgeState.log("Conectando con el TV ($url)…")
        registeredThisAttempt = false
        val req = Request.Builder().url(url).build()
        ws = http.newWebSocket(req, listener)
    }

    private fun lost(webSocket: WebSocket, why: String) {
        if (!running) return
        if (webSocket !== ws) return
        ws = null
        updateConnected(false)
        closePointer()
        failPending()
        // Avoid flooding the log while the TV is off: only log when the reason changes.
        if (why != lastLostLog) {
            BridgeState.log("TV desconectado: $why")
            lastLostLog = why
        }
        // If this address never worked, try the other one (ws:3000 <-> wss:3001).
        if (!registeredThisAttempt) urlIndex++
        handler.postDelayed({ connect() }, 4000)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            val payload = JSONObject()
                .put("forcePairing", false)
                .put("pairingType", "PROMPT")
                .put("manifest", JSONObject(manifestJson))
            val key = loadKey()
            if (!key.isNullOrEmpty()) payload.put("client-key", key)
            val msg = JSONObject()
                .put("type", "register")
                .put("id", "register_0")
                .put("payload", payload)
            webSocket.send(msg.toString())
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                handle(webSocket, text)
            } catch (e: Exception) {
                BridgeState.log("Error leyendo mensaje del TV: ${e.message}")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            lost(webSocket, "cerrado ($code)")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            lost(webSocket, t.message ?: "sin respuesta (¿TV apagado?)")
        }
    }

    private fun handle(webSocket: WebSocket, text: String) {
        val root = JSONObject(text)
        val type = root.optString("type")
        val id = root.optString("id")
        val payload = root.optJSONObject("payload") ?: JSONObject()

        when (type) {
            "registered" -> {
                val key = payload.optString("client-key")
                if (key.isNotEmpty()) saveKey(key)
                registeredThisAttempt = true
                lastLostLog = ""
                updateConnected(true)
                BridgeState.log("TV conectado ✔")
                onReady?.invoke()
            }
            "response" -> {
                if (id == "register_0") {
                    // The TV is showing the "allow?" prompt.
                    BridgeState.tvPairing = true
                    BridgeState.log("Acepta la conexión en la pantalla del TV")
                } else {
                    pending.remove(id)?.invoke(payload)
                }
            }
            "error" -> {
                val err = root.optString("error", "error")
                if (id == "register_0") {
                    BridgeState.log("El TV rechazó el emparejamiento: $err")
                } else {
                    BridgeState.log("El TV respondió con error: $err")
                    pending.remove(id)?.invoke(
                        JSONObject().put("returnValue", false).put("errorText", err),
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------- órdenes

    /** Sends `ssap://<uri>`. Returns false if the TV is not connected. */
    fun request(
        uri: String,
        payload: JSONObject = JSONObject(),
        cb: ((JSONObject?) -> Unit)? = null,
    ): Boolean {
        val w = ws
        if (w == null || !connected) {
            cb?.invoke(null)
            return false
        }
        val id = "r${nextId.incrementAndGet()}"
        if (cb != null) {
            pending[id] = cb
            handler.postDelayed({ pending.remove(id)?.invoke(null) }, 8000)
        }
        val msg = JSONObject()
            .put("type", "request")
            .put("id", id)
            .put("uri", "ssap://$uri")
            .put("payload", payload)
        return w.send(msg.toString())
    }

    // ---------------------------------------------------------------- botones del control

    private var pointer: WebSocket? = null
    private var pointerReady = false
    private var pointerOpening = false
    private val pointerQueue = ArrayList<String>()

    /** Presses a remote-control button (UP, DOWN, LEFT, RIGHT, ENTER, BACK, HOME, EXIT, 0-9...). */
    fun button(name: String) {
        synchronized(this) {
            if (pointerReady) {
                pointer?.send("type:button\nname:$name\n\n")
                return
            }
            pointerQueue.add(name)
            if (pointerOpening) return
            pointerOpening = true
        }
        request("com.webos.service.networkinput/getPointerInputSocket") { resp ->
            val path = resp?.optString("socketPath").orEmpty()
            if (path.isEmpty()) {
                synchronized(this) {
                    pointerOpening = false
                    pointerQueue.clear()
                }
                BridgeState.log("No pude abrir el canal de botones del TV")
            } else {
                val req = Request.Builder().url(path).build()
                val sock = http.newWebSocket(req, pointerListener)
                synchronized(this) { pointer = sock }
            }
        }
    }

    private val pointerListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(this@WebOsClient) {
                pointerReady = true
                pointerOpening = false
                for (name in pointerQueue) webSocket.send("type:button\nname:$name\n\n")
                pointerQueue.clear()
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            resetPointer(webSocket)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            resetPointer(webSocket)
        }
    }

    private fun resetPointer(webSocket: WebSocket) {
        synchronized(this) {
            if (webSocket !== pointer) return
            pointer = null
            pointerReady = false
            pointerOpening = false
            pointerQueue.clear()
        }
    }

    private fun closePointer() {
        val p = synchronized(this) {
            val current = pointer
            pointer = null
            pointerReady = false
            pointerOpening = false
            pointerQueue.clear()
            current
        }
        try {
            p?.close(1000, "stop")
        } catch (e: Exception) {
            // ignore
        }
    }
}
