package com.tvbridge.app

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** State shared between the service and the screen (log + status flags). */
object BridgeState {
    @Volatile var serviceRunning = false
    @Volatile var tvConnected = false
    /** True while the TV shows the "allow this device?" prompt and waits for the user. */
    @Volatile var tvPairing = false
    @Volatile var cloudConnected = false

    private val lines = ArrayList<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    @Synchronized
    fun log(msg: String) {
        lines.add("${fmt.format(Date())}  $msg")
        while (lines.size > 80) lines.removeAt(0)
        Log.d("TvBridge", msg)
    }

    /** Last lines of the log, newest first. */
    @Synchronized
    fun text(): String = lines.takeLast(40).reversed().joinToString("\n")

    /** Called when the voice bridge stops (the TV connection may stay up for the on-screen remote). */
    fun bridgeStopped() {
        serviceRunning = false
        cloudConnected = false
    }
}
