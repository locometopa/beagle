package es.locometopa.beagle

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

// Los mismos tonos que HomeTV
object Colores {
    val Fondo = Color(0xFF202020)
    val Panel = Color(0xFF2D2D2D)
    val Texto = Color(0xFFF4F4F4)
    val Apagado = Color(0xFFA0A0A0)
    val Acento = Color(0xFFC6F24E)
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
            .background(if (foco) Colores.Acento.copy(alpha = 0.25f) else if (seleccionada) Color.White.copy(alpha = 0.10f) else Color.Transparent)
            .border(2.dp, if (foco) Colores.Acento else Color.Transparent, forma)
            .clickable(interactionSource = fuente, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
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
            textStyle = TextStyle(color = Colores.Texto, fontSize = 20.sp),
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
