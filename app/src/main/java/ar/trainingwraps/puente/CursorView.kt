package ar.trainingwraps.puente

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Flecha del puntero que se dibuja en el celular (encima de todo, sin bloquear toques). */
class CursorView(ctx: Context) : View(ctx) {
    private var cx = 0f
    private var cy = 0f
    private var visible = false
    private var apretado = false
    private val loc = IntArray(2)

    private val forma = Path().apply {
        val s = ctx.resources.displayMetrics.density * 1.25f
        moveTo(0f, 0f)
        lineTo(0f, 17f * s)
        lineTo(4.5f * s, 13f * s)
        lineTo(7.5f * s, 20f * s)
        lineTo(10.2f * s, 18.9f * s)
        lineTo(7.2f * s, 12f * s)
        lineTo(13f * s, 12f * s)
        close()
    }
    private val relleno = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val rellenoApretado = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 229, 255)
        style = Paint.Style.FILL
    }
    private val borde = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = ctx.resources.displayMetrics.density * 1.4f
        strokeJoin = Paint.Join.ROUND
    }

    fun mostrar(x: Float, y: Float) {
        visible = true
        mover(x, y)
    }

    fun mover(x: Float, y: Float) {
        cx = x
        cy = y
        invalidate()
    }

    fun apretar(a: Boolean) {
        apretado = a
        invalidate()
    }

    fun ocultar() {
        visible = false
        apretado = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!visible) return
        getLocationOnScreen(loc)
        canvas.save()
        canvas.translate(cx - loc[0], cy - loc[1])
        canvas.drawPath(forma, if (apretado) rellenoApretado else relleno)
        canvas.drawPath(forma, borde)
        canvas.restore()
    }
}
