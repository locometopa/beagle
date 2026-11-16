package es.locometopa.beagle

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme
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
    Inicio("Inicio"), Canales("Canales"), Agenda("Agenda"), Buscar("Buscar"), Ajustes("Ajustes")
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
        val alcance = rememberCoroutineScope()
        var seccion by remember { mutableStateOf(Seccion.Inicio) }
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

        val datos = Datos(catalogo, cargando, error, servidorVacio, cambios, { recargar() }, { favorito(it) })

        Box(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize().background(Colores.Fondo)) {
            // Menú lateral
            Column(Modifier.width(240.dp).fillMaxHeight().background(Colores.Panel).padding(16.dp)) {
                Text("Beagle", fontSize = 30.sp, color = Colores.Acento, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 24.dp))
                for (s in Seccion.values()) {
                    Fila(onClick = { seccion = s }, modifier = Modifier.fillMaxWidth(), seleccionada = s == seccion) {
                        Text(s.titulo, fontSize = 22.sp, color = Colores.Texto)
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (seccion) {
                    Seccion.Inicio -> PantallaInicio(datos, ajustes) { seccion = Seccion.Ajustes }
                    Seccion.Canales -> PantallaCanales(datos)
                    Seccion.Agenda -> PantallaAgenda(datos)
                    Seccion.Buscar -> PantallaBuscar(datos)
                    Seccion.Ajustes -> PantallaAjustes(conexion, ajustes) { recargar() }
                }
            }
        }
            // El aviso de versión nueva va por encima de todo y coge el foco
            AvisoActualizacion(ajustes, Modifier.fillMaxSize())
        }
    }
}
