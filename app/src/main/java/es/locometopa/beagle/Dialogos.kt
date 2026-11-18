package es.locometopa.beagle

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.tv.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Los diálogos de la app, con el aspecto de la app. Se usan igual que AlertDialog.Builder
 * (título, mensaje, lista de opciones, casillas, una vista propia y hasta tres botones).
 */
class DialogoBeagle(@Suppress("unused") val contexto: Context) {
    internal var titulo = ""
    internal var mensaje = ""
    internal var opciones: List<String> = emptyList()
    internal var modo = 0 // 0 sin lista, 1 opciones que se pulsan, 2 una elegida, 3 varias marcadas
    internal var inicialUnica = -1
    internal var marcadasIni: BooleanArray = BooleanArray(0)
    internal var alPulsar: ((DialogoBeagle, Int) -> Unit)? = null
    internal var alMarcar: ((DialogoBeagle, Int, Boolean) -> Unit)? = null
    internal var vista: View? = null
    internal var positivo: Pair<String, ((DialogoBeagle, Int) -> Unit)?>? = null
    internal var negativo: Pair<String, ((DialogoBeagle, Int) -> Unit)?>? = null
    internal var neutro: Pair<String, ((DialogoBeagle, Int) -> Unit)?>? = null

    fun setTitle(t: String) = apply { titulo = t }
    fun setMessage(m: String) = apply { mensaje = m }
    fun setView(v: View) = apply { vista = v }
    fun setItems(o: Array<String>, l: ((DialogoBeagle, Int) -> Unit)?) = apply { opciones = o.toList(); modo = 1; alPulsar = l }
    fun setSingleChoiceItems(o: Array<String>, actual: Int, l: ((DialogoBeagle, Int) -> Unit)?) = apply { opciones = o.toList(); modo = 2; inicialUnica = actual; alPulsar = l }
    fun setMultiChoiceItems(o: Array<String>, marcadas: BooleanArray, l: ((DialogoBeagle, Int, Boolean) -> Unit)?) = apply { opciones = o.toList(); modo = 3; marcadasIni = marcadas; alMarcar = l }
    fun setPositiveButton(t: String, l: ((DialogoBeagle, Int) -> Unit)?) = apply { positivo = t to l }
    fun setNegativeButton(t: String, l: ((DialogoBeagle, Int) -> Unit)?) = apply { negativo = t to l }
    fun setNeutralButton(t: String, l: ((DialogoBeagle, Int) -> Unit)?) = apply { neutro = t to l }

    fun show(): DialogoBeagle { DialogoHost.actual = this; return this }
    fun dismiss() { if (DialogoHost.actual === this) DialogoHost.actual = null }
}

object DialogoHost {
    var actual by mutableStateOf<DialogoBeagle?>(null)
}

/** Pone una vista de Android (campo de texto, texto…) con los colores de la app. */
private fun pintar(v: View) {
    if (v is TextView) {
        v.setTextColor(Estilo.TEXTO)
        v.setHintTextColor(Estilo.APAGADO)
        if (v is EditText) v.backgroundTintList = android.content.res.ColorStateList.valueOf(Estilo.ACENTO)
    }
    if (v is ViewGroup) for (i in 0 until v.childCount) pintar(v.getChildAt(i))
}

/** Se pone una sola vez, en BeagleApp: dibuja el diálogo que haya pedido cualquier pantalla. */
@Composable
fun HostDialogos() {
    val d = DialogoHost.actual ?: return
    androidx.compose.runtime.key(d) { ContenidoDialogo(d) }
}

@Composable
private fun ContenidoDialogo(d: DialogoBeagle) {
    fun pulsar(par: Pair<String, ((DialogoBeagle, Int) -> Unit)?>?, cual: Int) {
        par?.second?.invoke(d, cual)
        d.dismiss()
    }
    val marcadas = remember { mutableStateListOf<Boolean>().also { l -> d.marcadasIni.forEach { l.add(it) } } }
    var unica by remember { mutableStateOf(d.inicialUnica) }
    val foco = remember { FocusRequester() }
    val estado = rememberLazyListState()
    LaunchedEffect(Unit) {
        if (d.modo == 2 && unica in d.opciones.indices) try { estado.scrollToItem(unica) } catch (e: Exception) { }
        try { foco.requestFocus() } catch (e: Exception) { /* aún no está colocado */ }
    }
    Dialog(onDismissRequest = { d.dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color(0xB3000000)), contentAlignment = Alignment.Center) {
            val forma = RoundedCornerShape(20.dp)
            Column(
                Modifier.width(640.dp).heightIn(max = 480.dp).clip(forma).background(Colores.Panel).border(2.dp, Colores.Linea, forma).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (d.titulo.isNotEmpty()) Text(d.titulo, fontSize = 22.sp, color = Colores.Texto, fontWeight = FontWeight.SemiBold)
                if (d.mensaje.isNotEmpty()) Text(d.mensaje, fontSize = 16.sp, color = Colores.Apagado)
                d.vista?.let { v ->
                    remember(v) { pintar(v) }
                    AndroidView(factory = { v.also { (it.parent as? ViewGroup)?.removeView(it) } }, modifier = Modifier.fillMaxWidth())
                }
                if (d.opciones.isNotEmpty()) {
                    LazyColumn(Modifier.weight(1f, fill = false), state = estado, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        itemsIndexed(d.opciones) { i, texto ->
                            val marca = when (d.modo) {
                                2 -> if (i == unica) "●  " else "○  "
                                3 -> if (marcadas.getOrNull(i) == true) "☑  " else "☐  "
                                else -> ""
                            }
                            val dentro = if (d.modo == 2) i == (if (unica in d.opciones.indices) unica else 0) else i == 0
                            Fila(
                                onClick = {
                                    when (d.modo) {
                                        1 -> { d.alPulsar?.invoke(d, i); d.dismiss() }
                                        2 -> { unica = i; d.alPulsar?.invoke(d, i); d.dismiss() }
                                        3 -> { val n = marcadas[i].not(); marcadas[i] = n; d.marcadasIni[i] = n; d.alMarcar?.invoke(d, i, n) }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().let { if (dentro) it.focusRequester(foco) else it },
                                seleccionada = d.modo == 2 && i == unica,
                            ) {
                                Text(marca + texto, fontSize = 18.sp, color = Colores.Texto, maxLines = 2)
                            }
                        }
                    }
                }
                val botones = listOfNotNull(d.neutro?.let { Triple(it, -3, false) }, d.negativo?.let { Triple(it, -2, false) }, d.positivo?.let { Triple(it, -1, true) })
                if (botones.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                    for ((par, cual, principal) in botones) {
                        val sinLista = d.opciones.isEmpty() && d.vista == null
                        Boton(par.first, { pulsar(par, cual) }, principal, if (sinLista && principal) Modifier.focusRequester(foco) else Modifier)
                    }
                }
            }
        }
    }
}

/**
 * El mismo diálogo para el reproductor, que está hecho con vistas de Android (no con Compose):
 * panel redondo con título, opciones que se eligen con el mando y botón de cerrar.
 */
class DialogoVista(private val actividad: android.app.Activity, private val titulo: String, private val textos: List<String>, private val alElegir: (Int) -> Unit) {
    private var dialogo: android.app.Dialog? = null
    private var marcada = -1

    /** La opción en la que empieza el foco (por ejemplo el canal que se está viendo). */
    fun seleccionar(i: Int) { marcada = i }

    private fun fondo(color: Int, borde: Int, radio: Float, grosor: Int): android.graphics.drawable.GradientDrawable =
        android.graphics.drawable.GradientDrawable().apply { setColor(color); cornerRadius = radio; setStroke(grosor, borde) }

    fun show(): DialogoVista {
        val dp = actividad.resources.displayMetrics.density
        val d = android.app.Dialog(actividad, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
        val caja = android.widget.LinearLayout(actividad).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((22 * dp).toInt(), (20 * dp).toInt(), (22 * dp).toInt(), (20 * dp).toInt())
            background = fondo(Estilo.PANEL, Estilo.LINEA, 20 * dp, (2 * dp).toInt())
        }
        caja.addView(TextView(actividad).apply {
            text = titulo; textSize = 22f; setTextColor(Estilo.TEXTO); setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, (12 * dp).toInt())
        })
        val lista = android.widget.LinearLayout(actividad).apply { orientation = android.widget.LinearLayout.VERTICAL }
        var primero: View? = null
        var elegido: View? = null
        fun fila(texto: String, accion: () -> Unit): TextView = TextView(actividad).apply {
            text = texto; textSize = 18f; setTextColor(Estilo.TEXTO); maxLines = 2
            setPadding((18 * dp).toInt(), (11 * dp).toInt(), (18 * dp).toInt(), (11 * dp).toInt())
            isFocusable = true; isClickable = true
            val normal = fondo(Estilo.PANEL, android.graphics.Color.TRANSPARENT, 14 * dp, (2 * dp).toInt())
            val foco = fondo(0xFF27313F.toInt(), Estilo.ACENTO, 14 * dp, (2 * dp).toInt())
            background = android.graphics.drawable.StateListDrawable().apply { addState(intArrayOf(android.R.attr.state_focused), foco); addState(intArrayOf(), normal) }
            val m = android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            m.bottomMargin = (6 * dp).toInt(); layoutParams = m
            setOnClickListener { accion() }
        }
        textos.forEachIndexed { i, t ->
            val v = fila(t) { d.dismiss(); alElegir(i) }
            lista.addView(v)
            if (primero == null) primero = v
            if (i == marcada) elegido = v
        }
        val scroll = android.widget.ScrollView(actividad).apply { addView(lista) }
        caja.addView(scroll, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val cerrar = TextView(actividad).apply {
            text = "Cerrar"; textSize = 17f; setTextColor(Estilo.TEXTO); setTypeface(typeface, android.graphics.Typeface.BOLD); gravity = android.view.Gravity.CENTER
            setPadding((22 * dp).toInt(), (9 * dp).toInt(), (22 * dp).toInt(), (9 * dp).toInt())
            isFocusable = true; isClickable = true
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), fondo(android.graphics.Color.WHITE, android.graphics.Color.WHITE, 999f, 0))
                addState(intArrayOf(), fondo(0xFF27313F.toInt(), 0xFF27313F.toInt(), 999f, 0))
            }
            onFocusChangeListener = View.OnFocusChangeListener { v, f -> (v as TextView).setTextColor(if (f) Estilo.FONDO else Estilo.TEXTO) }
            setOnClickListener { d.dismiss() }
        }
        caja.addView(cerrar, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = android.view.Gravity.END; topMargin = (10 * dp).toInt() })
        d.setContentView(caja)
        d.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0xB3000000.toInt()))
            val ancho = (640 * dp).toInt().coerceAtMost((actividad.resources.displayMetrics.widthPixels * 0.9).toInt())
            setLayout(ancho, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        }
        d.show()
        dialogo = d
        val foco = elegido ?: primero ?: cerrar
        foco.post {
            foco.requestFocus()
            if (foco !== cerrar) scroll.post { scroll.requestChildFocus(foco, foco) }
        }
        return this
    }
}
