package es.locometopa.beagle

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import kotlinx.coroutines.launch

/**
 * Listas: encender y apagar (OK), renombrar (▶ y OK) y añadir una nueva desde una dirección web.
 * Los cambios van al servidor y llegan solos al resto de teles.
 */
@Composable
fun PantallaListas(conexion: Conexion, servidorVacio: Boolean, alCambiar: () -> Unit) {
    val alcance = rememberCoroutineScope()
    var listas by remember { mutableStateOf(conexion.listas()) }
    var mensaje by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var ocupado by remember { mutableStateOf(false) }
    var renombrando by remember { mutableStateOf<String?>(null) }
    var nombreNuevo by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var nombreLista by remember { mutableStateOf("") }

    // Al abrir se pone al día la copia (por si otra tele cambió algo) y se leen las listas
    LaunchedEffect(servidorVacio) {
        if (!servidorVacio) { conexion.sincronizar(); listas = conexion.listas() }
    }

    fun trabajar(texto: String, tarea: suspend () -> Unit) {
        if (ocupado) return
        ocupado = true
        mensaje = true to texto
        alcance.launch {
            try {
                tarea()
                listas = conexion.listas()
                alCambiar()
            } catch (e: Exception) {
                mensaje = false to (e.message ?: "No se ha podido hacer el cambio")
            }
            ocupado = false
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Listas", fontSize = 28.sp, color = Colores.Texto, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        if (servidorVacio) {
            Text("Primero pon la dirección del servidor de casa en Ajustes.", fontSize = 20.sp, color = Colores.Texto)
            return@Column
        }
        mensaje?.let { (bien, t) -> Text(t, fontSize = 16.sp, color = if (bien) Colores.Aviso else Colores.Mal) }

        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item(key = "nueva") {
                Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Añadir una lista", fontSize = 22.sp, color = Colores.Texto)
                    Text("La baja el servidor: vale M3U, JSON de AceList, Xtream o MylinkPaste.", fontSize = 14.sp, color = Colores.Apagado)
                    CampoTexto(url, { url = it }, "Pulsa OK y escribe la dirección (http://…)", Modifier.fillMaxWidth(), KeyboardType.Uri)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        CampoTexto(nombreLista, { nombreLista = it }, "Nombre (opcional)", Modifier.weight(1f))
                        Button(onClick = {
                            val direccion = url.trim()
                            if (direccion.isEmpty()) mensaje = false to "Escribe la dirección de la lista"
                            else trabajar("Descargando la lista en el servidor… puede tardar un minuto") {
                                val r = conexion.anadirLista(nombreLista, direccion)
                                url = ""; nombreLista = ""
                                mensaje = true to "«${r.nombre}» añadida: ${r.creados} canales nuevos"
                            }
                        }) { Text("Añadir") }
                    }
                }
            }
            if (listas.isEmpty()) item(key = "vacio") {
                Text("Todavía no hay listas. Añade una arriba.", fontSize = 18.sp, color = Colores.Apagado)
            }
            items(listas, key = { "l-${it.id}" }) { l ->
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Fila(onClick = {
                            trabajar(if (l.encendida) "Apagando «${l.nombre}»…" else "Encendiendo «${l.nombre}»…") {
                                conexion.modificarLista(l.id) { it.put("enabled", !l.encendida) }
                                mensaje = null
                            }
                        }, modifier = Modifier.weight(1f)) {
                            Text(if (l.encendida) "●" else "○", fontSize = 22.sp, color = if (l.encendida) Colores.Bien else Colores.Apagado)
                            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                                Text(l.nombre, fontSize = 20.sp, color = Colores.Texto)
                                Text((if (l.encendida) "encendida" else "apagada") + " · ${l.canales} canales" + if (l.deSerie) " · de serie" else "", fontSize = 14.sp, color = Colores.Apagado)
                            }
                        }
                        Fila(onClick = { renombrando = l.id; nombreNuevo = l.nombre }) { Text("✎", fontSize = 22.sp, color = Colores.Apagado) }
                    }
                    if (renombrando == l.id) {
                        Row(Modifier.padding(start = 16.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            CampoTexto(nombreNuevo, { nombreNuevo = it }, "Nuevo nombre", Modifier.weight(1f))
                            Button(onClick = {
                                val nuevo = nombreNuevo.trim()
                                renombrando = null
                                if (nuevo.isNotEmpty() && nuevo != l.nombre) trabajar("Renombrando…") {
                                    conexion.modificarLista(l.id) { it.put("name", nuevo) }
                                    mensaje = null
                                }
                            }) { Text("Guardar") }
                        }
                    }
                }
            }
        }
    }
}
