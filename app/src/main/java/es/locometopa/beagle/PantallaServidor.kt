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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.launch

private val Panel = Color(0xFF2D2D2D)
private val Apagado = Color(0xFFA0A0A0)
private val Bien = Color(0xFF2FBF71)
private val Mal = Color(0xFFE5484D)
private val Aviso = Color(0xFFE6B422)

/** Ajuste «Servidor de casa»: dirección, búsqueda en la red, estado y lo que hay sincronizado. */
@Composable
fun PantallaServidor(conexion: Conexion) {
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

    // Al abrir: se enseña ya la copia local y se pregunta al servidor
    LaunchedEffect(Unit) { if (conexion.servidor().isNotEmpty()) comprobar() }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 64.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Servidor de casa", fontSize = 34.sp, color = MaterialTheme.colorScheme.onBackground)
        Text(
            "Beagle coge los canales, las listas y los favoritos del servidor de casa de HomeTV (puerto 8096).",
            fontSize = 16.sp, color = Apagado,
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                Modifier.width(380.dp).height(56.dp).background(Panel, RoundedCornerShape(10.dp))
                    .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                BasicTextField(
                    value = texto,
                    onValueChange = { texto = it },
                    singleLine = true,
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onBackground, fontSize = 20.sp),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { campo ->
                        if (texto.isEmpty()) Text("192.168.1.180", fontSize = 20.sp, color = Apagado)
                        campo()
                    },
                )
            }
            Button(onClick = { texto = conexion.ponerServidor(texto); comprobar() }) { Text("Conectar") }
            Button(onClick = {
                estado = Estado.Buscando
                alcance.launch {
                    val ip = conexion.buscar()
                    if (ip != null) { texto = ip; comprobar() }
                    else estado = Estado.NoResponde("No se ha encontrado el servidor en esta red", copia)
                }
            }) { Text("Buscar en la red") }
        }

        FilaEstado(estado)
        copia?.let { Datos(it) }
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
private fun Datos(copia: Copia) {
    val r = copia.resumen
    Column(Modifier.background(Panel, RoundedCornerShape(12.dp)).padding(24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Lo que hay en la tele", fontSize = 18.sp, color = Apagado)
        Spacer(Modifier.height(4.dp))
        Text("${r.canales} canales" + if (r.ligero) " (sin los de listas apagadas)" else "", fontSize = 22.sp, color = MaterialTheme.colorScheme.onBackground)
        Text("${r.listas} listas, ${r.listasEncendidas} encendidas", fontSize = 18.sp, color = MaterialTheme.colorScheme.onBackground)
        Text("${r.favoritos} favoritos", fontSize = 18.sp, color = MaterialTheme.colorScheme.onBackground)
    }
}
