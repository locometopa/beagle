package es.locometopa.beagle

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.tv.material3.darkColorScheme

/** Esqueleto de Beagle: de momento solo saluda. Las pantallas se irán añadiendo según CHECKLIST.md. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { BeagleApp() }
    }
}

// Los mismos tonos que HomeTV
private val Fondo = Color(0xFF202020)
private val Texto = Color(0xFFF4F4F4)
private val Acento = Color(0xFFC6F24E)

@Composable
fun BeagleApp() {
    MaterialTheme(colorScheme = darkColorScheme(primary = Acento, background = Fondo, onBackground = Texto)) {
        Column(
            modifier = Modifier.fillMaxSize().background(Fondo),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = "Hola Beagle", color = Acento, style = MaterialTheme.typography.displayLarge)
            Text(text = "Versión de arranque: aquí irán los canales", color = Texto, style = MaterialTheme.typography.titleMedium)
        }
    }
}
