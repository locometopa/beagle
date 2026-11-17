package es.locometopa.beagle

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val HORA = SimpleDateFormat("HH:mm", Locale("es", "ES"))

@Composable
private fun TituloFila(texto: String) {
    Text(texto.uppercase(), fontSize = 15.sp, color = Colores.Acento, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp, start = 4.dp))
}

/** Destacado de arriba: el partido en directo (o el próximo) en tu mejor canal. */
@Composable
private fun Destacado(e: Evento, cat: Catalogo) {
    val contexto = LocalContext.current
    val canales = cat.canalesDe(e)
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Colores.Panel).padding(horizontal = 24.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (e.vivo) "EN DIRECTO" else "A LAS ${HORA.format(Date(e.inicio))}", fontSize = 14.sp, color = if (e.vivo) Colores.Mal else Colores.Acento, fontWeight = FontWeight.Bold)
            val sub = listOf(e.deporte, e.competicion).filter { it.isNotEmpty() && it != e.titulo }.joinToString(" · ")
            if (sub.isNotEmpty()) Text("   $sub", fontSize = 15.sp, color = Colores.Apagado, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(e.titulo, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Colores.Texto, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (canales.isNotEmpty()) Text("En ${canales[0].titulo}" + if (canales.size > 1) " y ${canales.size - 1} más" else "", fontSize = 16.sp, color = Colores.Apagado)
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Boton("▶  Ver ahora", { abrirEvento(contexto, cat, e) }, principal = true)
        }
    }
}

@Composable
private fun TarjetaEvento(e: Evento, cat: Catalogo) {
    val contexto = LocalContext.current
    val mejor = cat.canalesDe(e).firstOrNull()
    Tarjeta({ abrirEvento(contexto, cat, e) }, 250.dp, 104.dp) {
        Text(if (e.vivo) "EN DIRECTO" else HORA.format(Date(e.inicio)), fontSize = 13.sp, color = if (e.vivo) Colores.Mal else Colores.Acento, fontWeight = FontWeight.Bold)
        Text(e.titulo, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Colores.Texto, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(mejor?.titulo ?: "", fontSize = 13.sp, color = Colores.Apagado, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TarjetaCanal(lista: List<Canal>, c: Canal) {
    val contexto = LocalContext.current
    val orden = c.estado
    val color = Color(Estilo.colorEstado(orden))
    Tarjeta({ abrirReproductor(contexto, lista, lista.indexOf(c)) }, 210.dp, 72.dp) {
        Text(c.titulo, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Colores.Texto, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(color))
            Text("  " + c.categoria.ifEmpty { "Sin categoría" }, fontSize = 13.sp, color = Colores.Apagado, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Inicio como el de HomeTV: el destacado arriba y debajo filas que se recorren de lado: en directo, a continuación, vistos y favoritos. */
@Composable
fun ContenidoPortada(datos: Datos, cat: Catalogo, ajustes: Ajustes) {
    val ahora = System.currentTimeMillis()
    val conCanal = cat.eventos.filter { !it.aplazado && !it.posible && !it.apagada && cat.canalesDe(it).isNotEmpty() }
    val enDirecto = conCanal.filter { it.vivo }
    val luego = conCanal.filter { !it.vivo && it.inicio < ahora + 6 * 3600_000L }
    val porClave = cat.canales.associateBy { it.clave }
    val vistos = ajustes.vistos.mapNotNull { porClave[it] }.take(10)
    val favoritos = cat.canales.filter { it.favorito || datos.cambios < 0 }
    val destacado = enDirecto.firstOrNull() ?: luego.firstOrNull()

    LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (destacado != null) item(key = "hero") { Destacado(destacado, cat) }
        if (enDirecto.isNotEmpty()) {
            item(key = "t-vivo") { TituloFila("En directo ahora") }
            item(key = "r-vivo") { LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(enDirecto, key = { "e-${it.id}" }) { TarjetaEvento(it, cat) } } }
        }
        if (luego.isNotEmpty()) {
            item(key = "t-luego") { TituloFila("A continuación") }
            item(key = "r-luego") { LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(luego, key = { "l-${it.id}" }) { TarjetaEvento(it, cat) } } }
        }
        if (vistos.isNotEmpty()) {
            item(key = "t-vistos") { TituloFila("Vistos hace poco") }
            item(key = "r-vistos") { LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(vistos, key = { "v-${it.clave}" }) { TarjetaCanal(vistos, it) } } }
        }
        item(key = "t-fav") { TituloFila("Favoritos") }
        if (favoritos.isEmpty()) item(key = "sin-fav") {
            Text("Aún no tienes favoritos: márcalos con ★ en Canales.", fontSize = 16.sp, color = Colores.Apagado, modifier = Modifier.padding(start = 4.dp))
        } else item(key = "r-fav") { LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(favoritos, key = { "f-${it.clave}" }) { TarjetaCanal(favoritos, it) } } }
    }
}
