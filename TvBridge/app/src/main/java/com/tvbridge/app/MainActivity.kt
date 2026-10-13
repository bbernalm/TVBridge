package com.tvbridge.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Single screen: on-screen remote, settings, voice-bridge start/stop and the log. */
class MainActivity : Activity() {
    private class Item(val label: String, val action: () -> Unit)

    private lateinit var etIp: EditText
    private lateinit var etMac: EditText
    private lateinit var etKey: EditText
    private lateinit var etSecret: EditText
    private lateinit var etDevice: EditText
    private lateinit var etChannel: EditText
    private lateinit var etText: EditText
    private lateinit var etToast: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var cfgBox: LinearLayout
    private lateinit var inputsBox: LinearLayout
    private lateinit var appsBox: LinearLayout

    private var listsLoaded = false
    private var muted = false

    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            render()
            maybeLoadLists()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        val cfg = Config.load(this)
        etIp.setText(cfg.tvIp)
        etMac.setText(cfg.tvMac)
        etKey.setText(cfg.appKey)
        etSecret.setText(cfg.appSecret)
        etDevice.setText(cfg.deviceId)
        // First time: show the settings so the user knows where to start.
        cfgBox.visibility = if (cfg.tvReady) View.GONE else View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        TvHub.acquire(this, "ui")
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        TvHub.release("ui")
        super.onPause()
    }

    // ---------------------------------------------------------------- interfaz

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun label(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(16), 0, dp(4))
        })
    }

    private fun cmdBtn(text: String, action: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 13f
        maxLines = 2
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        setOnClickListener { action() }
    }

    /** An invisible placeholder that keeps button rows aligned. */
    private fun gap(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
    }

    private fun row(vararg views: View): LinearLayout {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (v in views) r.addView(v)
        return r
    }

    private fun field(parent: LinearLayout, hint: String, secret: Boolean = false): EditText {
        val et = EditText(this).apply {
            this.hint = hint
            isSingleLine = true
            inputType = if (secret) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            }
        }
        parent.addView(et)
        return et
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(24), dp(16), dp(24))
        }

        root.addView(TextView(this).apply {
            text = "TvBridge"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Control de tu TV LG webOS desde el celular y con Google Home"
            textSize = 14f
            setPadding(0, 0, 0, dp(8))
        })

        tvStatus = TextView(this).apply {
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(8))
        }
        root.addView(tvStatus)

        // ---- Control remoto
        label(root, "Control remoto")
        root.addView(row(
            cmdBtn("Encender") { powerOnClicked() },
            cmdBtn("Apagar") { tvDo { it.powerOff() } },
        ))
        root.addView(row(
            cmdBtn("Vol −") { tvDo { it.volumeDown() } },
            cmdBtn("Silenciar") { tvDo { muted = !muted; it.setMute(muted) } },
            cmdBtn("Vol +") { tvDo { it.volumeUp() } },
        ))
        root.addView(row(
            cmdBtn("Canal −") { tvDo { it.channelDown() } },
            cmdBtn("Canal +") { tvDo { it.channelUp() } },
        ))

        etChannel = EditText(this).apply {
            hint = "Número de canal"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
        }
        root.addView(row(etChannel, cmdBtn("Ir al canal") { goToChannel() }))

        label(root, "Navegación")
        root.addView(row(gap(), cmdBtn("▲") { tvDo { it.button("UP") } }, gap()))
        root.addView(row(
            cmdBtn("◀") { tvDo { it.button("LEFT") } },
            cmdBtn("OK") { tvDo { it.button("ENTER") } },
            cmdBtn("▶") { tvDo { it.button("RIGHT") } },
        ))
        root.addView(row(gap(), cmdBtn("▼") { tvDo { it.button("DOWN") } }, gap()))
        root.addView(row(
            cmdBtn("Atrás") { tvDo { it.button("BACK") } },
            cmdBtn("Inicio") { tvDo { it.button("HOME") } },
            cmdBtn("Salir") { tvDo { it.button("EXIT") } },
        ))

        label(root, "Reproducción")
        root.addView(row(
            cmdBtn("⏪") { tvDo { it.media("Rewind") } },
            cmdBtn("▶") { tvDo { it.media("Play") } },
            cmdBtn("⏸") { tvDo { it.media("Pause") } },
            cmdBtn("⏹") { tvDo { it.media("Stop") } },
            cmdBtn("⏩") { tvDo { it.media("FastForward") } },
        ))

        label(root, "Entradas")
        inputsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(inputsBox)

        label(root, "Aplicaciones")
        appsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(appsBox)
        root.addView(cmdBtn("Recargar entradas y aplicaciones") { listsLoaded = false })

        label(root, "Escribir en el TV (con el teclado del TV abierto)")
        etText = field(root, "Texto")
        root.addView(row(
            cmdBtn("Enviar texto") { sendTextClicked() },
            cmdBtn("Enter") { tvDo { it.sendEnter() } },
        ))

        label(root, "Mostrar un aviso en la pantalla del TV")
        etToast = field(root, "Mensaje")
        root.addView(cmdBtn("Mostrar aviso") { sendToastClicked() })

        // ---- Configuración
        root.addView(cmdBtn("Configuración (mostrar / ocultar)") {
            cfgBox.visibility = if (cfgBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        })
        cfgBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        label(cfgBox, "TV")
        etIp = field(cfgBox, "IP del TV (ej. 192.168.1.50)")
        etMac = field(cfgBox, "MAC del TV, para encenderlo (ej. AA:BB:CC:DD:EE:FF)")
        label(cfgBox, "Sinric Pro (solo para la voz con Google Home)")
        etKey = field(cfgBox, "Sinric Pro · App Key")
        etSecret = field(cfgBox, "Sinric Pro · App Secret", secret = true)
        etDevice = field(cfgBox, "Sinric Pro · Device ID")
        cfgBox.addView(cmdBtn("Guardar (solo control con botones)") { onSaveClicked(start = false) })
        cfgBox.addView(cmdBtn("Guardar e iniciar voz con Google Home") { onSaveClicked(start = true) })
        cfgBox.addView(cmdBtn("Detener voz con Google Home") {
            stopService(Intent(this, BridgeService::class.java))
        })
        cfgBox.addView(cmdBtn("Quitar ahorro de batería (importante)") { askBattery() })
        cfgBox.addView(cmdBtn("Olvidar emparejamiento con el TV") {
            Config.clearClientKey(this)
            TvHub.restart(this)
            toast("Listo. La próxima conexión pedirá aceptar de nuevo en el TV.")
        })
        root.addView(cfgBox)

        label(root, "Registro")
        tvLog = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        root.addView(tvLog)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    // ---------------------------------------------------------------- estado

    private fun render() {
        val run = BridgeState.serviceRunning
        val tvLine = when {
            BridgeState.tvConnected -> "conectado ✔"
            BridgeState.tvPairing -> "mira la pantalla del TV y pulsa Aceptar"
            else -> "sin conexión (¿TV apagado o IP incorrecta?)"
        }
        tvStatus.text = "Voz con Google Home: ${if (run) "ACTIVA" else "detenida"}\n" +
            "TV: $tvLine\n" +
            "Sinric Pro: ${if (BridgeState.cloudConnected) "conectado ✔" else "sin conexión"}"
        val log = BridgeState.text()
        if (tvLog.text.toString() != log) tvLog.text = log
    }

    /** Fills the input and app buttons the first time the TV is connected. */
    private fun maybeLoadLists() {
        val c = TvHub.controller
        if (!BridgeState.tvConnected || c == null) {
            listsLoaded = false
            return
        }
        if (listsLoaded) return
        listsLoaded = true
        c.loadInputs { inputs ->
            runOnUiThread {
                fillButtons(inputsBox, inputs.map { i -> Item(i.label) { tvDo { t -> t.switchInput(i.id) } } })
            }
        }
        c.loadApps { apps ->
            runOnUiThread {
                fillButtons(appsBox, apps.map { a -> Item(a.title) { tvDo { t -> t.launchApp(a.id) } } })
            }
        }
    }

    private fun fillButtons(box: LinearLayout, items: List<Item>) {
        box.removeAllViews()
        if (items.isEmpty()) {
            box.addView(TextView(this).apply {
                text = "No se pudo cargar la lista. Pulsa «Recargar»."
                textSize = 13f
            })
            return
        }
        for (chunk in items.chunked(3)) {
            val r = row()
            for (item in chunk) r.addView(cmdBtn(item.label, item.action))
            repeat(3 - chunk.size) { r.addView(gap()) }
            box.addView(r)
        }
    }

    // ---------------------------------------------------------------- acciones

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun tvDo(action: (TvController) -> Unit) {
        val c = TvHub.controller
        if (c == null) {
            toast("Primero guarda la IP del TV en Configuración")
            return
        }
        if (!c.connected) {
            toast("El TV no está conectado todavía. ¿Está encendido?")
            return
        }
        action(c)
    }

    private fun powerOnClicked() {
        val c = TvHub.controller
        if (c == null) {
            toast("Primero guarda la IP y la MAC del TV en Configuración")
        } else if (!c.powerOn()) {
            toast("Falta una MAC válida del TV en Configuración")
        } else {
            toast("Enviando señal de encendido…")
        }
    }

    private fun goToChannel() {
        val n = etChannel.text.toString().trim().toIntOrNull()
        if (n == null) {
            toast("Escribe un número de canal")
            return
        }
        tvDo { it.changeChannelNumber(n) { _ -> } }
    }

    private fun sendTextClicked() {
        val t = etText.text.toString()
        if (t.isEmpty()) {
            toast("Escribe el texto")
            return
        }
        tvDo { it.sendText(t) }
    }

    private fun sendToastClicked() {
        val t = etToast.text.toString().trim()
        if (t.isEmpty()) {
            toast("Escribe el mensaje")
            return
        }
        tvDo { it.toast(t) }
    }

    private fun onSaveClicked(start: Boolean) {
        val cfg = Config(
            tvIp = etIp.text.toString().trim(),
            tvMac = etMac.text.toString().trim(),
            appKey = etKey.text.toString().trim(),
            appSecret = etSecret.text.toString().trim(),
            deviceId = etDevice.text.toString().trim(),
        )
        if (!cfg.tvReady) {
            toast("Escribe la IP del TV")
            return
        }
        if (start && !cfg.cloudReady) {
            toast("Para la voz completa App Key, App Secret y Device ID de Sinric Pro")
            return
        }
        Config.save(this, cfg)
        listsLoaded = false
        TvHub.restart(this)
        toast("Guardado")
        if (start) {
            if (Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_PERMS)
            } else {
                launchService()
            }
        }
    }

    private fun launchService() {
        startForegroundService(Intent(this, BridgeService::class.java))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        // The service works even if notifications are denied; the notice is just hidden.
        launchService()
    }

    private fun askBattery() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            toast("Ya está sin restricción de batería ✔")
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName")),
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    companion object {
        private const val REQ_PERMS = 42
    }
}
