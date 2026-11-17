package es.locometopa.beagle

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val HORA = SimpleDateFormat("HH:mm", Locale("es", "ES"))

/** Tarjeta de la portada: se agranda y se marca en blanco al tener el foco. */
@Composable
fun Tarjeta(
    onClick: () -> Unit,
    ancho: Dp,
    alto: Dp,
    modifier: Modifier = Modifier,
    fondo: Brush = Brush.linearGradient(listOf(Colores.Panel, Colores.Panel)),
    contenido: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val fuente = remember { MutableInteractionSource() }
    val foco by fuente.collectIsFocusedAsState()
    val escala by animateFloatAsState(if (foco) 1.06f else 1f, label = "escala")
    val forma = RoundedCornerShape(18.dp)
    Column(
        modifier
            .width(ancho).height(alto)
            .scale(escala)
            .clip(forma)
            .background(fondo)
            .border(3.dp, if (foco) Color.White else Color.Transparent, forma)
            .clickable(interactionSource = fuente, indication = null, onClick = onClick)
            .padding(18.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        content = contenido,
    )
}

/** El cartel grande de arriba: el partido en directo (o el próximo) con su mejor canal. */
@Composable
fun Cartel(e: Evento, cat: Catalogo, onClick: () -> Unit) {
    val mios = cat.canalesDe(e)
    val degradado = Brush.linearGradient(listOf(Color(0xFF3B4A1A), Color(0xFF2D2D2D)))
    Tarjeta(onClick, ancho = 760.dp, alto = 210.dp, fondo = degradado) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (etiqueta, color) = if (e.vivo) "EN DIRECTO" to Colores.Bien else "A LAS ${HORA.format(Date(e.inicio))}" to Colores.Acento
            Text(etiqueta, fontSize = 16.sp, color = color, fontWeight = FontWeight.Bold)
            val sub = listOf(e.deporte, e.competicion).filter { it.isNotEmpty() && it != e.titulo }.joinToString(" · ")
            if (sub.isNotEmpty()) Text("   $sub", fontSize = 16.sp, color = Colores.Apagado)
        }
        Text(e.titulo, fontSize = 34.sp, color = Colores.Texto, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            if (mios.isEmpty()) "sin canal" else "▶  " + mios[0].titulo + if (mios.size > 1) "  +${mios.size - 1}" else "",
            fontSize = 20.sp, color = Colores.Acento, fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun TarjetaEvento(e: Evento, cat: Catalogo, onClick: () -> Unit) {
    val mios = cat.canalesDe(e)
    Tarjeta(onClick, ancho = 300.dp, alto = 130.dp) {
        Text(if (e.vivo) "EN DIRECTO" else HORA.format(Date(e.inicio)), fontSize = 15.sp, color = if (e.vivo) Colores.Bien else Colores.Acento, fontWeight = FontWeight.Bold)
        Text(e.titulo, fontSize = 19.sp, color = Colores.Texto, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(mios.firstOrNull()?.titulo ?: "", fontSize = 14.sp, color = Colores.Apagado, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TarjetaCanal(c: Canal, onClick: () -> Unit) {
    Tarjeta(onClick, ancho = 190.dp, alto = 100.dp) {
        Text(c.categoria, fontSize = 13.sp, color = Colores.Apagado, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(c.titulo, fontSize = 19.sp, color = Colores.Texto, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Una fila horizontal de tarjetas con su título. */
@Composable
fun <T> FilaTarjetas(titulo: String, elementos: List<T>, tarjeta: @Composable (Int, T) -> Unit) {
    if (elementos.isEmpty()) return
    Text(titulo, fontSize = 22.sp, color = Colores.Texto, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp, bottom = 8.dp, start = 4.dp))
    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 10.dp, horizontal = 8.dp)) {
        items(elementos.size) { i -> tarjeta(i, elementos[i]) }
    }
}

@Composable
fun ContenidoPortada(datos: Datos, cat: Catalogo, ajustes: Ajustes) {
    val contexto = androidx.compose.ui.platform.LocalContext.current
    val ahora = System.currentTimeMillis()
    val conCanal = cat.eventos.filter { !it.aplazado && !it.posible && !it.apagada && cat.canalesDe(it).isNotEmpty() }
    val enDirecto = conCanal.filter { it.vivo }
    val luego = conCanal.filter { !it.vivo && it.inicio < ahora + 6 * 3600_000L }
    val destacado = enDirecto.firstOrNull() ?: luego.firstOrNull()
    val resto = enDirecto.filter { it !== destacado }
    val proximos = luego.filter { it !== destacado }
    val porClave = cat.canales.associateBy { it.clave }
    val vistos = ajustes.vistos.mapNotNull { porClave[it] }.take(10)
    val favoritos = cat.canales.filter { it.favorito }

    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth()) {
        if (destacado != null) item(key = "cartel") {
            Spacer(Modifier.height(8.dp))
            Cartel(destacado, cat) { abrirEvento(contexto, cat, destacado) }
        }
        item(key = "vivo") { FilaTarjetas("En directo ahora", resto) { _, e -> TarjetaEvento(e, cat) { abrirEvento(contexto, cat, e) } } }
        item(key = "luego") { FilaTarjetas("A continuación", proximos) { _, e -> TarjetaEvento(e, cat) { abrirEvento(contexto, cat, e) } } }
        item(key = "vistos") { FilaTarjetas("Vistos hace poco", vistos) { i, c -> TarjetaCanal(c) { abrirReproductor(contexto, vistos, i) } } }
        item(key = "fav") {
            if (favoritos.isEmpty()) Text("Aún no tienes favoritos: márcalos con ★ en Canales.", fontSize = 16.sp, color = Colores.Apagado, modifier = Modifier.padding(top = 16.dp, start = 4.dp))
            else FilaTarjetas("Favoritos", favoritos) { i, c -> TarjetaCanal(c) { abrirReproductor(contexto, favoritos, i) } }
        }
    }
}
