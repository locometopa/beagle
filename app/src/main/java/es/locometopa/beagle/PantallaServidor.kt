package es.locometopa.beagle

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch

private val Panel = Color(0xFF18212F)
private val Apagado = Color(0xFFA0A0A0)
private val Bien = Color(0xFF2FBF71)
private val Mal = Color(0xFFE5484D)
private val Aviso = Color(0xFFE6B422)

/** Ajustes: servidor de casa (dirección, búsqueda en la red, estado), motor, versión. */
@Composable
fun PantallaAjustes(conexion: Conexion, ajustes: Ajustes, servidorVacio: Boolean, alCambiarServidor: () -> Unit) {
    var pestana by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 24.dp, top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Fila(onClick = { pestana = 0 }, seleccionada = pestana == 0) { Text("General", fontSize = 20.sp, color = Tinta.texto) }
            Fila(onClick = { pestana = 1 }, seleccionada = pestana == 1) { Text("Listas", fontSize = 20.sp, color = Tinta.texto) }
        }
        if (pestana == 0) AjustesGenerales(conexion, ajustes, alCambiarServidor)
        else PantallaListas(conexion, servidorVacio, alCambiarServidor)
    }
}

@Composable
private fun AjustesGenerales(conexion: Conexion, ajustes: Ajustes, alCambiarServidor: () -> Unit) {
    val alcance = rememberCoroutineScope()
    var texto by remember { mutableStateOf(conexion.servidor()) }
    var estado by remember { mutableStateOf<Estado>(if (conexion.servidor().isEmpty()) Estado.SinServidor else Estado.Comprobando) }
    var copia by remember { mutableStateOf(conexion.copiaLocal()) }

    fun comprobar() {
        estado = Estado.Comprobando
        alcance.launch {
            val e = conexion.sincronizar()
            estado = e
            when (e) {
                is Estado.Conectado -> e.copia?.let { copia = it }
                is Estado.NoResponde -> e.copia?.let { copia = it }
                else -> {}
            }
        }
    }

    fun conectar() {
        texto = conexion.ponerServidor(texto)
        alCambiarServidor()
        comprobar()
    }

    // Al abrir se pregunta al servidor (la copia local ya está a la vista)
    LaunchedEffect(Unit) { if (conexion.servidor().isNotEmpty()) comprobar() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Ajustes", fontSize = 34.sp, color = MaterialTheme.colorScheme.onBackground)
        Text("Servidor de casa", fontSize = 22.sp, color = MaterialTheme.colorScheme.onBackground)
        Text(
            "Beagle coge los canales, las listas y los favoritos del servidor de casa de HomeTV (puerto 8096).",
            fontSize = 16.sp, color = Apagado,
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            CampoTexto(texto, { texto = it }, "Pulsa OK y escribe la IP (192.168.1.180)", Modifier.width(420.dp),
                teclado = KeyboardType.Uri, alAceptar = { conectar() })
            Button(onClick = { conectar() }) { Text("Conectar") }
            Button(onClick = {
                estado = Estado.Buscando
                alcance.launch {
                    val ip = conexion.buscar()
                    if (ip != null) { texto = ip; alCambiarServidor(); comprobar() }
                    else estado = Estado.NoResponde("No se ha encontrado el servidor en esta red", copia)
                }
            }) { Text("Buscar en la red") }
        }

        FilaEstado(estado)
        FilaMotor(conexion)
        FilaVpn(estado, conexion) { comprobar() }
        FilaReproductor(ajustes)
        FilaDatos(conexion)
        copia?.let { ResumenDatos(it) }
        Text("Beagle ${ajustes.version()}", fontSize = 14.sp, color = Apagado)
    }
}

@Composable
private fun FilaEstado(estado: Estado) {
    val (color, titulo, detalle) = when (estado) {
        is Estado.SinServidor -> Triple(Apagado, "Sin servidor", "Escribe la IP del servidor o pulsa «Buscar en la red».")
        is Estado.Buscando -> Triple(Aviso, "Buscando…", "Recorriendo la red local (tarda unos segundos).")
        is Estado.Comprobando -> Triple(Aviso, "Comprobando…", "")
        is Estado.Conectado -> Triple(
            Bien, "Conectado",
            listOfNotNull(
                estado.salud.motor?.let { "Motor Ace Stream $it" } ?: "Motor Ace Stream parado",
                if (estado.salud.vpn) "VPN activa" else null,
                estado.salud.apk?.let { "HomeTV $it en el servidor" },
                estado.aviso,
            ).joinToString(" · "),
        )
        is Estado.NoResponde -> Triple(Mal, "Sin conexión", estado.motivo + if (estado.copia != null) " · se usa la copia guardada en la tele" else "")
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(14.dp).height(14.dp).background(color, RoundedCornerShape(7.dp)))
        Text(titulo, fontSize = 22.sp, color = color)
        if (detalle.isNotEmpty()) Text(detalle, fontSize = 16.sp, color = Apagado)
    }
}

@Composable
private fun ResumenDatos(copia: Copia) {
    val r = copia.resumen
    Column(Modifier.background(Panel, RoundedCornerShape(12.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Lo que hay en la tele", fontSize = 18.sp, color = Apagado)
        Spacer(Modifier.height(4.dp))
        Text("${r.canales} canales" + if (r.ligero) " (sin los de listas apagadas)" else "", fontSize = 22.sp, color = MaterialTheme.colorScheme.onBackground)
        Text("${r.listas} listas, ${r.listasEncendidas} encendidas", fontSize = 18.sp, color = MaterialTheme.colorScheme.onBackground)
        Text("${r.favoritos} favoritos", fontSize = 18.sp, color = MaterialTheme.colorScheme.onBackground)
    }
}

/** Motor de Ace Stream: dónde está (servidor de casa o esta tele) y si responde. */
@Composable
private fun FilaMotor(conexion: Conexion) {
    val alcance = rememberCoroutineScope()
    var enTele by remember { mutableStateOf(conexion.motorEnTele()) }
    var resultado by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var probando by remember { mutableStateOf(false) }

    fun probar() {
        probando = true
        resultado = null
        alcance.launch {
            resultado = try { true to conexion.comprobarMotor() } catch (e: Exception) { false to (e.message ?: "No responde") }
            probando = false
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Motor de Ace Stream", fontSize = 18.sp, color = Apagado)
        Button(onClick = { enTele = !enTele; conexion.ponerMotorEnTele(enTele); resultado = null }) {
            Text(if (enTele) "En esta tele" else "En el servidor de casa")
        }
        Button(onClick = { if (!probando) probar() }) { Text(if (probando) "Probando…" else "Probar motor") }
        resultado?.let { (bien, texto) -> Text(texto, fontSize = 16.sp, color = if (bien) Bien else Mal) }
    }
}

/** Reproductor preferido: Beagle, Ace Player, AceServe, VLC o elegir cada vez; dice cuáles están instaladas. */
@Composable
private fun FilaReproductor(ajustes: Ajustes) {
    val contexto = androidx.compose.ui.platform.LocalContext.current
    var actual by remember { mutableStateOf(ajustes.reproductor) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Reproductor", fontSize = 18.sp, color = Apagado)
        Button(onClick = {
            val todos = Reproductor.values()
            actual = todos[(actual.ordinal + 1) % todos.size]
            ajustes.reproductor = actual
        }) { Text(actual.nombre) }
        val apps = listOf(Reproductor.Vlc, Reproductor.AcePlayer, Reproductor.AceServe)
            .joinToString(" · ") { it.nombre + if (Externos.instalada(contexto, it) != null) " ✓" else " ✗" }
        Text(apps, fontSize = 15.sp, color = Apagado)
    }
}

/** VPN del servidor (WARP o Proton): cómo está y cambiarla desde la tele. */
@Composable
private fun FilaVpn(estado: Estado, conexion: Conexion, recomprobar: () -> Unit) {
    val e = estado as? Estado.Conectado ?: return
    val alcance = rememberCoroutineScope()
    var mensaje by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    fun cambiar(usar: String) {
        mensaje = true to "Cambiando la VPN del servidor… tarda unos segundos"
        alcance.launch {
            try {
                conexion.cambiarVpn(usar)
                kotlinx.coroutines.delay(8000)
                recomprobar()
                mensaje = null
            } catch (x: Exception) {
                mensaje = false to (x.message ?: "No se ha podido cambiar la VPN")
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("VPN del servidor", fontSize = 18.sp, color = Apagado)
            Text(if (e.salud.vpn) "activa" + (e.salud.vpnDetalle?.let { " · $it" } ?: "") else "apagada", fontSize = 16.sp, color = if (e.salud.vpn) Bien else Apagado)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = { cambiar("warp") }) { Text("WARP") }
            Button(onClick = { cambiar("proton") }) { Text("Proton") }
            Button(onClick = { cambiar("ninguna") }) { Text("Sin VPN") }
        }
        mensaje?.let { (bien, texto) -> Text(texto, fontSize = 15.sp, color = if (bien) Aviso else Mal) }
    }
}

/** Copias de seguridad en el servidor, limpiar la caché de la app y empezar de cero en esta tele. */
@Composable
private fun FilaDatos(conexion: Conexion) {
    val contexto = androidx.compose.ui.platform.LocalContext.current
    val alcance = rememberCoroutineScope()
    var mensaje by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var seguro by remember { mutableStateOf(false) }
    Text("Datos", fontSize = 22.sp, color = MaterialTheme.colorScheme.onBackground)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = {
            mensaje = true to "Guardando la copia en el servidor…"
            alcance.launch {
                mensaje = try { true to "Copia guardada: " + conexion.copiaAhora() } catch (e: Exception) { false to (e.message ?: "No se ha podido") }
            }
        }) { Text("Copia de seguridad ahora") }
        Button(onClick = {
            alcance.launch {
                mensaje = try {
                    val c = conexion.copias()
                    true to if (c.isEmpty()) "Aún no hay copias" else "Copias (${c.size}): " + c.take(4).joinToString(" · ")
                } catch (e: Exception) { false to (e.message ?: "No se ha podido") }
            }
        }) { Text("Ver copias") }
        Button(onClick = {
            val n = try { contexto.cacheDir.deleteRecursively() } catch (e: Exception) { false }
            mensaje = true to if (n) "Caché limpia" else "No había nada que limpiar"
        }) { Text("Limpiar caché") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = {
            if (!seguro) { seguro = true; mensaje = false to "Pulsa otra vez «Empezar de cero» para borrar lo guardado en esta tele (el servidor no se toca)" }
            else { seguro = false; conexion.empezarDeCero(); mensaje = true to "Hecho: cierra y abre la app para volver a cargarlo todo del servidor" }
        }) { Text(if (seguro) "¿Seguro? Pulsa otra vez" else "Empezar de cero") }
    }
    mensaje?.let { (bien, t) -> Text(t, fontSize = 16.sp, color = if (bien) Bien else Aviso) }
}
