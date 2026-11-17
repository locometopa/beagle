package es.locometopa.beagle

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

/** Outfit, la misma letra que HomeTV, incluida en la app. */
val Outfit = FontFamily(
    Font(R.font.outfit_regular, FontWeight.Normal),
    Font(R.font.outfit_medium, FontWeight.Medium),
    Font(R.font.outfit_semibold, FontWeight.SemiBold),
    Font(R.font.outfit_bold, FontWeight.Bold),
)

// Los mismos tonos que HomeTV
object Colores {
    val Fondo = Color(0xFF0B1220)
    val Panel = Color(0xFF18212F)
    val Lateral = Color(0xFF050D1A)
    val Texto = Color(0xFFF4F4F4)
    val Apagado = Color(0xFF9AA3B2)
    val Acento = Color(0xFFFCA12C)
    val Bien = Color(0xFF2FBF71)
    val Aviso = Color(0xFFE6B422)
    val Mal = Color(0xFFE5484D)
}

/** Una fila que se elige con el mando: foco bien visible (borde y fondo verdes), OK = [onClick]. */
@Composable
fun Fila(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    seleccionada: Boolean = false,
    contenido: @Composable RowScope.() -> Unit,
) {
    val fuente = remember { MutableInteractionSource() }
    val foco by fuente.collectIsFocusedAsState()
    val forma = RoundedCornerShape(10.dp)
    Row(
        modifier
            .clip(forma)
            .background(if (foco) Color.White.copy(alpha = 0.16f) else if (seleccionada) Color.White.copy(alpha = 0.10f) else Color.Transparent)
            .border(2.dp, if (foco) Color.White else Color.Transparent, forma)
            .clickable(interactionSource = fuente, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = contenido,
    )
}

/**
 * Campo de texto pensado para el mando: se ve como una fila, y solo al pulsar OK se convierte en campo y sale el teclado.
 * Así el teclado no aparece solo cada vez que el foco pasa por encima.
 */
@Composable
fun CampoTexto(
    valor: String,
    alCambiar: (String) -> Unit,
    pista: String,
    modifier: Modifier = Modifier,
    teclado: KeyboardType = KeyboardType.Text,
    alAceptar: () -> Unit = {},
) {
    var editando by remember { mutableStateOf(false) }
    var tuvoFoco by remember { mutableStateOf(false) }
    var haEditado by remember { mutableStateOf(false) }
    val pedirCampo = remember { FocusRequester() }
    val devolver = remember { FocusRequester() }

    if (!editando) {
        Fila(onClick = { editando = true; haEditado = true }, modifier = modifier.focusRequester(devolver)) {
            Text(valor.ifEmpty { pista }, fontSize = 20.sp, color = if (valor.isEmpty()) Colores.Apagado else Colores.Texto)
        }
    } else {
        BasicTextField(
            value = valor,
            onValueChange = alCambiar,
            singleLine = true,
            textStyle = TextStyle(color = Colores.Texto, fontSize = 20.sp, fontFamily = Outfit),
            cursorBrush = SolidColor(Colores.Acento),
            keyboardOptions = KeyboardOptions(keyboardType = teclado, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { editando = false; alAceptar() }),
            modifier = modifier
                .background(Colores.Panel, RoundedCornerShape(10.dp))
                .border(2.dp, Colores.Acento, RoundedCornerShape(10.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .focusRequester(pedirCampo)
                .onFocusChanged {
                    if (it.isFocused) tuvoFoco = true
                    else if (tuvoFoco) { tuvoFoco = false; editando = false }
                },
        )
        LaunchedEffect(Unit) { try { pedirCampo.requestFocus() } catch (e: Exception) { /* aún no está colocado */ } }
    }
    // Al terminar de escribir el foco vuelve a la fila, para seguir con el mando donde estabas
    LaunchedEffect(editando) {
        if (!editando && haEditado) { try { devolver.requestFocus() } catch (e: Exception) { /* sin foco que devolver */ } }
    }
}

/** Opción del menú lateral, como `.mi` de HomeTV: píldora; la sección actual en verde, la que tiene el foco en blanco. */
@Composable
fun ItemMenu(texto: String, seleccionada: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val fuente = remember { MutableInteractionSource() }
    val foco by fuente.collectIsFocusedAsState()
    val forma = RoundedCornerShape(999.dp)
    val fondo = if (foco) Color.White else if (seleccionada) Colores.Acento else Color.Transparent
    val letra = if (foco || seleccionada) Colores.Fondo else Colores.Apagado
    Row(
        modifier
            .clip(forma)
            .background(fondo)
            .clickable(interactionSource = fuente, indication = null, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(texto, fontSize = 20.sp, color = letra, fontWeight = if (foco || seleccionada) FontWeight.SemiBold else FontWeight.Medium)
    }
}

/** Píldora con un punto de color, como `.pill.engine`: verde = bien, rojo = mal, ámbar = comprobando, gris = sin datos. */
@Composable
fun PildoraEstado(texto: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(color))
        Text(texto, fontSize = 16.sp, color = Colores.Apagado, modifier = Modifier.padding(start = 10.dp))
    }
}
