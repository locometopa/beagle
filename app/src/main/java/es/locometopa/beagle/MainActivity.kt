package es.locometopa.beagle

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalTextStyle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Beagle, cliente nativo del servidor de casa de HomeTV. Las pantallas se van añadiendo según CHECKLIST.md. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ajustes = Ajustes(applicationContext)
        val conexion = Conexion(ajustes, CopiaLocal(applicationContext))
        setContent { BeagleApp(conexion, ajustes) }
    }
}

private enum class Seccion(val titulo: String) {
    Inicio("Inicio"), Canales("Canales"), Agenda("Agenda"), Videos("Vídeos"), Buscar("Buscar"), Ajustes("Ajustes")
}

/** Lo que las pantallas de canales necesitan saber: los canales, si se están cargando, y cómo marcar un favorito. */
class Datos(
    val catalogo: Catalogo?,
    val cargando: Boolean,
    val error: String?,
    val servidorVacio: Boolean,
    val cambios: Int,
    val recargar: () -> Unit,
    val alFavorito: (Canal) -> Unit,
)

@Composable
fun BeagleApp(conexion: Conexion, ajustes: Ajustes) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Colores.Acento, background = Colores.Fondo, onBackground = Colores.Texto)) {
        CompositionLocalProvider(LocalTextStyle provides LocalTextStyle.current.merge(TextStyle(fontFamily = Outfit))) {
        val alcance = rememberCoroutineScope()
        var seccion by remember { mutableStateOf(Seccion.entries.firstOrNull { it.name == ajustes.ultimaSeccion } ?: Seccion.Inicio) }
        var catalogo by remember { mutableStateOf(conexion.catalogoLocal()) }
        var cargando by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var servidorVacio by remember { mutableStateOf(conexion.servidor().isEmpty()) }
        var cambios by remember { mutableStateOf(0) }

        fun recargar() {
            servidorVacio = conexion.servidor().isEmpty()
            if (servidorVacio || cargando) return
            cargando = true
            error = null
            alcance.launch {
                try {
                    catalogo = conexion.cargarCatalogo()
                } catch (e: Exception) {
                    error = e.message ?: "No se han podido cargar los canales"
                }
                cargando = false
            }
        }

        fun favorito(c: Canal) {
            val poner = !c.favorito
            c.favorito = poner
            cambios++
            alcance.launch {
                try {
                    conexion.favorito(c, poner)
                } catch (e: Exception) {
                    // El servidor no lo ha guardado: se deshace en pantalla
                    c.favorito = !poner
                    cambios++
                    error = "No se ha podido guardar el favorito: ${e.message ?: "sin conexión"}"
                }
            }
        }

        LaunchedEffect(Unit) { recargar() }

        // El panel del servidor puede bloquear esta tele (mensaje a pantalla completa) o pedirle que se actualice.
        // Se pregunta cada 20 s; si el servidor no responde se deja todo como estaba.
        var bloqueo by remember { mutableStateOf<Bloqueo?>(null) }
        var pedirActualizar by remember { mutableStateOf(0) }
        LaunchedEffect(servidorVacio) {
            while (!servidorVacio) {
                try {
                    val b = conexion.bloqueo()
                    bloqueo = if (b.bloqueada) b else null
                    if (b.actualizar) pedirActualizar++
                } catch (e: Exception) {
                    // sin conexión: no se bloquea ni se desbloquea
                }
                delay(20_000)
            }
        }

        // Estado del motor para la píldora del menú: se comprueba al arrancar y cada minuto
        var motor by remember { mutableStateOf<Pair<String, androidx.compose.ui.graphics.Color>>("Motor…" to Colores.Aviso) }
        LaunchedEffect(servidorVacio, ajustes.motorEnTele) {
            while (!servidorVacio) {
                motor = try { conexion.comprobarMotor(); "Motor listo" to Colores.Bien } catch (e: Exception) { "Motor sin respuesta" to Colores.Mal }
                delay(60_000)
            }
            if (servidorVacio) motor = "Sin servidor" to Colores.Apagado
        }

        AccionesCanal.conexion = conexion
        AccionesCanal.alcance = alcance
        AccionesCanal.categorias = catalogo?.categorias ?: emptyList()
        AccionesCanal.recargar = { recargar() }
        val datos = Datos(catalogo, cargando, error, servidorVacio, cambios, { recargar() }, { favorito(it) })

        Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize().background(Colores.Fondo)) {
            // Menú lateral, como el de HomeTV: fondo más oscuro, marca, píldoras y el estado abajo
            Column(Modifier.width(184.dp).fillMaxHeight().background(Colores.Lateral).padding(horizontal = 14.dp, vertical = 20.dp)) {
                // El logo completo tal cual (perro y «Beagle TV» en una línea), solo reducido para que quepa
                androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(R.drawable.logo_completo), "Beagle TV",
                    Modifier.fillMaxWidth().padding(bottom = 18.dp),
                    contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
                )
                for (s in Seccion.entries) {
                    ItemMenu(s.titulo, s == seccion, { seccion = s; ajustes.ultimaSeccion = s.name }, Modifier.fillMaxWidth())
                    Spacer(Modifier.height(2.dp))
                }
                Spacer(Modifier.weight(1f))
                // Reloj y estado del motor abajo, como en HomeTV
                var hora by remember { mutableStateOf("") }
                LaunchedEffect(Unit) {
                    val formato = java.text.SimpleDateFormat("HH:mm", java.util.Locale("es", "ES"))
                    while (true) { hora = formato.format(java.util.Date()); delay(15_000) }
                }
                Text(hora, fontSize = 26.sp, color = Colores.Texto, modifier = Modifier.padding(start = 12.dp))
                PildoraEstado(motor.first, motor.second, Modifier.padding(start = 12.dp))
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (seccion) {
                    Seccion.Inicio -> PantallaInicio(datos, ajustes) { seccion = Seccion.Ajustes }
                    Seccion.Canales -> PantallaCanales(datos, conexion, ajustes)
                    Seccion.Agenda -> PantallaAgenda(datos, conexion)
                    Seccion.Videos -> PantallaVideos(conexion, servidorVacio)
                    Seccion.Buscar -> PantallaBuscar(datos, conexion)
                    Seccion.Ajustes -> PantallaAjustes(conexion, ajustes, servidorVacio) { recargar() }
                }
            }
        }
            // El aviso de versión nueva va por encima de todo y coge el foco
            AvisoActualizacion(ajustes, pedirActualizar, Modifier.fillMaxSize())
            bloqueo?.let { PantallaBloqueada(it.mensaje) }
        }
        }
    }
}

/** Pantalla completa con el mensaje del panel; no deja pulsar nada de lo que hay debajo. */
@Composable
private fun PantallaBloqueada(mensaje: String) {
    val foco = remember { FocusRequester() }
    BackHandler(enabled = true) { /* no se puede salir */ }
    LaunchedEffect(Unit) { try { foco.requestFocus() } catch (e: Exception) { /* aún no está colocado */ } }
    Box(Modifier.fillMaxSize().background(Colores.Fondo).focusRequester(foco).focusable(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(64.dp)) {
            Text("Beagle está bloqueada", fontSize = 34.sp, color = Colores.Acento)
            Spacer(Modifier.height(16.dp))
            Text(mensaje.ifEmpty { "La tele está bloqueada." }, fontSize = 24.sp, color = Colores.Texto)
        }
    }
}
