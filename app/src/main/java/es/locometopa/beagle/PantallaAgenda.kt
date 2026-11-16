package es.locometopa.beagle

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val FORMATO_HORA = SimpleDateFormat("HH:mm", Locale("es", "ES"))
private val FORMATO_DIA = SimpleDateFormat("EEEE d 'de' MMMM", Locale("es", "ES"))

private fun mismoDia(a: Long, b: Long): Boolean {
    val x = Calendar.getInstance().apply { timeInMillis = a }
    val y = Calendar.getInstance().apply { timeInMillis = b }
    return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)
}

private fun diaTexto(ms: Long): String {
    val ahora = System.currentTimeMillis()
    return when {
        mismoDia(ms, ahora) -> "Hoy"
        mismoDia(ms, ahora + 86_400_000L) -> "Mañana"
        else -> FORMATO_DIA.format(Date(ms)).replaceFirstChar { it.uppercase() }
    }
}

/** Reproduce el evento en el mejor de mis canales; con ▲▼ se pasa a los otros que lo emiten. */
fun abrirEvento(contexto: Context, cat: Catalogo, e: Evento) {
    val canales = cat.canalesDe(e)
    if (canales.isNotEmpty()) abrirReproductor(contexto, canales, 0)
}

/** Un evento: hora (o «EN DIRECTO»), título, competición y cuántos de mis canales lo dan. */
@Composable
fun FilaEvento(e: Evento, cat: Catalogo, onClick: () -> Unit) {
    val mios = cat.canalesDe(e)
    Fila(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.width(110.dp)) {
            when {
                e.aplazado -> Text("Aplazado", fontSize = 16.sp, color = Colores.Aviso)
                e.vivo -> Text("EN DIRECTO", fontSize = 16.sp, color = Colores.Bien)
                else -> Text(FORMATO_HORA.format(Date(e.inicio)), fontSize = 22.sp, color = Colores.Texto)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(e.titulo, fontSize = 20.sp, color = Colores.Texto)
            val sub = listOf(e.deporte, e.competicion).filter { it.isNotEmpty() && it != e.titulo }.joinToString(" · ")
            if (sub.isNotEmpty()) Text(sub, fontSize = 14.sp, color = Colores.Apagado)
        }
        val (txt, color) = when {
            mios.isNotEmpty() -> (if (mios.size == 1) mios[0].titulo else "${mios[0].titulo} +${mios.size - 1}") to Colores.Bien
            e.posible -> "posible canal" to Colores.Aviso
            e.apagada -> "en una lista apagada" to Colores.Aviso
            e.enAbierto -> "en abierto" to Colores.Apagado
            else -> "sin canal" to Colores.Apagado
        }
        Text(txt, fontSize = 15.sp, color = color)
    }
}

/** Agenda deportiva que da el servidor (ya cruzada con mis canales): filtros a la izquierda, eventos a la derecha. */
@Composable
fun PantallaAgenda(datos: Datos) {
    val contexto = LocalContext.current
    val cat = datos.catalogo
    var filtro by remember { mutableStateOf("Todos") }
    Row(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 32.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        if (cat == null) {
            Text(if (datos.cargando) "Cargando…" else datos.error ?: "Sin datos", fontSize = 20.sp, color = Colores.Apagado)
            return@Row
        }
        if (cat.eventos.isEmpty()) {
            Text("El servidor no tiene agenda ahora mismo.", fontSize = 20.sp, color = Colores.Apagado)
            return@Row
        }
        val deportes = cat.eventos.map { it.deporte }.filter { it.isNotEmpty() }.distinct().sorted()
        val filtros = listOf("Todos", "En directo", "En abierto", "Con mis canales") + deportes
        LazyColumn(Modifier.width(260.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(filtros, key = { it }) { f ->
                Fila(onClick = { filtro = f }, modifier = Modifier.fillMaxWidth(), seleccionada = f == filtro) {
                    Text(f, fontSize = 18.sp, color = Colores.Texto)
                }
            }
        }
        val lista = cat.eventos.filter {
            when (filtro) {
                "Todos" -> true
                "En directo" -> it.vivo
                "En abierto" -> it.enAbierto
                "Con mis canales" -> !it.posible && !it.apagada && cat.canalesDe(it).isNotEmpty()
                else -> it.deporte == filtro
            }
        }
        // Cabeceras de día entre los eventos («Hoy», «Mañana»…)
        val filas = ArrayList<Any>()
        var ultimoDia = 0L
        for (e in lista) {
            if (ultimoDia == 0L || !mismoDia(ultimoDia, e.inicio)) { filas.add(diaTexto(e.inicio)); ultimoDia = e.inicio }
            filas.add(e)
        }
        if (filas.isEmpty()) Text("No hay eventos con este filtro.", fontSize = 18.sp, color = Colores.Apagado)
        else LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(filas, key = { if (it is Evento) "e-${it.id}" else "d-$it" }) { f ->
                if (f is Evento) FilaEvento(f, cat) { abrirEvento(contexto, cat, f) }
                else Text(f as String, fontSize = 20.sp, color = Colores.Acento, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp, start = 16.dp))
            }
        }
    }
}
