package ar.trainingwraps.puente

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import ar.trainingwraps.puente.Protocolo.msg
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * TABLET. Mantiene la conexión con el celular y una franja finita en el borde de la pantalla.
 * Cuando el puntero toca la franja, abre CapturaActivity, que manda mouse y teclado al celular.
 */
class EmisorService : Service() {

    companion object {
        @Volatile var activo: EmisorService? = null
        @Volatile var estado: String = "Apagado"
        const val CANAL = "puente"
        const val DETENER = "ar.trainingwraps.puente.DETENER"
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var ajustes: Ajustes
    private lateinit var wm: WindowManager
    @Volatile private var conexion: Conexion? = null
    @Volatile private var corriendo = false
    private var franja: View? = null
    private var bloqueadaHasta = 0L
    private var esperandoCaptura = false

    val conectado: Boolean get() = conexion?.abierta == true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == DETENER) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (corriendo) {
            // Cambiaron los datos: reconectar con los nuevos.
            conexion?.cerrar()
            return START_STICKY
        }
        corriendo = true
        activo = this
        ajustes = Ajustes(this)
        wm = getSystemService(WindowManager::class.java)!!
        crearCanal()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notificacion("Buscando el celular…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notificacion("Buscando el celular…"))
        }
        bucleConexion()
        main.postDelayed(ping, 4000)
        return START_STICKY
    }

    override fun onDestroy() {
        corriendo = false
        main.removeCallbacksAndMessages(null)
        CapturaActivity.cerrarSiAbierta(false)
        conexion?.cerrar()
        conexion = null
        quitarFranja()
        if (activo === this) activo = null
        estado = "Apagado"
        super.onDestroy()
    }

    fun enviar(m: ByteArray) {
        conexion?.enviar(m)
    }

    private val ping = object : Runnable {
        override fun run() {
            conexion?.enviar(msg(Protocolo.PING))
            if (corriendo) main.postDelayed(this, 4000)
        }
    }

    private fun estado(s: String) {
        estado = s
        main.post {
            if (corriendo) getSystemService(NotificationManager::class.java)!!.notify(1, notificacion(s))
        }
    }

    // ---------------------------------------------------------------- red

    private fun dormir(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { }
    }

    private fun bucleConexion() {
        val t = Thread {
            while (corriendo) {
                val ip = ajustes.ip
                if (ip.isBlank()) {
                    estado("Falta la dirección del celular")
                    dormir(3000); continue
                }
                var c: Conexion? = null
                try {
                    val s = Socket()
                    s.connect(InetSocketAddress(ip, Protocolo.PUERTO), 4000)
                    c = Conexion(s)
                    c.enviar(msg(Protocolo.HOLA) {
                        it.writeInt(Protocolo.MAGIA)
                        it.writeInt(Protocolo.VERSION)
                        it.writeUTF(ajustes.pinRemoto)
                        it.writeByte(ajustes.lado)
                        it.writeFloat(ajustes.sensibilidad)
                    })
                    val ent = c.entrada
                    when (ent.readByte()) {
                        Protocolo.OK -> { ent.readInt(); ent.readInt() }
                        Protocolo.ERROR -> {
                            estado(ent.readUTF())
                            c.cerrar()
                            dormir(5000); continue
                        }
                        else -> throw IOException("Respuesta desconocida")
                    }
                    conexion = c
                    estado("Conectado: llevá el puntero al borde " + (if (ajustes.lado == 0) "derecho" else "izquierdo"))
                    main.post { mostrarFranja() }
                    while (c.abierta) {
                        when (ent.readByte()) {
                            Protocolo.SALIR -> { ent.readFloat(); main.post { volvioDelCelular() } }
                            Protocolo.PING -> {}
                            else -> throw IOException("Mensaje desconocido")
                        }
                    }
                } catch (_: Exception) {
                }
                c?.cerrar()
                conexion = null
                main.post {
                    CapturaActivity.cerrarSiAbierta(false)
                    quitarFranja()
                }
                if (corriendo) {
                    estado("Sin conexión con el celular, reintentando… (¿mismo Wi-Fi? ¿Puente activo en Accesibilidad?)")
                    dormir(3000)
                }
            }
        }
        t.isDaemon = true
        t.name = "puente-emisor"
        t.start()
    }

    // ---------------------------------------------------------------- franja del borde

    private fun mostrarFranja() {
        if (franja != null || !corriendo || !conectado) return
        if (!Settings.canDrawOverlays(this)) {
            estado("Falta el permiso «Mostrar sobre otras apps»")
            return
        }
        val v = View(this)
        v.setBackgroundColor(0x5500E5FF)
        v.setOnHoverListener { vista, e -> alPasar(vista, e); false }
        val anchoPx = (4 * resources.displayMetrics.density).toInt().coerceAtLeast(3)
        val lp = WindowManager.LayoutParams(
            anchoPx,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or (if (ajustes.lado == 0) Gravity.RIGHT else Gravity.LEFT)
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        lp.fitInsetsTypes = 0
        try {
            wm.addView(v, lp)
            franja = v
        } catch (e: Exception) {
            estado("No se pudo mostrar la franja del borde: ${e.message}")
        }
    }

    private fun quitarFranja() {
        val v = franja ?: return
        franja = null
        try { wm.removeView(v) } catch (_: Exception) { }
    }

    private fun alPasar(v: View, e: MotionEvent) {
        val a = e.actionMasked
        if (a != MotionEvent.ACTION_HOVER_ENTER && a != MotionEvent.ACTION_HOVER_MOVE) return
        if (SystemClock.uptimeMillis() < bloqueadaHasta) return
        if (CapturaActivity.actual != null || esperandoCaptura) return
        val c = conexion ?: return
        val f = if (v.height > 0) (e.y / v.height).coerceIn(0f, 1f) else 0.5f
        c.enviar(msg(Protocolo.ENTRAR) { it.writeFloat(f) })
        esperandoCaptura = true
        val i = Intent(this, CapturaActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
        try { startActivity(i) } catch (_: Exception) { }
        // Si Android no dejó abrir la captura, devolver el control y avisar.
        main.postDelayed({
            if (esperandoCaptura) {
                esperandoCaptura = false
                conexion?.enviar(msg(Protocolo.SOLTAR))
                estado("Android no dejó abrir la captura: revisá el permiso de ventanas emergentes en segundo plano (paso 2)")
                pausarFranja()
            }
        }, 1500)
    }

    /** Lo llama CapturaActivity cuando ya tiene el mouse. */
    fun capturaAbierta() {
        esperandoCaptura = false
    }

    /** El puntero salió del celular por el borde: cerrar la captura. */
    private fun volvioDelCelular() {
        CapturaActivity.cerrarSiAbierta(false)
        pausarFranja()
    }

    /** Lo llama CapturaActivity al cerrarse, por el motivo que sea. */
    fun capturaCerrada() {
        esperandoCaptura = false
        pausarFranja()
        if (conectado) estado("Conectado: llevá el puntero al borde " + (if (ajustes.lado == 0) "derecho" else "izquierdo"))
    }

    /**
     * Al volver, el puntero de la tablet reaparece pegado al borde: se saca la franja
     * un momento para que no salte de nuevo al celular.
     */
    private fun pausarFranja() {
        quitarFranja()
        bloqueadaHasta = SystemClock.uptimeMillis() + 1000
        main.postDelayed({ mostrarFranja() }, 1000)
    }

    // ---------------------------------------------------------------- notificación

    private fun crearCanal() {
        val nm = getSystemService(NotificationManager::class.java)!!
        nm.createNotificationChannel(NotificationChannel(CANAL, "Puente activo", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notificacion(texto: String): Notification {
        val abrir = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val detener = PendingIntent.getService(
            this, 1, Intent(this, EmisorService::class.java).setAction(DETENER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CANAL)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("Puente")
            .setContentText(texto)
            .setContentIntent(abrir)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Detener", detener).build())
            .build()
    }
}
