package ar.trainingwraps.puente

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.net.Inet4Address

/** Pantalla de configuración (modo oscuro). La misma app sirve para la tablet y para el celular. */
class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var ajustes: Ajustes
    private lateinit var contenido: LinearLayout
    private val dp get() = resources.displayMetrics.density

    // Colores
    private val FONDO = Color.rgb(18, 18, 20)
    private val TARJETA = Color.rgb(30, 31, 35)
    private val TEXTO = Color.rgb(235, 235, 240)
    private val TENUE = Color.rgb(160, 163, 172)
    private val ACENTO = Color.rgb(0, 229, 255)
    private val BIEN = Color.rgb(80, 220, 120)
    private val MAL = Color.rgb(255, 99, 99)

    // Vistas que se refrescan
    private var estadoTxt: TextView? = null
    private var permisosTxt: TextView? = null
    private var ipCampo: EditText? = null
    private var encontradosCaja: LinearLayout? = null

    // Búsqueda de celulares en la red
    private var nsd: NsdManager? = null
    private var buscador: NsdManager.DiscoveryListener? = null
    private val pendientes = ArrayDeque<NsdServiceInfo>()
    private var resolviendo = false
    private val encontrados = LinkedHashMap<String, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ajustes = Ajustes(this)
        window.statusBarColor = FONDO
        window.navigationBarColor = FONDO

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(FONDO)
        contenido = LinearLayout(this)
        contenido.orientation = LinearLayout.VERTICAL
        val p = (20 * dp).toInt()
        contenido.setPadding(p, p, p, p)
        scroll.addView(contenido)
        scroll.setOnApplyWindowInsetsListener { v, ins ->
            val b = ins.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            ins
        }
        setContentView(scroll)
        armar()
    }

    override fun onResume() {
        super.onResume()
        refrescar.run()
        if (esTablet()) buscarCelulares()
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(refrescar)
        pararBusqueda()
    }

    private fun esTablet(): Boolean = when (ajustes.modo) {
        1 -> true
        2 -> false
        else -> resources.configuration.smallestScreenWidthDp >= 600
    }

    // ---------------------------------------------------------------- armado de pantalla

    private fun armar() {
        contenido.removeAllViews()
        estadoTxt = null; permisosTxt = null; ipCampo = null; encontradosCaja = null

        titulo("Puente")
        texto("Un solo teclado y un solo mouse para la tablet y el celular. El puntero pasa de una pantalla a la otra por el borde, y el teclado escribe en el equipo donde está el puntero.", TENUE)

        val tablet = esTablet()
        val modo = LinearLayout(this)
        modo.orientation = LinearLayout.HORIZONTAL
        modo.addView(boton(if (tablet) "● Este equipo es la TABLET" else "Este equipo es la tablet", tablet) {
            ajustes.modo = 1; armar(); refrescar.run(); buscarCelulares()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        modo.addView(espacio(8, 0))
        modo.addView(boton(if (!tablet) "● Este equipo es el CELULAR" else "Este equipo es el celular", !tablet) {
            ajustes.modo = 2; pararBusqueda(); armar(); refrescar.run()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        contenido.addView(modo)
        contenido.addView(espacio(0, 16))

        if (tablet) armarTablet() else armarCelular()
    }

    private fun armarCelular() {
        val t = tarjeta()
        subtitulo(t, "Estado")
        estadoTxt = texto(t, "", TEXTO)

        subtitulo(t, "PIN de este celular")
        val pin = texto(t, ajustes.pinPropio, ACENTO)
        pin.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
        pin.typeface = Typeface.MONOSPACE
        pin.letterSpacing = 0.15f

        subtitulo(t, "Dirección en la red")
        val ips = Red.ipsLocales(this)
        texto(t, if (ips.isEmpty()) "Sin red: conectá el celular al mismo Wi-Fi que la tablet" else ips.joinToString("   "), TEXTO)
            .typeface = Typeface.MONOSPACE

        val pasos = tarjeta()
        subtitulo(pasos, "Paso 1 — Activar Puente en Accesibilidad")
        texto(pasos, "Abrí Accesibilidad. Puente está arriba de todo, en «Aplicaciones descargadas». Tocalo y activalo. Android va a avisar que la app puede ver y controlar la pantalla: es lo que necesita para mover el puntero y escribir.", TENUE)
        pasos.addView(boton("Abrir Accesibilidad", true) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        pasos.addView(espacio(0, 12))
        subtitulo(pasos, "Si el interruptor aparece gris («configuración restringida»)")
        texto(pasos, "Pasa con las apps instaladas desde un APK: Puente aparece en gris con «Controlada por la configuración restringida». Tocalo una vez (sale el aviso); recién ahí Android habilita la opción. Después tocá el botón de abajo, en la info de la app abrí el menú de tres puntos de arriba a la derecha, elegí permitir la configuración restringida y confirmá con tu huella o PIN. Volvé al paso 1.", TENUE)
        pasos.addView(boton("Abrir info de Puente", false) { abrirInfoApp() })

        val nota = tarjeta()
        subtitulo(nota, "Cómo se usa")
        texto(nota, "• Clic = tocar. Clic sostenido = mantener apretado. Clic y arrastrar = deslizar (se ejecuta al soltar).\n• Rueda = desplazar.  Clic derecho o Esc = Atrás.  Clic de la rueda = Inicio.\n• Para escribir, primero hacé clic en un campo de texto.\n• Ctrl+A / C / X / V = seleccionar todo, copiar, cortar, pegar.\n• Para volver a la tablet, llevá el puntero contra el borde del celular que da a la tablet.", TENUE)
    }

    private fun armarTablet() {
        val t = tarjeta()
        subtitulo(t, "Estado")
        estadoTxt = texto(t, "", TEXTO)
        permisosTxt = texto(t, "", TENUE)

        val per = tarjeta()
        subtitulo(per, "Permisos de esta tablet (una sola vez)")
        per.addView(boton("1. Permitir «Mostrar sobre otras apps»", false) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        per.addView(espacio(0, 8))
        per.addView(boton("2. Xiaomi: ventanas emergentes en segundo plano", false) { abrirPermisosXiaomi() })
        texto(per, "En «Otros permisos», activá el de abrir ventanas nuevas (o mostrar ventanas emergentes) mientras se ejecuta en segundo plano. Sin ese permiso, Xiaomi no deja que el puntero pase al celular.", TENUE)
        per.addView(espacio(0, 8))
        per.addView(boton("3. Batería sin restricciones", false) { pedirBateria() })

        val con = tarjeta()
        subtitulo(con, "Celular")
        val cajaEnc = LinearLayout(this)
        cajaEnc.orientation = LinearLayout.VERTICAL
        con.addView(cajaEnc)
        encontradosCaja = cajaEnc
        dibujarEncontrados()

        texto(con, "Dirección del celular (se completa sola si lo encuentra):", TENUE)
        val ip = campo(ajustes.ip, "Ej.: 192.168.0.25", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        con.addView(ip); ipCampo = ip
        texto(con, "PIN que muestra el celular:", TENUE)
        val pin = campo(ajustes.pinRemoto, "6 números", InputType.TYPE_CLASS_NUMBER)
        con.addView(pin)

        texto(con, "¿De qué lado de la tablet está el celular?", TENUE)
        val grupo = RadioGroup(this)
        val der = radio("A la DERECHA (el puntero sale por el borde derecho)")
        val izq = radio("A la IZQUIERDA (el puntero sale por el borde izquierdo)")
        grupo.addView(der); grupo.addView(izq)
        if (ajustes.lado == 0) der.isChecked = true else izq.isChecked = true
        con.addView(grupo)

        val sensTxt = texto(con, "", TENUE)
        val barra = SeekBar(this)
        barra.max = 30 // 0.5 a 3.5
        barra.progress = ((ajustes.sensibilidad - 0.5f) * 10).toInt().coerceIn(0, 30)
        fun mostrarSens() { sensTxt.text = "Velocidad del puntero en el celular: ×%.1f".format(0.5f + barra.progress / 10f) }
        mostrarSens()
        barra.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { mostrarSens() }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        con.addView(barra)
        con.addView(espacio(0, 12))

        val fila = LinearLayout(this)
        fila.orientation = LinearLayout.HORIZONTAL
        fila.addView(boton("Conectar", true) {
            ajustes.ip = ip.text.toString()
            ajustes.pinRemoto = pin.text.toString()
            ajustes.lado = if (der.isChecked) 0 else 1
            ajustes.sensibilidad = 0.5f + barra.progress / 10f
            conectar()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fila.addView(espacio(8, 0))
        fila.addView(boton("Detener", false) {
            startService(Intent(this, EmisorService::class.java).setAction(EmisorService.DETENER))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        con.addView(fila)

        val nota = tarjeta()
        subtitulo(nota, "Cómo se usa")
        texto(nota, "• Con la conexión activa aparece una línea celeste finita en el borde. Llevá el puntero ahí y pasa al celular.\n• Mientras el puntero está en el celular, el teclado escribe en el celular.\n• Para volver: llevá el puntero contra el borde del celular que da a la tablet, tocá la pantalla de la tablet con el dedo, o apretá Ctrl + Alt + Q.\n• Los dos equipos tienen que estar en el mismo Wi-Fi (o la tablet conectada a la zona Wi-Fi del celular).\n• Usalo en redes de confianza: lo que tipeás viaja por el Wi-Fi sin cifrar.", TENUE)
    }

    // ---------------------------------------------------------------- acciones

    private fun conectar() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        startForegroundService(Intent(this, EmisorService::class.java))
    }

    private fun abrirInfoApp() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun abrirPermisosXiaomi() {
        val intentos = listOf(
            Intent("miui.intent.action.APP_PERM_EDITOR").setComponent(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
            ).putExtra("extra_pkgname", packageName),
            Intent("miui.intent.action.APP_PERM_EDITOR").setComponent(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.permissions.AppPermissionsEditorActivity")
            ).putExtra("extra_pkgname", packageName),
            Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", packageName)
        )
        for (i in intentos) {
            try { startActivity(i); return } catch (_: Exception) { }
        }
        abrirInfoApp()
    }

    @Suppress("BatteryLife")
    private fun pedirBateria() {
        val pm = getSystemService(PowerManager::class.java)!!
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            texto("La batería ya está sin restricciones para Puente.", BIEN)
            return
        }
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            abrirInfoApp()
        }
    }

    private val refrescar = object : Runnable {
        override fun run() {
            if (esTablet()) {
                val e = if (EmisorService.activo != null) EmisorService.estado else "Apagado: completá los datos y tocá Conectar"
                estadoTxt?.text = e
                estadoTxt?.setTextColor(if (EmisorService.activo?.conectado == true) BIEN else TEXTO)
                val sobre = Settings.canDrawOverlays(this@MainActivity)
                val bat = getSystemService(PowerManager::class.java)!!.isIgnoringBatteryOptimizations(packageName)
                permisosTxt?.text = (if (sobre) "✓" else "✗") + " Mostrar sobre otras apps     " +
                    (if (bat) "✓" else "✗") + " Batería sin restricciones"
            } else {
                val activo = ReceptorService.activo != null
                estadoTxt?.text = ReceptorService.estado
                estadoTxt?.setTextColor(if (activo) BIEN else MAL)
            }
            main.postDelayed(this, 1000)
        }
    }

    // ---------------------------------------------------------------- búsqueda en la red

    private fun buscarCelulares() {
        if (buscador != null) return
        val m = getSystemService(NsdManager::class.java)!!
        val l = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(t: String?, e: Int) { buscador = null }
            override fun onStopDiscoveryFailed(t: String?, e: Int) {}
            override fun onDiscoveryStarted(t: String?) {}
            override fun onDiscoveryStopped(t: String?) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post { pendientes.addLast(info); resolverSiguiente() }
            }
            override fun onServiceLost(info: NsdServiceInfo) {}
        }
        try {
            m.discoverServices(Protocolo.TIPO_NSD, NsdManager.PROTOCOL_DNS_SD, l)
            nsd = m
            buscador = l
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun resolverSiguiente() {
        if (resolviendo) return
        val info = pendientes.removeFirstOrNull() ?: return
        val m = nsd ?: return
        resolviendo = true
        try {
            m.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, e: Int) {
                    main.post { resolviendo = false; resolverSiguiente() }
                }
                override fun onServiceResolved(i: NsdServiceInfo) {
                    val dir = if (Build.VERSION.SDK_INT >= 34) {
                        i.hostAddresses.firstOrNull { it is Inet4Address }?.hostAddress
                    } else {
                        i.host?.hostAddress
                    }
                    main.post {
                        resolviendo = false
                        if (dir != null) {
                            encontrados[i.serviceName] = dir
                            if (ajustes.ip.isBlank() || encontrados.size == 1) {
                                ipCampo?.let { if (it.text.isBlank()) it.setText(dir) }
                            }
                            dibujarEncontrados()
                        }
                        resolverSiguiente()
                    }
                }
            })
        } catch (_: Exception) {
            resolviendo = false
        }
    }

    private fun pararBusqueda() {
        try { buscador?.let { nsd?.stopServiceDiscovery(it) } } catch (_: Exception) { }
        buscador = null
        pendientes.clear()
        resolviendo = false
    }

    private fun dibujarEncontrados() {
        val caja = encontradosCaja ?: return
        caja.removeAllViews()
        if (encontrados.isEmpty()) {
            texto(caja, "Buscando celulares con Puente en este Wi-Fi…", TENUE)
            return
        }
        texto(caja, "Encontrados (tocá para elegir):", TENUE)
        for ((nombre, dir) in encontrados) {
            caja.addView(boton("$nombre — $dir", false) { ipCampo?.setText(dir) })
            caja.addView(espacio(0, 6))
        }
    }

    // ---------------------------------------------------------------- ayudantes de interfaz

    private fun titulo(t: String) {
        val v = TextView(this)
        v.text = t
        v.setTextColor(ACENTO)
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        v.typeface = Typeface.DEFAULT_BOLD
        contenido.addView(v)
    }

    private fun texto(t: String, color: Int): TextView = texto(contenido, t, color)

    private fun texto(padre: LinearLayout, t: String, color: Int): TextView {
        val v = TextView(this)
        v.text = t
        v.setTextColor(color)
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        v.setPadding(0, (4 * dp).toInt(), 0, (8 * dp).toInt())
        v.setLineSpacing(0f, 1.15f)
        padre.addView(v)
        return v
    }

    private fun subtitulo(padre: LinearLayout, t: String) {
        val v = TextView(this)
        v.text = t
        v.setTextColor(TEXTO)
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        v.typeface = Typeface.DEFAULT_BOLD
        v.setPadding(0, (6 * dp).toInt(), 0, (2 * dp).toInt())
        padre.addView(v)
    }

    private fun tarjeta(): LinearLayout {
        val c = LinearLayout(this)
        c.orientation = LinearLayout.VERTICAL
        val p = (16 * dp).toInt()
        c.setPadding(p, p, p, p)
        c.background = GradientDrawable().apply {
            setColor(TARJETA)
            cornerRadius = 14 * dp
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.bottomMargin = (14 * dp).toInt()
        contenido.addView(c, lp)
        return c
    }

    private fun boton(t: String, principal: Boolean, accion: () -> Unit): Button {
        val b = Button(this)
        b.text = t
        b.isAllCaps = false
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        b.setTextColor(if (principal) Color.BLACK else TEXTO)
        b.background = GradientDrawable().apply {
            setColor(if (principal) ACENTO else Color.rgb(48, 50, 56))
            cornerRadius = 10 * dp
        }
        val p = (12 * dp).toInt()
        b.setPadding(p, p, p, p)
        b.gravity = Gravity.CENTER
        b.setOnClickListener { accion() }
        return b
    }

    private fun campo(valor: String, pista: String, tipo: Int): EditText {
        val e = EditText(this)
        e.setText(valor)
        e.hint = pista
        e.inputType = tipo
        e.setTextColor(TEXTO)
        e.setHintTextColor(TENUE)
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        e.background = GradientDrawable().apply {
            setColor(Color.rgb(22, 23, 26))
            cornerRadius = 8 * dp
            setStroke((1 * dp).toInt(), Color.rgb(70, 72, 80))
        }
        val p = (12 * dp).toInt()
        e.setPadding(p, p, p, p)
        return e
    }

    private fun radio(t: String): RadioButton {
        val r = RadioButton(this)
        r.text = t
        r.setTextColor(TEXTO)
        r.id = View.generateViewId()
        return r
    }

    private fun espacio(anchoDp: Int, altoDp: Int): View {
        val v = View(this)
        v.layoutParams = LinearLayout.LayoutParams((anchoDp * dp).toInt(), (altoDp * dp).toInt())
        return v
    }
}
