package es.locometopa.beagle

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import java.util.Locale

private fun tamano(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale("es", "ES"), "%.1f GB", bytes / 1073741824.0)
    bytes >= 1L shl 20 -> String.format(Locale("es", "ES"), "%.0f MB", bytes / 1048576.0)
    else -> "${bytes / 1024} KB"
}

/** Un vídeo de una carpeta del servidor, preparado para el reproductor (es un enlace directo, sin motor). */
private fun comoCanal(carpeta: Carpeta, v: VideoCarpeta): Canal = Canal(
    clave = "carpeta|${carpeta.id}|${v.sub}",
    titulo = Modelo.limpio(v.nombre),
    categoria = carpeta.nombre,
    favorito = false,
    ahora = "",
    luego = "",
    enlaces = listOf(Enlace(id = "", titulo = v.nombre, kind = "", url = v.url, estado = "", peers = 0)),
)

/** Vídeos: las carpetas del servidor y del NAS, y las grabaciones. Atrás sube un nivel. */
@Composable
fun PantallaVideos(conexion: Conexion, servidorVacio: Boolean) {
    val contexto = LocalContext.current
    val alcance = rememberCoroutineScope()
    var carpetas by remember { mutableStateOf<List<Carpeta>?>(null) }
    var abierta by remember { mutableStateOf<Carpeta?>(null) }
    var ruta by remember { mutableStateOf(listOf<String>()) }   // subcarpetas por las que se ha bajado
    var listado by remember { mutableStateOf<Listado?>(null) }
    var cargando by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun cargarCarpetas() {
        cargando = true; error = null
        alcance.launch {
            try { carpetas = conexion.carpetas() } catch (e: Exception) { error = e.message ?: "No se han podido cargar las carpetas" }
            cargando = false
        }
    }

    fun abrir(c: Carpeta, sub: List<String>) {
        abierta = c; ruta = sub; listado = null; cargando = true; error = null
        alcance.launch {
            try { listado = conexion.listarCarpeta(c.id, sub.lastOrNull() ?: "") } catch (e: Exception) { error = e.message ?: "No se ha podido abrir la carpeta" }
            cargando = false
        }
    }

    LaunchedEffect(servidorVacio) { if (!servidorVacio) cargarCarpetas() }

    // Atrás: sube una subcarpeta, y desde la raíz de una carpeta vuelve a la lista
    BackHandler(enabled = abierta != null) {
        val c = abierta ?: return@BackHandler
        if (ruta.isEmpty()) { abierta = null; listado = null; error = null } else abrir(c, ruta.dropLast(1))
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val c = abierta
        Text(if (c == null) "Vídeos" else c.nombre + if (ruta.isNotEmpty()) " › " + ruta.last().substringAfterLast('/') else "", fontSize = 34.sp, color = Tinta.texto)
        when {
            servidorVacio -> Text("Primero pon la dirección del servidor de casa en Ajustes.", fontSize = 20.sp, color = Tinta.texto)
            error != null -> {
                Text(error ?: "", fontSize = 18.sp, color = Colores.Mal)
                Button(onClick = { if (c == null) cargarCarpetas() else abrir(c, ruta) }) { Text("Reintentar") }
            }
            cargando -> Text("Cargando…", fontSize = 20.sp, color = Colores.Aviso)
            c == null -> {
                val lista = carpetas.orEmpty()
                if (lista.isEmpty()) Text("El servidor no tiene carpetas de vídeo. Se añaden desde su panel.", fontSize = 18.sp, color = Tinta.suave)
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(lista, key = { it.id }) { carpeta ->
                        Fila(onClick = { abrir(carpeta, emptyList()) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text("📁  " + carpeta.nombre, fontSize = 20.sp, color = Tinta.texto)
                                val estado = when {
                                    !carpeta.existe -> "no se encuentra: ¿está montada?"
                                    carpeta.encendido == false -> "el equipo está apagado"
                                    else -> listOf(carpeta.ruta, if (carpeta.elementos.isNotEmpty()) "${carpeta.elementos} elementos" else "").filter { it.isNotEmpty() }.joinToString(" · ")
                                }
                                if (estado.isNotEmpty()) Text(estado, fontSize = 14.sp, color = if (carpeta.existe && carpeta.encendido != false) Colores.Apagado else Colores.Aviso)
                            }
                        }
                    }
                }
            }
            else -> {
                val l = listado
                if (l == null || (l.subcarpetas.isEmpty() && l.videos.isEmpty())) Text("Esta carpeta está vacía.", fontSize = 18.sp, color = Tinta.suave)
                else {
                    val canales = l.videos.map { comoCanal(c, it) }
                    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(l.subcarpetas, key = { "d-${it.second}" }) { (nombre, sub) ->
                            Fila(onClick = { abrir(c, ruta + sub) }, modifier = Modifier.fillMaxWidth()) {
                                Text("📁  $nombre", fontSize = 20.sp, color = Tinta.texto)
                            }
                        }
                        items(l.videos.indices.toList(), key = { "v-${l.videos[it].sub}" }) { i ->
                            val v = l.videos[i]
                            Fila(onClick = { abrirReproductor(contexto, canales, i) }, modifier = Modifier.fillMaxWidth()) {
                                Text(Modelo.limpio(v.nombre), fontSize = 20.sp, color = Tinta.texto, modifier = Modifier.weight(1f))
                                Text(tamano(v.bytes), fontSize = 14.sp, color = Tinta.suave)
                            }
                        }
                    }
                }
            }
        }
    }
}
