package es.locometopa.beagle

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/** Beagle, cliente nativo del servidor de casa de HomeTV. Las pantallas se van añadiendo según CHECKLIST.md. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ajustes = Ajustes(applicationContext)
        val conexion = Conexion(ajustes, CopiaLocal(applicationContext))
        setContent { BeagleApp(conexion, ajustes) }
    }
}

// Los mismos tonos que HomeTV
private val Fondo = Color(0xFF202020)
private val Texto = Color(0xFFF4F4F4)
private val Acento = Color(0xFFC6F24E)

@Composable
fun BeagleApp(conexion: Conexion, ajustes: Ajustes) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Acento, background = Fondo, onBackground = Texto)) {
        Box(Modifier.fillMaxSize().background(Fondo)) {
            // De momento solo está el ajuste del servidor; el menú lateral llegará con el bloque 5
            PantallaServidor(conexion)
            AvisoActualizacion(ajustes, Modifier.align(Alignment.BottomCenter))
        }
    }
}
