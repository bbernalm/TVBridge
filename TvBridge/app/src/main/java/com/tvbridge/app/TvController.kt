package com.tvbridge.app

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.min

/**
 * High-level TV actions (power, volume, channels, inputs, apps, remote buttons) on top of
 * [WebOsClient]. Everything is non-blocking; lookups that need an answer from the TV take a
 * callback, which may run on any thread.
 */
class TvController(private val tv: WebOsClient, private val cfg: Config) {
    data class Channel(val id: String, val number: String, val name: String)
    data class Input(val id: String, val label: String)
    data class App(val id: String, val title: String)

    val connected: Boolean
        get() = tv.connected

    @Volatile private var channels: List<Channel> = emptyList()
    @Volatile private var apps: List<App> = emptyList()

    private fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    // ---------------------------------------------------------------- encendido

    /** Turns the TV on with Wake-on-LAN. Returns false if the MAC address is missing/invalid. */
    fun powerOn(): Boolean {
        if (cfg.tvMac.isBlank()) {
            BridgeState.log("Falta la dirección MAC del TV para poder encenderlo")
            return false
        }
        val ok = WakeOnLan.sendAsync(cfg.tvMac, cfg.tvIp)
        if (!ok) BridgeState.log("La dirección MAC del TV no es válida")
        return ok
    }

    fun powerOff() {
        tv.request("system/turnOff")
    }

    // ---------------------------------------------------------------- volumen

    fun volumeUp() {
        tv.request("audio/volumeUp")
    }

    fun volumeDown() {
        tv.request("audio/volumeDown")
    }

    fun setMute(mute: Boolean) {
        tv.request("audio/setMute", JSONObject().put("mute", mute))
    }

    fun setVolume(volume: Int) {
        tv.request("audio/setVolume", JSONObject().put("volume", volume.coerceIn(0, 100)))
    }

    /** Changes the volume by [delta] (positive or negative) and reports the new level. */
    fun adjustVolume(delta: Int, cb: (Int) -> Unit) {
        tv.request("audio/getVolume") { r ->
            val current = r?.let {
                it.optInt("volume", it.optJSONObject("volumeStatus")?.optInt("volume", -1) ?: -1)
            } ?: -1
            if (current >= 0) {
                val target = (current + delta).coerceIn(0, 100)
                setVolume(target)
                cb(target)
            } else {
                // Could not read the level: fall back to single steps.
                repeat(min(abs(delta), 20)) { if (delta > 0) volumeUp() else volumeDown() }
                cb(0)
            }
        }
    }

    // ---------------------------------------------------------------- canales

    fun channelUp() {
        tv.request("tv/channelUp")
    }

    fun channelDown() {
        tv.request("tv/channelDown")
    }

    fun skipChannels(count: Int) {
        repeat(min(abs(count), 50)) { if (count > 0) channelUp() else channelDown() }
    }

    private fun loadChannels(cb: (List<Channel>) -> Unit) {
        val cached = channels
        if (cached.isNotEmpty()) {
            cb(cached)
            return
        }
        tv.request("tv/getChannelList") { r ->
            val arr = r?.optJSONArray("channelList")
            val list = ArrayList<Channel>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    list.add(
                        Channel(
                            o.optString("channelId"),
                            o.optString("channelNumber"),
                            o.optString("channelName"),
                        ),
                    )
                }
            }
            channels = list
            cb(list)
        }
    }

    private fun openChannel(ch: Channel) {
        tv.request("tv/openChannel", JSONObject().put("channelId", ch.id))
    }

    /** Types the digits like the real remote does (fallback when the channel list is empty). */
    fun typeDigits(number: Int) {
        for (c in number.toString()) tv.button(c.toString())
        tv.button("ENTER")
    }

    /** Goes to channel [number]; reports the channel name (or the number if unknown). */
    fun changeChannelNumber(number: Int, cb: (String?) -> Unit) {
        loadChannels { list ->
            val ch = list.firstOrNull { it.number.toIntOrNull() == number }
            if (ch != null) {
                openChannel(ch)
                cb(ch.name)
            } else {
                typeDigits(number)
                cb(number.toString())
            }
        }
    }

    /** Goes to the channel whose name matches [name]; reports its name or null if not found. */
    fun changeChannelName(name: String, cb: (String?) -> Unit) {
        val q = norm(name)
        loadChannels { list ->
            val ch = list.firstOrNull { norm(it.name) == q }
                ?: if (q.isEmpty()) null else list.firstOrNull { norm(it.name).contains(q) }
            if (ch != null) {
                openChannel(ch)
                cb(ch.name)
            } else {
                cb(null)
            }
        }
    }

    // ---------------------------------------------------------------- entradas y apps

    fun loadInputs(cb: (List<Input>) -> Unit) {
        tv.request("tv/getExternalInputList") { r ->
            val arr = r?.optJSONArray("devices")
            val list = ArrayList<Input>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    if (id.isNotEmpty()) list.add(Input(id, o.optString("label", id)))
                }
            }
            cb(list)
        }
    }

    fun switchInput(id: String) {
        tv.request("tv/switchInput", JSONObject().put("inputId", id))
    }

    fun loadApps(cb: (List<App>) -> Unit) {
        val cached = apps
        if (cached.isNotEmpty()) {
            cb(cached)
            return
        }
        tv.request("com.webos.applicationManager/listLaunchPoints") { r ->
            val arr = r?.optJSONArray("launchPoints")
            val seen = HashSet<String>()
            val list = ArrayList<App>()
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val id = o.optString("id")
                    val title = o.optString("title")
                    if (id.isNotEmpty() && title.isNotBlank() && seen.add(id)) list.add(App(id, title))
                }
            }
            list.sortBy { it.title.lowercase() }
            apps = list
            cb(list)
        }
    }

    fun launchApp(id: String) {
        tv.request("system.launcher/launch", JSONObject().put("id", id))
    }

    /**
     * Voice "change input to X": X can be an HDMI input, the TV tuner, or the name of an app.
     * Reports the name that was selected, or null if nothing matched.
     */
    fun selectInput(raw: String, cb: (String?) -> Unit) {
        val q = norm(raw)
        loadInputs { inputs ->
            val hit = inputs.firstOrNull { norm(it.id) == q || norm(it.label) == q }
            if (hit != null) {
                switchInput(hit.id)
                cb(hit.label)
            } else if (q == "tv" || q == "livetv" || q.contains("antena") || q.contains("televisi")) {
                launchApp("com.webos.app.livetv")
                cb("TV")
            } else {
                loadApps { list ->
                    val app = list.firstOrNull { norm(it.title) == q }
                        ?: if (q.isEmpty()) null else list.firstOrNull { norm(it.title).contains(q) }
                    if (app != null) {
                        launchApp(app.id)
                        cb(app.title)
                    } else {
                        cb(null)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- reproducción y control

    /** Play / Pause / Stop / Rewind / FastForward. Returns false for unsupported controls. */
    fun media(control: String): Boolean {
        val uri = when (control.lowercase()) {
            "play" -> "media.controls/play"
            "pause" -> "media.controls/pause"
            "stop" -> "media.controls/stop"
            "rewind" -> "media.controls/rewind"
            "fastforward" -> "media.controls/fastForward"
            else -> return false
        }
        tv.request(uri)
        return true
    }

    fun button(name: String) = tv.button(name)

    fun sendText(text: String) {
        tv.request("com.webos.service.ime/insertText", JSONObject().put("text", text).put("replace", 0))
    }

    fun sendEnter() {
        tv.request("com.webos.service.ime/sendEnterKey")
    }

    fun deleteChars(count: Int) {
        tv.request("com.webos.service.ime/deleteCharacters", JSONObject().put("count", count))
    }

    fun toast(message: String) {
        tv.request("system.notifications/createToast", JSONObject().put("message", message))
    }
}
