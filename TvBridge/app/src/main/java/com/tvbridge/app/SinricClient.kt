package com.tvbridge.app

import android.os.Handler
import android.os.Looper
import android.util.Base64
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Sinric Pro client (wss://ws.sinric.pro). It registers as the "TV" device created in the
 * Sinric Pro portal; Google Home sends commands through the cloud and this class forwards
 * them to the TV over the local network.
 *
 * Responses are signed with HMAC-SHA256 (key = App Secret) over the exact "payload" text,
 * and that same text is what gets sent.
 *
 * Note: messages sent to the cloud are plain ASCII on purpose, so the signature matches
 * byte for byte.
 */
class SinricClient(
    private val appKey: String,
    private val appSecret: String,
    private val deviceId: String,
    /** Always asks for the current controller, because the TV connection can be rebuilt. */
    private val tvProvider: () -> TvController?,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .pingInterval(120, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    private var running = false
    private var ws: WebSocket? = null

    fun start() {
        running = true
        handler.post { connect() }
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        val w = ws
        ws = null
        BridgeState.cloudConnected = false
        try {
            w?.close(1000, "stop")
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun connect() {
        if (!running) return
        BridgeState.log("Conectando con Sinric Pro…")
        val req = Request.Builder()
            .url("wss://ws.sinric.pro")
            .addHeader("appkey", appKey)
            .addHeader("deviceids", deviceId)
            .addHeader("platform", "Android")
            .addHeader("SDKVersion", "1.0.0")
            .build()
        ws = http.newWebSocket(req, listener)
    }

    private fun lost(webSocket: WebSocket, why: String) {
        if (!running) return
        if (ws != null && webSocket !== ws) return
        ws = null
        BridgeState.cloudConnected = false
        BridgeState.log("Sinric Pro desconectado: $why")
        if (running) handler.postDelayed({ connect() }, 5000)
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            BridgeState.cloudConnected = true
            BridgeState.log("Sinric Pro conectado ✔")
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            try {
                handle(webSocket, text)
            } catch (e: Exception) {
                BridgeState.log("Error procesando orden: ${e.message}")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            lost(webSocket, "cerrado ($code)")
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val code = response?.code
            val why = if (code == 401 || code == 403) {
                "rechazado ($code): revisa App Key y Device ID"
            } else {
                t.message ?: "error de red"
            }
            lost(webSocket, why)
        }
    }

    // ---------------------------------------------------------------- órdenes

    private class Result(val ok: Boolean, val message: String, val value: JSONObject)

    private fun success(value: JSONObject) = Result(true, "OK", value)
    private fun failure(message: String) = Result(false, message, JSONObject())

    private fun valueOf(req: JSONObject): JSONObject = when (val v = req.opt("value")) {
        is JSONObject -> v
        is String -> try {
            JSONObject(v)
        } catch (e: Exception) {
            JSONObject()
        }
        else -> JSONObject()
    }

    private fun handle(webSocket: WebSocket, text: String) {
        val root = JSONObject(text)
        if (root.has("timestamp")) return // the server sends its clock right after connecting
        val req = root.optJSONObject("payload") ?: return
        if (req.optString("type") != "request") return
        val reqDevice = req.optString("deviceId")
        if (reqDevice.isNotEmpty() && reqDevice != deviceId) return

        val action = req.optString("action")
        val value = valueOf(req)
        BridgeState.log("Google → $action $value")

        execute(action, value) { result -> respond(webSocket, req, action, result) }
    }

    private fun respond(webSocket: WebSocket, req: JSONObject, action: String, result: Result) {
        val payload = JSONObject()
        payload.put("action", action)
        payload.put("clientId", req.optString("clientId"))
        payload.put("createdAt", System.currentTimeMillis() / 1000)
        payload.put("deviceId", deviceId)
        payload.put("message", result.message)
        payload.put("replyToken", req.optString("replyToken"))
        payload.put("scope", "device")
        payload.put("success", result.ok)
        payload.put("type", "response")
        payload.put("value", result.value)
        if (req.has("instanceId")) payload.put("instanceId", req.optString("instanceId"))

        val payloadText = payload.toString()
        val signature = sign(payloadText)
        val message = "{\"header\":{\"payloadVersion\":2,\"signatureVersion\":1}," +
            "\"payload\":$payloadText,\"signature\":{\"HMAC\":\"$signature\"}}"
        webSocket.send(message)
        if (!result.ok) BridgeState.log("Respondí con error: ${result.message}")
    }

    /** Runs the action and calls [done] exactly once with the result to send back to Google. */
    private fun execute(action: String, v: JSONObject, done: (Result) -> Unit) {
        val tv = tvProvider()
        if (tv == null) {
            done(failure("TV controller not ready"))
            return
        }

        // Power is special: when the TV is off it does not answer, so "on" uses Wake-on-LAN.
        if (action == "setPowerState") {
            val on = v.optString("state").equals("On", ignoreCase = true)
            if (on) {
                if (tv.powerOn()) {
                    done(success(JSONObject().put("state", "On")))
                } else {
                    done(failure("Set the TV MAC address in the app"))
                }
            } else {
                if (tv.connected) tv.powerOff()
                done(success(JSONObject().put("state", "Off")))
            }
            return
        }

        if (!tv.connected) {
            done(failure("TV not connected (is it on?)"))
            return
        }

        when (action) {
            "setVolume" -> {
                val volume = v.optInt("volume", 0)
                tv.setVolume(volume)
                done(success(JSONObject().put("volume", volume)))
            }
            "adjustVolume" -> {
                var delta = v.optInt("volume", 0)
                // "turn it up" with no amount: use a default step of 10.
                if (delta == 0 && v.optBoolean("volumeDefault", false)) delta = 10
                tv.adjustVolume(delta) { level -> done(success(JSONObject().put("volume", level))) }
            }
            "setMute" -> {
                val mute = v.optBoolean("mute", false)
                tv.setMute(mute)
                done(success(JSONObject().put("mute", mute)))
            }
            "mediaControl" -> {
                val control = v.optString("control")
                if (tv.media(control)) {
                    done(success(JSONObject().put("control", control)))
                } else {
                    done(failure("Unsupported control: $control"))
                }
            }
            "selectInput" -> {
                val input = v.optString("input")
                tv.selectInput(input) { found ->
                    if (found != null) {
                        done(success(JSONObject().put("input", input)))
                    } else {
                        done(failure("Input not found: $input"))
                    }
                }
            }
            "changeChannel" -> {
                val ch = v.optJSONObject("channel")
                val number = ch?.optString("number")?.toIntOrNull()
                val name = ch?.optString("name").orEmpty()
                val reply = { resolved: String? ->
                    if (resolved != null) {
                        done(success(JSONObject().put("channel", JSONObject().put("name", resolved))))
                    } else {
                        done(failure("Channel not found: $name"))
                    }
                }
                if (number != null) {
                    tv.changeChannelNumber(number, reply)
                } else if (name.isNotEmpty()) {
                    tv.changeChannelName(name, reply)
                } else {
                    done(failure("Missing channel"))
                }
            }
            "skipChannels" -> {
                val count = v.optInt("channelCount", 0)
                tv.skipChannels(count)
                done(success(JSONObject().put("channel", JSONObject().put("name", ""))))
            }
            else -> done(failure("Unsupported action"))
        }
    }

    private fun sign(payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(appSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val raw = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(raw, Base64.NO_WRAP)
    }
}
