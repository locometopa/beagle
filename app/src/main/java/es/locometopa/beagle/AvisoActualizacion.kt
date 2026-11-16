package es.locometopa.beagle

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface Fase {
    data object Nada : Fase
    class Hay(val version: String) : Fase
    class Descargando(val version: String, val porcentaje: Int) : Fase
    class Permiso(val version: String) : Fase
    class Fallo(val version: String?, val motivo: String) : Fase
}

/**
 * Al abrir Beagle mira si hay una versión nueva y, si la hay, ofrece actualizar (como HomeTV).
 * Tras «Actualizar ahora» se baja la APK y se abre el instalador de Android: ahí se confirma con el mando.
 */
@Composable
fun AvisoActualizacion(ajustes: Ajustes, modifier: Modifier = Modifier) {
    val contexto = LocalContext.current
    val alcance = rememberCoroutineScope()
    var fase by remember { mutableStateOf<Fase>(Fase.Nada) }
    var ocultado by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        // Sin conexión o sin version.txt no se avisa de nada: simplemente no hay actualización que ofrecer
        val nueva = try {
            withContext(Dispatchers.IO) { Actualizador.buscar(ajustes.urlActualizaciones, ajustes.version()) }
        } catch (e: Exception) {
            null
        }
        if (nueva != null) fase = Fase.Hay(nueva.version)
    }

    fun actualizar(version: String) {
        fase = Fase.Descargando(version, -1)
        alcance.launch {
            fase = try {
                val apk = withContext(Dispatchers.IO) {
                    Actualizador.descargar(contexto, ajustes.urlActualizaciones) { p -> fase = Fase.Descargando(version, p) }
                }
                when (val r = Actualizador.instalar(contexto, apk)) {
                    null -> Fase.Hay(version)
                    Actualizador.PERMISO -> Fase.Permiso(version)
                    else -> Fase.Fallo(version, r)
                }
            } catch (e: Exception) {
                Fase.Fallo(version, e.message ?: "No se ha podido descargar")
            }
        }
    }

    val f = fase
    val version = when (f) {
        is Fase.Hay -> f.version
        is Fase.Descargando -> f.version
        is Fase.Permiso -> f.version
        is Fase.Fallo -> f.version
        Fase.Nada -> null
    }
    if (version == null || version == ocultado) return

    Box(modifier.fillMaxWidth().padding(32.dp).background(Color(0xFF2D2D2D), RoundedCornerShape(14.dp)).padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            val texto = when (f) {
                is Fase.Hay -> "Hay una versión nueva de Beagle: $version (tienes la ${ajustes.version()})"
                is Fase.Descargando -> "Descargando la versión $version" + if (f.porcentaje >= 0) "… ${f.porcentaje} %" else "…"
                is Fase.Permiso -> "Activa «Instalar apps desconocidas» para Beagle en los ajustes de la tele y vuelve a pulsar Actualizar"
                is Fase.Fallo -> "No se ha podido actualizar: ${f.motivo}"
                Fase.Nada -> ""
            }
            Text(texto, fontSize = 18.sp, color = MaterialTheme.colorScheme.onBackground)
            if (f !is Fase.Descargando) {
                Button(onClick = { actualizar(version) }) { Text("Actualizar ahora") }
                Button(onClick = { ocultado = version }) { Text("Más tarde") }
            }
        }
    }
}
