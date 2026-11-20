package ar.trainingwraps.puente

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import ar.trainingwraps.puente.Protocolo.msg
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * CELULAR. Servicio de accesibilidad que recibe el mouse y el teclado de la tablet:
 * dibuja un puntero, convierte los clics en toques y escribe en el campo de texto activo.
 */
class ReceptorService : AccessibilityService() {

    companion object {
        @Volatile var activo: ReceptorService? = null
        @Volatile var estado: String = "Apagado (falta activarlo en Accesibilidad)"
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var ajustes: Ajustes
    private lateinit var wm: WindowManager

    @Volatile private var servidor: ServerSocket? = null
    @Volatile private var conexion: Conexion? = null
    private var nsd: NsdManager? = null
    private var registroNsd: NsdManager.RegistrationListener? = null
    private var cursor: CursorView? = null

    // Puntero
    private var x = 0f
    private var y = 0f
    private var ancho = 1
    private var alto = 1
    private var enControl = false
    private var desdeEntrada = 0L
    private var empuje = 0f
    private var lado = 0
    private var sens = 1.6f

    // Botón izquierdo: se graba el recorrido y se ejecuta como toque o arrastre al soltar
    private var apretado = false
    private var tApretado = 0L
    private val recorrido = ArrayList<FloatArray>()

    // Rueda
    private var ruedaV = 0f
    private var ruedaH = 0f
    private var ruedaOcupada = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        activo = this
        ajustes = Ajustes(this)
        wm = getSystemService(WindowManager::class.java)!!
        crearCursor()
        arrancarServidor()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        apagar()
        if (activo === this) activo = null
        estado = "Apagado (falta activarlo en Accesibilidad)"
        super.onDestroy()
    }

    private fun estado(s: String) {
        estado = s
    }

    // ---------------------------------------------------------------- red

    private fun arrancarServidor() {
        val t = Thread {
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(Protocolo.PUERTO))
                servidor = ss
                main.post { registrarNsd(ss.localPort) }
                estado("Esperando a la tablet")
                while (!ss.isClosed) {
                    val s = ss.accept()
                    val h = Thread { atender(s) }
                    h.isDaemon = true
                    h.name = "puente-cliente"
                    h.start()
                }
            } catch (e: Exception) {
                if (activo === this) estado("Error de red: ${e.message}")
            }
        }
        t.isDaemon = true
        t.name = "puente-servidor"
        t.start()
    }

    private fun atender(s: Socket) {
        val c = try { Conexion(s) } catch (_: Exception) { return }
        try {
            s.soTimeout = 15000 // la tablet manda PING cada 4 s
            val ent = c.entrada
            if (ent.readByte() != Protocolo.HOLA || ent.readInt() != Protocolo.MAGIA) {
                c.cerrar(); return
            }
            val version = ent.readInt()
            val pin = ent.readUTF()
            val ladoRemoto = ent.readByte().toInt()
            val sensRemota = ent.readFloat()
            if (version != Protocolo.VERSION) {
                c.enviar(msg(Protocolo.ERROR) { it.writeUTF("Versiones distintas: actualizá Puente en los dos equipos") })
                Thread.sleep(300); c.cerrar(); return
            }
            if (pin != ajustes.pinPropio) {
                c.enviar(msg(Protocolo.ERROR) { it.writeUTF("PIN incorrecto: revisá el que muestra el celular") })
                Thread.sleep(300); c.cerrar(); return
            }
            // Una sola tablet a la vez: la nueva reemplaza a la anterior.
            conexion?.cerrar()
            conexion = c
            main.post {
                lado = ladoRemoto
                sens = sensRemota
                medirPantalla()
            }
            c.enviar(msg(Protocolo.OK) { it.writeInt(ancho); it.writeInt(alto) })
            estado("Conectado con la tablet")

            while (c.abierta) {
                when (ent.readByte()) {
                    Protocolo.ENTRAR -> { val f = ent.readFloat(); main.post { entrar(f) } }
                    Protocolo.MOVER -> { val dx = ent.readFloat(); val dy = ent.readFloat(); main.post { mover(dx, dy) } }
                    Protocolo.BOTON -> { val b = ent.readInt(); val d = ent.readBoolean(); main.post { boton(b, d) } }
                    Protocolo.RUEDA -> { val v = ent.readFloat(); val h = ent.readFloat(); main.post { rueda(v, h) } }
                    Protocolo.TECLA -> { val k = ent.readInt(); val m = ent.readInt(); main.post { tecla(k, m) } }
                    Protocolo.TEXTO -> { val t = ent.readUTF(); main.post { texto(t) } }
                    Protocolo.SOLTAR -> main.post { salir(false) }
                    Protocolo.PING -> {}
                    else -> throw IOException("Mensaje desconocido")
                }
            }
        } catch (_: Exception) {
        }
        c.cerrar()
        if (conexion === c) {
            conexion = null
            main.post { salir(false) }
            estado("Esperando a la tablet")
        }
    }

    private fun registrarNsd(puerto: Int) {
        try {
            val info = NsdServiceInfo().apply {
                serviceName = "Puente ${Build.MODEL}"
                serviceType = Protocolo.TIPO_NSD
                port = puerto
            }
            val l = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) {}
                override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
            }
            val m = getSystemService(NsdManager::class.java)!!
            m.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
            nsd = m
            registroNsd = l
        } catch (_: Exception) {
        }
    }

    private fun apagar() {
        try { registroNsd?.let { nsd?.unregisterService(it) } } catch (_: Exception) { }
        registroNsd = null
        conexion?.cerrar()
        conexion = null
        try { servidor?.close() } catch (_: Exception) { }
        servidor = null
        try { cursor?.let { wm.removeView(it) } } catch (_: Exception) { }
        cursor = null
    }

    // ---------------------------------------------------------------- puntero

    private fun crearCursor() {
        val v = CursorView(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        lp.fitInsetsTypes = 0
        try {
            wm.addView(v, lp)
            cursor = v
        } catch (_: Exception) {
        }
    }

    private fun medirPantalla() {
        val d = getSystemService(DisplayManager::class.java)!!.getDisplay(Display.DEFAULT_DISPLAY)
        val p = Point()
        @Suppress("DEPRECATION")
        d.getRealSize(p)
        ancho = max(1, p.x)
        alto = max(1, p.y)
    }

    private fun entrar(f: Float) {
        medirPantalla()
        enControl = true
        desdeEntrada = SystemClock.uptimeMillis()
        empuje = 0f
        apretado = false
        recorrido.clear()
        y = (f * alto).coerceIn(0f, alto - 1f)
        x = if (lado == 0) 0f else ancho - 1f
        cursor?.mostrar(x, y)
        estado("Usando el celular")
    }

    private fun mover(dx: Float, dy: Float) {
        if (!enControl) return
        var nx = x + dx * sens
        y = (y + dy * sens).coerceIn(0f, alto - 1f)

        // Volver a la tablet: empujar contra el borde que da a la tablet.
        val borde = if (lado == 0) 0f else ancho - 1f
        val afuera = if (lado == 0) -nx else nx - borde
        if (afuera > 0f) {
            nx = borde
            if (!apretado && SystemClock.uptimeMillis() - desdeEntrada > 300) {
                empuje += afuera
                if (empuje > 30f) { x = nx; salir(true); return }
            }
        } else {
            empuje = 0f
        }
        x = nx.coerceIn(0f, ancho - 1f)
        if (apretado) recorrido.add(floatArrayOf(x, y, (SystemClock.uptimeMillis() - tApretado).toFloat()))
        cursor?.mover(x, y)
    }

    private fun salir(avisar: Boolean) {
        if (!enControl) return
        enControl = false
        apretado = false
        recorrido.clear()
        cursor?.ocultar()
        if (avisar) {
            val f = y / alto
            conexion?.enviar(msg(Protocolo.SALIR) { it.writeFloat(f) })
        }
        if (conexion != null) estado("Conectado con la tablet")
    }

    private fun boton(b: Int, abajo: Boolean) {
        if (!enControl) return
        when (b) {
            Protocolo.BOTON_IZQ -> {
                if (abajo) {
                    apretado = true
                    tApretado = SystemClock.uptimeMillis()
                    recorrido.clear()
                    recorrido.add(floatArrayOf(x, y, 0f))
                    cursor?.apretar(true)
                } else if (apretado) {
                    apretado = false
                    cursor?.apretar(false)
                    soltarIzquierdo()
                }
            }
            Protocolo.BOTON_DER, Protocolo.BOTON_ATRAS -> if (abajo) performGlobalAction(GLOBAL_ACTION_BACK)
            Protocolo.BOTON_MEDIO -> if (abajo) performGlobalAction(GLOBAL_ACTION_HOME)
            Protocolo.BOTON_ADELANTE -> if (abajo) performGlobalAction(GLOBAL_ACTION_RECENTS)
        }
    }

    /** Clic corto = toque; clic largo = mantener; clic con movimiento = arrastre. */
    private fun soltarIzquierdo() {
        if (recorrido.isEmpty()) return
        val dur = SystemClock.uptimeMillis() - tApretado
        val p0 = recorrido[0]
        var dist = 0f
        for (p in recorrido) dist = max(dist, hypot(p[0] - p0[0], p[1] - p0[1]))

        val path = Path()
        path.moveTo(p0[0], p0[1])
        val duracion: Long
        if (dist < 12f) {
            duracion = if (dur < 450) 40L else dur.coerceIn(500L, 3000L)
        } else {
            val paso = max(1, recorrido.size / 60)
            var i = paso
            while (i < recorrido.size) {
                path.lineTo(recorrido[i][0], recorrido[i][1])
                i += paso
            }
            val u = recorrido[recorrido.size - 1]
            path.lineTo(u[0], u[1])
            duracion = dur.coerceIn(80L, 4000L)
        }
        recorrido.clear()
        gesto(GestureDescription.StrokeDescription(path, 0, duracion), null)
    }

    private fun gesto(trazo: GestureDescription.StrokeDescription, alTerminar: (() -> Unit)?) {
        val g = GestureDescription.Builder().addStroke(trazo).build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { alTerminar?.invoke() }
            override fun onCancelled(gestureDescription: GestureDescription?) { alTerminar?.invoke() }
        }, main)
        if (!ok) alTerminar?.invoke()
    }

    private fun rueda(v: Float, h: Float) {
        if (!enControl) return
        ruedaV += v
        ruedaH += h
        if (!ruedaOcupada) despacharRueda()
    }

    /** La rueda se traduce en un deslizamiento corto del dedo, centrado en el puntero. */
    private fun despacharRueda() {
        if (ruedaV == 0f && ruedaH == 0f) return
        val paso = alto * 0.11f
        val dy = (ruedaV * paso).coerceIn(-alto * 0.55f, alto * 0.55f)
        val dx = (-ruedaH * paso).coerceIn(-ancho * 0.55f, ancho * 0.55f)
        ruedaV = 0f
        ruedaH = 0f
        // Lejos de los bordes de arriba y abajo para no abrir notificaciones ni ir al inicio.
        val minY = alto * 0.08f
        val maxY = alto * 0.92f
        val cx = x.coerceIn(abs(dx) / 2 + 2f, ancho - abs(dx) / 2 - 2f)
        val cy = y.coerceIn(minY + abs(dy) / 2, maxY - abs(dy) / 2)
        val path = Path()
        path.moveTo(cx - dx / 2, cy - dy / 2)
        path.lineTo(cx + dx / 2, cy + dy / 2)
        ruedaOcupada = true
        gesto(GestureDescription.StrokeDescription(path, 0, 220L)) {
            ruedaOcupada = false
            despacharRueda()
        }
    }

    // ---------------------------------------------------------------- teclado

    private fun campoActivo() = inputMethod?.currentInputConnection

    private fun texto(t: String) {
        if (!enControl) return
        campoActivo()?.commitText(t, 1, null)
    }

    private fun tecla(k: Int, meta: Int) {
        if (!enControl) return
        val ctrl = meta and KeyEvent.META_CTRL_ON != 0
        if (k == KeyEvent.KEYCODE_ESCAPE || k == KeyEvent.KEYCODE_BACK) { performGlobalAction(GLOBAL_ACTION_BACK); return }
        val ic = campoActivo() ?: return
        if (ctrl) {
            val accion = when (k) {
                KeyEvent.KEYCODE_A -> android.R.id.selectAll
                KeyEvent.KEYCODE_C -> android.R.id.copy
                KeyEvent.KEYCODE_X -> android.R.id.cut
                KeyEvent.KEYCODE_V -> android.R.id.paste
                else -> 0
            }
            if (accion != 0) { ic.performContextMenuAction(accion); return }
        }
        val t = SystemClock.uptimeMillis()
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, k, 0, meta))
        ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, k, 0, meta))
    }
}
