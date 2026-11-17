package es.locometopa.beagle

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

@Composable
private fun TituloFila(texto: String) {
    Text(texto, fontSize = 22.sp, color = Colores.Texto, fontFamily = Titulos, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 8.dp))
}

/** Portada en listas compactas: en directo, a continuación, vistos y favoritos. */
/** «Sigue con…»: el último canal que viste, grande y naranja, para retomarlo con un OK. */
@Composable
private fun Sigue(c: Canal, alPulsar: () -> Unit) {
    val fuente = remember { MutableInteractionSource() }
    val foco by fuente.collectIsFocusedAsState()
    val forma = RoundedCornerShape(26.dp)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .then(if (foco) Modifier.shadow(20.dp, forma) else Modifier)
            .clip(forma)
            .background(Brush.linearGradient(listOf(Color(0xFFFCA12C), Color(0xFFE0741A))))
            .then(if (foco) Modifier.background(Color.Transparent) else Modifier)
            .clickable(interactionSource = fuente, indication = null, onClick = alPulsar)
            .padding(horizontal = 22.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Monograma(c.titulo, 64.dp)
        Column(Modifier.weight(1f).padding(start = 20.dp)) {
            Text("Sigue con ${c.titulo}", fontSize = 26.sp, color = Color(0xFF0B1220), fontFamily = Titulos, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(c.ahora.ifEmpty { c.categoria }, fontSize = 16.sp, color = Color(0xFF3A2600), maxLines = 1)
        }
        Text(
            "▶ Seguir", fontSize = 18.sp, fontWeight = FontWeight.Bold,
            color = if (foco) Color(0xFF0B1220) else Color.White,
            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(if (foco) Color.White else Color(0xFF0B1220)).padding(horizontal = 22.dp, vertical = 10.dp),
        )
    }
}

@Composable
fun ContenidoPortada(datos: Datos, cat: Catalogo, ajustes: Ajustes) {
    val contexto = LocalContext.current
    val ahora = System.currentTimeMillis()
    val conCanal = cat.eventos.filter { !it.aplazado && !it.posible && !it.apagada && cat.canalesDe(it).isNotEmpty() }
    val enDirecto = conCanal.filter { it.vivo }
    val luego = conCanal.filter { !it.vivo && it.inicio < ahora + 6 * 3600_000L }
    val porClave = cat.canales.associateBy { it.clave }
    val vistos = ajustes.vistos.mapNotNull { porClave[it] }.take(8)
    val favoritos = cat.canales.filter { it.favorito || datos.cambios < 0 }

    LazyColumn(Modifier.fillMaxWidth()) {
        vistos.firstOrNull()?.let { ultimo -> item(key = "sigue") { Sigue(ultimo) { abrirReproductor(contexto, vistos, 0) } } }
        if (enDirecto.isNotEmpty()) {
            item(key = "t-vivo") { TituloFila("En directo ahora") }
            items(enDirecto, key = { "e-${it.id}" }) { e -> FilaEvento(e, cat) { abrirEvento(contexto, cat, e) } }
        }
        if (luego.isNotEmpty()) {
            item(key = "t-luego") { TituloFila("A continuación") }
            items(luego, key = { "l-${it.id}" }) { e -> FilaEvento(e, cat) { abrirEvento(contexto, cat, e) } }
        }
        if (vistos.isNotEmpty()) {
            item(key = "t-vistos") { TituloFila("Vistos hace poco") }
            items(vistos, key = { "v-${it.clave}" }) { c -> FilaCanal(vistos, c, datos.cambios, datos.alFavorito) }
        }
        item(key = "t-fav") { TituloFila("Favoritos") }
        if (favoritos.isEmpty()) item(key = "sin-fav") {
            Text("Aún no tienes favoritos: márcalos con ★ en Canales.", fontSize = 16.sp, color = Colores.Apagado, modifier = Modifier.padding(start = 8.dp))
        } else items(favoritos, key = { "f-${it.clave}" }) { c -> FilaCanal(favoritos, c, datos.cambios, datos.alFavorito) }
    }
}
