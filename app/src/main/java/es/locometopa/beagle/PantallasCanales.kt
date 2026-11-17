package es.locometopa.beagle

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Text

/** Abre el reproductor con esta lista (para hacer zapping por ella) empezando en [indice]. */
fun abrirReproductor(contexto: Context, canales: List<Canal>, indice: Int) {
    // Si en Ajustes hay otro reproductor (VLC, Ace Player…), se abre ahí en vez de en Beagle
    canales.getOrNull(indice)?.let { if (Externos.abrirSegunAjuste(contexto, it)) return }
    Sesion.canales = canales
    Sesion.indice = indice
    canales.getOrNull(indice)?.let { Ajustes(contexto.applicationContext).verCanal(it.clave) }
    contexto.startActivity(Intent(contexto, ReproductorActivity::class.java))
}

/** Lista de canales: OK reproduce; con ▶ se pasa a la estrella y OK marca o quita el favorito. */
@Composable
fun ListaCanales(canales: List<Canal>, cambios: Int, alFavorito: (Canal) -> Unit, modifier: Modifier = Modifier) {
    if (canales.isEmpty()) {
        Text("No hay canales aquí.", fontSize = 18.sp, color = Colores.Apagado, modifier = modifier.padding(16.dp))
        return
    }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(canales, key = { it.clave }) { c -> FilaCanal(canales, c, cambios, alFavorito) }
    }
}

/** Un canal de la lista [lista]: OK lo reproduce (con zapping por esa lista); ▶ pasa a la estrella de favorito. */
@Composable
fun FilaCanal(lista: List<Canal>, c: Canal, cambios: Int, alFavorito: (Canal) -> Unit) {
    val contexto = LocalContext.current
    // «cambios» sube al marcar un favorito para que la fila se vuelva a dibujar
    val fav = if (cambios >= 0) c.favorito else false
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Fila(onClick = { abrirReproductor(contexto, lista, lista.indexOf(c)) }, modifier = Modifier.weight(1f)) {
            Box(Modifier.width(12.dp).height(12.dp).background(Color(Estilo.colorEstado(c.estado)), RoundedCornerShape(6.dp)))
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Text(c.titulo, fontSize = 20.sp, color = Colores.Texto)
                val sub = listOf(c.categoria, c.ahora).filter { it.isNotEmpty() }.joinToString(" · ")
                if (sub.isNotEmpty()) Text(sub, fontSize = 14.sp, color = Colores.Apagado)
            }
            if (c.enlaces.size > 1) Text("${c.enlaces.size} enlaces", fontSize = 14.sp, color = Colores.Apagado)
        }
        Fila(onClick = { alFavorito(c) }) {
            Text(if (fav) "★" else "☆", fontSize = 24.sp, color = if (fav) Colores.Acento else Colores.Apagado)
        }
    }
}

/** Inicio: lo que se emite ahora, lo que viene, lo último que viste y tus favoritos. */
@Composable
fun PantallaInicio(datos: Datos, ajustes: Ajustes, irAAjustes: () -> Unit) {
    val cat = datos.catalogo
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            datos.servidorVacio -> {
                Text("Todavía no has puesto la dirección del servidor de casa.", fontSize = 20.sp, color = Colores.Texto)
                Button(onClick = irAAjustes) { Text("Ir a Ajustes") }
            }
            cat == null && datos.cargando -> Text("Cargando canales…", fontSize = 20.sp, color = Colores.Aviso)
            cat == null -> {
                Text(datos.error ?: "No se han podido cargar los canales", fontSize = 20.sp, color = Colores.Mal)
                Button(onClick = datos.recargar) { Text("Reintentar") }
            }
            else -> {
                val estado = when {
                    datos.cargando -> "Actualizando…"
                    datos.error != null -> "Sin conexión con el servidor: se usan los canales guardados"
                    else -> "${cat.canales.size} canales"
                }
                Text(estado, fontSize = 16.sp, color = if (datos.error != null) Colores.Aviso else Colores.Apagado)
                ContenidoPortada(datos, cat, ajustes)
            }
        }
    }
}

@Composable
private fun Titulo(texto: String) {
    Text(texto, fontSize = 22.sp, color = Colores.Texto, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp, start = 16.dp))
}

/** Canales: categorías a la izquierda, canales a la derecha. */
@Composable
fun PantallaCanales(datos: Datos) {
    val cat = datos.catalogo
    var elegida by remember { mutableStateOf("Todos") }
    Row(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (cat == null) {
            Text(if (datos.cargando) "Cargando canales…" else datos.error ?: "Sin canales", fontSize = 20.sp, color = Colores.Apagado)
            return@Row
        }
        val categorias = listOf("Todos", "★ Favoritos") + cat.categorias
        LazyColumn(Modifier.width(260.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(categorias, key = { it }) { nombre ->
                Fila(onClick = { elegida = nombre }, modifier = Modifier.fillMaxWidth(), seleccionada = nombre == elegida) {
                    Text(nombre, fontSize = 18.sp, color = Colores.Texto)
                }
            }
        }
        val lista = when (elegida) {
            "Todos" -> cat.canales
            "★ Favoritos" -> cat.canales.filter { it.favorito }
            else -> cat.canales.filter { it.categoria == elegida }
        }
        ListaCanales(lista, datos.cambios, datos.alFavorito, Modifier.weight(1f).fillMaxHeight())
    }
}

/** Buscar: se escribe con el teclado de la tele (pulsa OK en el campo) y salen los canales con esas palabras. */
@Composable
fun PantallaBuscar(datos: Datos) {
    var texto by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Buscar", fontSize = 34.sp, color = Colores.Texto)
        CampoTexto(texto, { texto = it }, "Pulsa OK y escribe el nombre de un canal", Modifier.fillMaxWidth())
        val cat = datos.catalogo
        if (cat != null && texto.isNotBlank()) {
            ListaCanales(Modelo.buscar(cat.canales, texto), datos.cambios, datos.alFavorito, Modifier.fillMaxSize())
        }
    }
}
