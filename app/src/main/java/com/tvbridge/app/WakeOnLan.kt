package com.tvbridge.app

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Wake-on-LAN: the only way to turn on a TV that is off, because a TV in standby does not
 * answer on its control port. The TV needs the "turn on via Wi-Fi/LAN" option enabled.
 */
object WakeOnLan {
    /** Parses AA:BB:CC:DD:EE:FF (also with - or no separators) into 6 bytes, or null. */
    fun parseMac(mac: String): ByteArray? {
        val hex = mac.filter { it.isLetterOrDigit() }
        if (hex.length != 12) return null
        if (!hex.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) return null
        return ByteArray(6) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
    }

    /** Sends the magic packet on a background thread. Returns false if the MAC is not valid. */
    fun sendAsync(mac: String, tvIp: String): Boolean {
        val m = parseMac(mac) ?: return false
        Thread {
            try {
                send(m, tvIp)
                BridgeState.log("Wake-on-LAN enviado")
            } catch (e: Exception) {
                BridgeState.log("Wake-on-LAN falló: ${e.message}")
            }
        }.start()
        return true
    }

    private fun send(mac: ByteArray, tvIp: String) {
        val packet = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) packet[i] = 0xFF.toByte()
        for (i in 0 until 16) System.arraycopy(mac, 0, packet, 6 + i * 6, 6)

        val targets = ArrayList<String>()
        targets.add("255.255.255.255")
        val parts = tvIp.trim().split(".")
        if (parts.size == 4 && parts.all { it.toIntOrNull() != null }) {
            targets.add("${parts[0]}.${parts[1]}.${parts[2]}.255")
        }

        DatagramSocket().use { socket ->
            socket.broadcast = true
            // A few repeats: a single UDP packet can get lost on Wi-Fi.
            repeat(3) {
                for (target in targets) {
                    for (port in intArrayOf(9, 7)) {
                        val addr = InetAddress.getByName(target)
                        socket.send(DatagramPacket(packet, packet.size, addr, port))
                    }
                }
                Thread.sleep(150)
            }
        }
    }
}
