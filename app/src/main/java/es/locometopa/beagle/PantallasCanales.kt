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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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

/** Estado del mejor enlace del canal, con las mismas palabras que HomeTV: En abierto · Vivo · N · Sin comprobar · Caído. */
private fun estadoTexto(c: Canal): Pair<String, Int> {
    val e = c.enlaces.firstOrNull() ?: return "Sin enlaces" to Estilo.colorEstado(2)
    return when {
        e.directo -> "En abierto" to Estilo.colorEstado(0)
        e.estado == "alive" -> ("Vivo" + if (e.peers > 0) " · ${e.peers}" else "") to Estilo.colorEstado(0)
        e.estado.isEmpty() -> "Sin comprobar" to Estilo.colorEstado(1)
        else -> "Caído" to Estilo.colorEstado(2)
    }
}

/** Opciones del canal (el ⋮ de HomeTV): reproducir, favorito y ver sus enlaces. */
private fun opcionesCanal(contexto: android.content.Context, lista: List<Canal>, c: Canal, alFavorito: (Canal) -> Unit) {
    val textos = arrayOf("▶  Reproducir", if (c.favorito) "☆  Quitar de favoritos" else "★  Añadir a favoritos", "☰  Ver los enlaces (${c.enlaces.size})")
    android.app.AlertDialog.Builder(contexto, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        .setTitle(c.titulo)
        .setItems(textos) { _, cual ->
            when (cual) {
                0 -> abrirReproductor(contexto, lista, lista.indexOf(c))
                1 -> alFavorito(c)
                else -> {
                    val enl = c.enlaces.mapIndexed { i, e ->
                        val st = when {
                            e.directo -> "en abierto"
                            e.estado == "alive" -> "funcionaba · ${e.peers} peers"
                            e.estado.isEmpty() -> "sin comprobar"
                            else -> "caído la última vez"
                        }
                        "Enlace ${i + 1} · $st"
                    }.toTypedArray()
                    android.app.AlertDialog.Builder(contexto, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Enlaces de ${c.titulo}").setItems(enl, null).setNegativeButton("Cerrar", null).show()
                }
            }
        }
        .setNegativeButton("Cerrar", null).show()
}

/** Una fila de canal como la de HomeTV: punto, nombre y categoría, estado a la derecha y ⋮ para las opciones. */
@Composable
fun FilaCanal(lista: List<Canal>, c: Canal, cambios: Int, alFavorito: (Canal) -> Unit) {
    val contexto = LocalContext.current
    // «cambios» sube al marcar un favorito para que la fila se vuelva a dibujar
    val fav = if (cambios >= 0) c.favorito else false
    val (estado, colorEstado) = estadoTexto(c)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Fila(onClick = { abrirReproductor(contexto, lista, lista.indexOf(c)) }, modifier = Modifier.weight(1f)) {
            Box(Modifier.width(12.dp).height(12.dp).background(Color(colorEstado), RoundedCornerShape(6.dp)))
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (fav) Text("★ ", fontSize = 19.sp, color = Colores.Acento)
                    Text(c.titulo, fontSize = 20.sp, color = if (c.estado == 2) Colores.Apagado else Colores.Texto, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (c.enlaces.size > 1) Text("  ${c.enlaces.size} enlaces", fontSize = 13.sp, color = Colores.Apagado)
                }
                Text(c.categoria.ifEmpty { "Sin categoría" }, fontSize = 14.sp, color = Colores.Apagado, maxLines = 1)
            }
            if (c.ahora.isNotEmpty()) Text(c.ahora, fontSize = 14.sp, color = Colores.Apagado, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(220.dp).padding(end = 12.dp))
            Text(estado, fontSize = 16.sp, color = Color(colorEstado), fontWeight = FontWeight.SemiBold, modifier = Modifier.width(130.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
        Fila(onClick = { opcionesCanal(contexto, lista, c, alFavorito) }) {
            Text("⋮", fontSize = 24.sp, color = Colores.Apagado)
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


/** Canales en filas: arriba las categorías, debajo los filtros y el orden, y luego la lista a todo el ancho. */
@Composable
fun PantallaCanales(datos: Datos) {
    val cat = datos.catalogo
    var elegida by remember { mutableStateOf("Todos") }
    var filtro by remember { mutableStateOf(0) }
    var orden by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (cat == null) {
            Text(if (datos.cargando) "Cargando canales…" else datos.error ?: "Sin canales", fontSize = 20.sp, color = Colores.Apagado)
            return@Column
        }
        val categorias = listOf("Todos", "★ Favoritos") + cat.categorias
        val directo = cat.eventos.filter { it.vivo && !it.aplazado && cat.canalesDe(it).isNotEmpty() }.take(8)
        val contexto = LocalContext.current
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Canales", fontSize = 28.sp, color = Colores.Texto, fontWeight = FontWeight.SemiBold)
            Text("  ${cat.canales.size}", fontSize = 18.sp, color = Colores.Apagado)
        }
        if (directo.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { Text("EN DIRECTO", fontSize = 14.sp, color = Colores.Acento, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 4.dp, top = 12.dp)) }
                items(directo, key = { "d-${it.id}" }) { e ->
                    Fila(onClick = { abrirEvento(contexto, cat, e) }) {
                        Text(e.titulo, fontSize = 15.sp, color = Colores.Texto, maxLines = 1)
                    }
                }
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(categorias, key = { it }) { nombre ->
                Chip(nombre, nombre == elegida, { elegida = nombre })
            }
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val nombres = listOf("Todos", "Vivos", "Sin caídos", "Caídos")
            items(nombres.size) { i ->
                Chip(nombres[i], filtro == i, { filtro = i }, tam = 15)
            }
            item { Chip(if (orden == 0) "Orden: mejores primero ▾" else "Orden: A-Z ▾", false, { orden = 1 - orden }, tam = 15) }
        }
        val lista = when (elegida) {
            "Todos" -> cat.canales
            "★ Favoritos" -> cat.canales.filter { it.favorito }
            else -> cat.canales.filter { it.categoria == elegida }
        }
        val filtrada = when (filtro) {
            1 -> lista.filter { it.estado == 0 }
            2 -> lista.filter { it.estado < 2 }
            3 -> lista.filter { it.estado == 2 }
            else -> lista
        }.let { l -> if (orden == 1) l.sortedBy { it.buscable } else l.sortedBy { it.estado } }
        ListaCanales(filtrada, datos.cambios, datos.alFavorito, Modifier.weight(1f).fillMaxWidth())
    }
}

/** Buscar: canales, eventos de la agenda y canales de listas apagadas con esas palabras. */
@Composable
fun PantallaBuscar(datos: Datos, conexion: Conexion) {
    var texto by remember { mutableStateOf("") }
    val contexto = LocalContext.current
    var apagados by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(texto) {
        apagados = emptyList()
        if (texto.trim().length < 3 || datos.servidorVacio) return@LaunchedEffect
        kotlinx.coroutines.delay(700)
        apagados = try { conexion.buscarApagados(texto.trim()) } catch (e: Exception) { emptyList() }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CampoTexto(texto, { texto = it }, "Buscar: pulsa OK y escribe un canal o un partido", Modifier.fillMaxWidth())
        val cat = datos.catalogo
        if (cat != null && texto.isNotBlank()) {
            val canales = Modelo.buscar(cat.canales, texto)
            val q = Modelo.sinTildes(texto)
            val eventos = cat.eventos.filter { !it.aplazado && Modelo.sinTildes(it.titulo + " " + it.competicion).contains(q) }.take(12)
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (eventos.isNotEmpty()) {
                    item(key = "t-ev") { Text("Agenda", fontSize = 20.sp, color = Colores.Acento, modifier = Modifier.padding(start = 8.dp, top = 4.dp)) }
                    items(eventos, key = { "ev-${it.id}" }) { e -> FilaEvento(e, cat) { abrirEvento(contexto, cat, e) } }
                }
                if (canales.isNotEmpty()) {
                    item(key = "t-ca") { Text("Canales", fontSize = 20.sp, color = Colores.Acento, modifier = Modifier.padding(start = 8.dp, top = 8.dp)) }
                    items(canales, key = { "ca-${it.clave}" }) { c -> FilaCanal(canales, c, datos.cambios, datos.alFavorito) }
                }
                if (apagados.isNotEmpty()) {
                    item(key = "t-ap") { Text("En listas apagadas (actívalas en Ajustes › Listas)", fontSize = 20.sp, color = Colores.Aviso, modifier = Modifier.padding(start = 8.dp, top = 8.dp)) }
                    items(apagados.size, key = { "ap-$it" }) { i ->
                        Column(Modifier.padding(start = 16.dp, top = 2.dp)) {
                            Text(apagados[i].first, fontSize = 18.sp, color = Colores.Texto)
                            Text(apagados[i].second, fontSize = 13.sp, color = Colores.Apagado)
                        }
                    }
                }
            }
        }
    }
}
