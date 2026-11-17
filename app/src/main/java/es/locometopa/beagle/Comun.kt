package es.locometopa.beagle

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.widget.TextView

/** La lista que se está viendo: el reproductor hace zapping por ella. Vive solo mientras la app está abierta. */
object Sesion {
    var canales: List<Canal> = emptyList()
    var indice: Int = 0
}

/** Colores y piezas de la pantalla (los mismos tonos que HomeTV). */
object Estilo {
    const val FONDO = 0xFF0D141E.toInt()
    const val PANEL = 0xFF19212D.toInt()
    const val LINEA = 0xFF3A4454.toInt()
    const val TEXTO = 0xFFF4F4F4.toInt()
    const val APAGADO = 0xFFA3AAB5.toInt()
    const val ACENTO = 0xFFE2AE49.toInt()
    const val BIEN = 0xFF7FE0A8.toInt()
    const val AVISO = 0xFFF0C64A.toInt()
    const val MAL = 0xFFFF6B6E.toInt()
    const val NADA = 0xFF666666.toInt()

    fun dp(c: Context, v: Int): Int = (v * c.resources.displayMetrics.density + 0.5f).toInt()

    fun redondo(color: Int, radio: Float): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radio
    }

    /** Fondo de la fila elegida con el mando: se ve bien a tres metros. */
    fun selector(c: Context): Drawable {
        val radio = dp(c, 10).toFloat()
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), redondo(0x33E2AE49, radio).apply { setStroke(dp(c, 2), ACENTO) })
            addState(intArrayOf(android.R.attr.state_pressed), redondo(0x26FFFFFF, radio))
            addState(intArrayOf(), ColorDrawable(0))
        }
    }

    fun texto(c: Context, sp: Float, color: Int, negrita: Boolean = false): TextView = TextView(c).apply {
        textSize = sp
        setTextColor(color)
        if (negrita) typeface = Typeface.DEFAULT_BOLD
    }

    fun colorEstado(orden: Int): Int = when (orden) {
        0 -> BIEN
        1 -> NADA
        else -> MAL
    }
}
