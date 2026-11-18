package es.locometopa.beagle

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
fun AvisoActualizacion(ajustes: Ajustes, repetir: Int = 0, modifier: Modifier = Modifier) {
    val contexto = LocalContext.current
    val alcance = rememberCoroutineScope()
    var fase by remember { mutableStateOf<Fase>(Fase.Nada) }
    var ocultado by remember { mutableStateOf<String?>(null) }
    var origen by remember { mutableStateOf(ajustes.urlActualizaciones) }

    // Se mira al abrir, y otra vez cada vez que el panel del servidor pide «Actualizar ya» (sube [repetir])
    LaunchedEffect(repetir) {
        if (repetir > 0) ocultado = null
        // Sin conexión o sin version.txt no se avisa de nada: simplemente no hay actualización que ofrecer
        var nueva: VersionNueva? = null
        for (o in ajustes.origenesActualizacion()) {
            try {
                nueva = withContext(Dispatchers.IO) { Actualizador.buscar(o, ajustes.version()) }
                origen = o
                break // este origen ha respondido: es el que vale (haya o no versión nueva)
            } catch (e: Exception) { /* se prueba el siguiente */ }
        }
        if (nueva != null) fase = Fase.Hay(nueva.version)
    }

    fun actualizar(version: String) {
        fase = Fase.Descargando(version, -1)
        alcance.launch {
            fase = try {
                val apk = withContext(Dispatchers.IO) {
                    Actualizador.descargar(contexto, origen) { p -> fase = Fase.Descargando(version, p) }
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

    val pedirFoco = remember { FocusRequester() }
    // Al salir el aviso (o cambiar de paso) el foco va solo al primer botón, para poder pulsarlo con el mando
    LaunchedEffect(f::class) {
        if (f !is Fase.Descargando) { try { pedirFoco.requestFocus() } catch (e: Exception) { /* aún no está colocado */ } }
    }
    // Atrás = «Más tarde» (no mientras descarga)
    BackHandler(enabled = f !is Fase.Descargando) { ocultado = version }

    val texto = when (f) {
        is Fase.Hay -> "Tienes la ${ajustes.version()} y ya está la $version."
        is Fase.Descargando -> "Descargando la versión $version" + if (f.porcentaje >= 0) "… ${f.porcentaje} %" else "…"
        is Fase.Permiso -> "Activa «Instalar apps desconocidas» para Beagle en los ajustes de la tele y vuelve a pulsar Actualizar ahora."
        is Fase.Fallo -> "No se ha podido actualizar: ${f.motivo}"
        Fase.Nada -> ""
    }
    // Ventana centrada sobre un fondo oscuro, como un diálogo
    Box(modifier.background(Color(0xB3000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(640.dp).background(Color(0xFF19212D), RoundedCornerShape(16.dp)).padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Hay una versión nueva de Beagle", fontSize = 26.sp, color = MaterialTheme.colorScheme.onBackground)
            Text(texto, fontSize = 18.sp, color = Color(0xFFA3AAB5))
            if (f !is Fase.Descargando) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = { actualizar(version) }, modifier = Modifier.focusRequester(pedirFoco)) { Text("Actualizar ahora") }
                    Button(onClick = { ocultado = version }) { Text("Más tarde") }
                }
            }
        }
    }
}
