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
import androidx.compose.ui.draw.shadow
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

/** Figtree para el texto y Fraunces para los títulos, incluidas en la app. */
val Cuerpo = FontFamily(
    Font(R.font.figtree_regular, FontWeight.Normal),
    Font(R.font.figtree_medium, FontWeight.Medium),
    Font(R.font.figtree_semibold, FontWeight.SemiBold),
    Font(R.font.figtree_bold, FontWeight.Bold),
)
val Titulos = FontFamily(
    Font(R.font.fraunces_semibold, FontWeight.SemiBold),
    Font(R.font.fraunces_bold, FontWeight.Bold),
)

// Los mismos tonos que HomeTV
object Colores {
    val Fondo = Color(0xFF101B2E)
    val Panel = Color(0xFF1B2A45)
    val Lateral = Color(0xFF07101F)
    val Texto = Color(0xFFF4F6FA)
    val Apagado = Color(0xFFA8B3C7)
    val Acento = Color(0xFFFCA12C)
    val Bien = Color(0xFF38C98A)
    val Aviso = Color(0xFFE6B422)
    val Mal = Color(0xFFF0646E)
}

/** Verdadero dentro de una fila que tiene el foco: la fila se pone blanca y la letra, oscura. */
val LocalEnFoco = androidx.compose.runtime.compositionLocalOf { false }

/** Tintas que se oscurecen solas cuando la fila está enfocada (fondo blanco). */
object Tinta {
    val texto: Color @Composable get() = if (LocalEnFoco.current) Color(0xFF0B1220) else Colores.Texto
    val suave: Color @Composable get() = if (LocalEnFoco.current) Color(0xFF4A5873) else Colores.Apagado
    val acento: Color @Composable get() = if (LocalEnFoco.current) Color(0xFFB86A00) else Colores.Acento
}

/** Un color de estado (verde, ámbar, rojo) que se oscurece en una fila enfocada para que se lea sobre blanco. */
@Composable
fun Color.enFila(): Color = if (LocalEnFoco.current) androidx.compose.ui.graphics.lerp(this, Color.Black, 0.45f) else this

/** Cuadrado de color con las iniciales, como los carteles de HomeTV: el tono sale del nombre. */
@Composable
fun Monograma(titulo: String, tam: androidx.compose.ui.unit.Dp = 44.dp) {
    val palabras = titulo.trim().split(' ', '-', '–').filter { it.isNotEmpty() }
    val ini = when {
        palabras.isEmpty() -> "?"
        palabras.size == 1 -> palabras[0].take(2)
        else -> "" + palabras[0].first() + palabras[1].first()
    }.uppercase()
    val h = (kotlin.math.abs(titulo.lowercase().hashCode()) % 360).toFloat()
    Box(
        Modifier.size(tam).clip(RoundedCornerShape(tam / 4)).background(
            androidx.compose.ui.graphics.Brush.linearGradient(listOf(Color.hsl(h, 0.48f, 0.34f), Color.hsl((h + 40f) % 360f, 0.52f, 0.18f)))
        ),
        contentAlignment = Alignment.Center,
    ) {
        Text(ini, fontSize = (tam.value * 0.38f).sp, fontWeight = FontWeight.Bold, color = Color.hsl(h, 0.7f, 0.9f))
    }
}

/** Una fila que se elige con el mando: con el foco se pone blanca (letra oscura) y con sombra; OK = [onClick]. */
@Composable
fun Fila(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    seleccionada: Boolean = false,
    contenido: @Composable RowScope.() -> Unit,
) {
    val fuente = remember { MutableInteractionSource() }
    val foco by fuente.collectIsFocusedAsState()
    val forma = RoundedCornerShape(22.dp)
    androidx.compose.runtime.CompositionLocalProvider(LocalEnFoco provides foco) {
        Row(
            modifier
                .then(if (foco) Modifier.shadow(18.dp, forma) else Modifier)
                .clip(forma)
                .background(if (foco) Color.White else if (seleccionada) Color.White.copy(alpha = 0.10f) else Color.Transparent)
                .clickable(interactionSource = fuente, indication = null, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = contenido,
        )
    }
}

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
            textStyle = TextStyle(color = Colores.Texto, fontSize = 20.sp, fontFamily = Cuerpo),
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
