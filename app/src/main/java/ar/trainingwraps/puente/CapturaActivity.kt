package ar.trainingwraps.puente

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout
import android.widget.TextView
import ar.trainingwraps.puente.Protocolo.msg

/**
 * TABLET. Pantalla transparente que se abre cuando el puntero cruza al celular.
 * Atrapa el mouse (pointer capture) y el teclado, y los manda al celular.
 * Para volver: llevar el puntero al borde del celular que da a la tablet,
 * tocar la pantalla de la tablet con el dedo, o Ctrl + Alt + Q.
 */
class CapturaActivity : Activity() {

    companion object {
        @Volatile var actual: CapturaActivity? = null
        fun cerrarSiAbierta(avisar: Boolean) {
            actual?.cerrar(avisar)
        }

        private val ESPECIALES = setOf(
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_ESCAPE,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_INSERT
        )
        private val DEL_SISTEMA = setOf(
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
            KeyEvent.KEYCODE_POWER
        )
        private const val META_UTIL = KeyEvent.META_CTRL_MASK or KeyEvent.META_SHIFT_MASK or
            KeyEvent.META_ALT_MASK or KeyEvent.META_META_MASK
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var raiz: FrameLayout
    private var capturado = false
    private var cerrando = false
    private var botones = 0
    private var acento = 0
    // Para touchpads (dan posición absoluta en vez de movimiento)
    private var tpX = Float.NaN
    private var tpY = Float.NaN

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actual = this
        EmisorService.activo?.capturaAbierta()

        raiz = FrameLayout(this)
        raiz.isFocusable = true
        raiz.isFocusableInTouchMode = true
        raiz.setBackgroundColor(Color.TRANSPARENT)

        val aviso = TextView(this)
        aviso.text = "⇄  Mouse y teclado en el celular  ·  tocá la tablet para volver"
        aviso.setTextColor(Color.WHITE)
        aviso.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        val pad = (8 * resources.displayMetrics.density).toInt()
        aviso.setPadding(pad * 2, pad, pad * 2, pad)
        aviso.setBackgroundColor(0xCC101418.toInt())
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        )
        lp.topMargin = pad * 4
        raiz.addView(aviso, lp)
        setContentView(raiz)

        raiz.setOnCapturedPointerListener { _, e -> puntero(e); true }
        raiz.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) cerrar(true)
            true
        }

        // Si en 2 segundos Android no entregó el mouse, se devuelve el control.
        main.postDelayed({ if (!capturado && !cerrando) cerrar(true) }, 2000)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !cerrando) {
            raiz.requestFocus()
            raiz.requestPointerCapture()
        }
    }

    override fun onPointerCaptureChanged(hasCapture: Boolean) {
        super.onPointerCaptureChanged(hasCapture)
        if (hasCapture) {
            capturado = true
        } else if (capturado && !cerrando) {
            cerrar(true)
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        if (!cerrando) {
            cerrando = true
            EmisorService.activo?.enviar(msg(Protocolo.SOLTAR))
            EmisorService.activo?.capturaCerrada()
        }
        if (actual === this) actual = null
        super.onDestroy()
    }

    private fun enviar(m: ByteArray) {
        EmisorService.activo?.enviar(m)
    }

    fun cerrar(avisar: Boolean) {
        if (cerrando) return
        cerrando = true
        if (avisar) enviar(msg(Protocolo.SOLTAR))
        try { raiz.releasePointerCapture() } catch (_: Exception) { }
        if (actual === this) actual = null
        EmisorService.activo?.capturaCerrada()
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    // ---------------------------------------------------------------- mouse

    private fun puntero(e: MotionEvent) {
        if (cerrando) return
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                if (e.isFromSource(android.view.InputDevice.SOURCE_MOUSE_RELATIVE)) {
                    var dx = 0f
                    var dy = 0f
                    for (h in 0 until e.historySize) {
                        dx += e.getHistoricalX(h)
                        dy += e.getHistoricalY(h)
                    }
                    dx += e.x
                    dy += e.y
                    if (dx != 0f || dy != 0f) {
                        enviar(msg(Protocolo.MOVER) { it.writeFloat(dx); it.writeFloat(dy) })
                    }
                } else if (e.isFromSource(android.view.InputDevice.SOURCE_TOUCHPAD)) {
                    if (!tpX.isNaN()) {
                        val dx = e.x - tpX
                        val dy = e.y - tpY
                        if (dx != 0f || dy != 0f) {
                            enviar(msg(Protocolo.MOVER) { it.writeFloat(dx); it.writeFloat(dy) })
                        }
                    }
                    tpX = e.x
                    tpY = e.y
                }
            }
            MotionEvent.ACTION_DOWN -> { tpX = e.x; tpY = e.y }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { tpX = Float.NaN; tpY = Float.NaN }
            MotionEvent.ACTION_SCROLL -> {
                val v = e.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val h = e.getAxisValue(MotionEvent.AXIS_HSCROLL)
                if (v != 0f || h != 0f) enviar(msg(Protocolo.RUEDA) { it.writeFloat(v); it.writeFloat(h) })
            }
        }
        val b = e.buttonState
        if (b != botones) {
            revisarBoton(b, MotionEvent.BUTTON_PRIMARY, Protocolo.BOTON_IZQ)
            revisarBoton(b, MotionEvent.BUTTON_SECONDARY, Protocolo.BOTON_DER)
            revisarBoton(b, MotionEvent.BUTTON_TERTIARY, Protocolo.BOTON_MEDIO)
            revisarBoton(b, MotionEvent.BUTTON_BACK, Protocolo.BOTON_ATRAS)
            revisarBoton(b, MotionEvent.BUTTON_FORWARD, Protocolo.BOTON_ADELANTE)
            botones = b
        }
    }

    private fun revisarBoton(estado: Int, mascara: Int, codigo: Int) {
        val antes = botones and mascara != 0
        val ahora = estado and mascara != 0
        if (antes != ahora) enviar(msg(Protocolo.BOTON) { it.writeInt(codigo); it.writeBoolean(ahora) })
    }

    // ---------------------------------------------------------------- teclado

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val k = e.keyCode
        if (k in DEL_SISTEMA) return super.dispatchKeyEvent(e)
        if (e.action != KeyEvent.ACTION_DOWN || cerrando) return true
        if (k == KeyEvent.KEYCODE_Q && e.isCtrlPressed && e.isAltPressed) {
            cerrar(true)
            return true
        }
        if (KeyEvent.isModifierKey(k)) return true
        val meta = e.metaState and META_UTIL

        if (k in ESPECIALES || e.isCtrlPressed || e.isMetaPressed) {
            acento = 0
            enviar(msg(Protocolo.TECLA) { it.writeInt(k); it.writeInt(meta) })
            return true
        }
        val u = e.getUnicodeChar(e.metaState)
        if (u == 0) {
            enviar(msg(Protocolo.TECLA) { it.writeInt(k); it.writeInt(meta) })
            return true
        }
        if (u and KeyCharacterMap.COMBINING_ACCENT != 0) {
            // Tecla de acento (tilde, diéresis…): se combina con la próxima letra.
            val a = u and KeyCharacterMap.COMBINING_ACCENT_MASK
            if (acento != 0) mandarTexto(String(Character.toChars(acento)))
            acento = a
            return true
        }
        val txt = if (acento != 0) {
            val combinado = KeyEvent.getDeadChar(acento, u)
            val r = if (combinado != 0) String(Character.toChars(combinado))
            else String(Character.toChars(acento)) + String(Character.toChars(u))
            acento = 0
            r
        } else {
            String(Character.toChars(u))
        }
        mandarTexto(txt)
        return true
    }

    private fun mandarTexto(t: String) {
        enviar(msg(Protocolo.TEXTO) { it.writeUTF(t) })
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean = true
}
