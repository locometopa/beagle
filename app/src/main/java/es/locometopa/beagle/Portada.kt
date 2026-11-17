package es.locometopa.beagle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
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
    Text(texto.uppercase(), fontSize = 15.sp, color = Colores.Acento, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp, start = 6.dp))
}

/** Portada en listas compactas: en directo, a continuación, vistos y favoritos. */
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

    LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
