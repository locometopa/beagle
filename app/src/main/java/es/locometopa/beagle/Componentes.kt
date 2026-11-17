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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
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

// Los mismos tonos que HomeTV: gris oscuro con verde lima
object Colores {
    val Fondo = Color(0xFF202020)
    val Panel = Color(0xFF2F2F2F)
    val PanelFoco = Color(0xFF3E3E3E)
    val Lateral = Color(0xFF1B1B1B)
    val Linea = Color(0xFF4B4B4B)
    val Texto = Color(0xFFF4F4F4)
    val Apagado = Color(0xFFA0A0A0)
    val Acento = Color(0xFFC6F24E)
    val Bien = Color(0xFF7FE0A8)
    val Aviso = Color(0xFFF0C64A)
    val Mal = Color(0xFFFF6B6E)
}

/** Una fila como `.row-btn` de HomeTV: fondo gris, y con el foco borde verde y fondo algo más claro. Sin sombras: así va fluido. */
@Composable
fun Fila(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    seleccionada: Boolean = false,
    contenido: @Composable RowScope.() -> Unit,
) {
    val fuente = remember { MutableInteractionSource() }
    val foco by fuente.collectIsFocusedAsState()
    val forma = RoundedCornerShape(14.dp)
    Row(
        modifier
            .clip(forma)
            .background(if (foco) Colores.PanelFoco else Colores.Panel)
            .border(2.dp, if (foco) Colores.Acento else if (seleccionada) Colores.Linea else Color.Transparent, forma)
            .clickable(interactionSource = fuente, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = contenido,
    )
}

/** Selector en píldora (`.sel`/`.chip` de HomeTV): borde gris; el elegido en verde; con el foco, blanco con letra oscura. */
@Composable
fun Chip(texto: String, elegido: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, tam: Int = 16) {
    val fuente = remember { MutableInteractionSource() }
    val foco by fuente.collectIsFocusedAsState()
    val forma = RoundedCornerShape(999.dp)
    Row(
        modifier
            .clip(forma)
            .background(if (foco) Color.White else Color.Transparent)
            .border(2.dp, if (foco) Color.White else if (elegido) Colores.Acento else Colores.Linea, forma)
            .clickable(interactionSource = fuente, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(texto, fontSize = tam.sp, color = if (foco) Colores.Fondo else if (elegido) Colores.Acento else Colores.Apagado, fontWeight = if (elegido || foco) FontWeight.SemiBold else FontWeight.Normal)
    }
}

/** Tarjeta con el foco del mando para las filas horizontales de Inicio (`.tarjeta` de HomeTV). */
@Composable
fun Tarjeta(onClick: () -> Unit, ancho: androidx.compose.ui.unit.Dp, alto: androidx.compose.ui.unit.Dp, contenido: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val fuente = remember { MutableInteractionSource() }
    val foco by fuente.collectIsFocusedAsState()
    val forma = RoundedCornerShape(14.dp)
    androidx.compose.foundation.layout.Column(
        Modifier
            .width(ancho).height(alto)
            .clip(forma)
            .background(if (foco) Colores.PanelFoco else Colores.Panel)
            .border(2.dp, if (foco) Colores.Acento else Color.Transparent, forma)
            .clickable(interactionSource = fuente, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        content = contenido,
    )
}

/** Botón en píldora (`.hbtn`): el principal en verde; con el foco, blanco. */
@Composable
fun Boton(texto: String, onClick: () -> Unit, principal: Boolean = false, modifier: Modifier = Modifier) {
    val fuente = remember { MutableInteractionSource() }
    val foco by fuente.collectIsFocusedAsState()
    val forma = RoundedCornerShape(999.dp)
    Row(
        modifier
            .clip(forma)
            .background(if (foco) Color.White else if (principal) Colores.Acento else Colores.PanelFoco)
            .clickable(interactionSource = fuente, indication = null, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(texto, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = if (foco || principal) Colores.Fondo else Colores.Texto)
    }
}

/** Fila de Ajustes (`.srow`): el nombre a la izquierda y, a la derecha, el valor en verde. */
@Composable
fun FilaAjuste(etiqueta: String, valor: String, onClick: () -> Unit, modifier: Modifier = Modifier, peligro: Boolean = false, colorValor: Color = Colores.Acento) {
    Fila(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        Text(etiqueta, fontSize = 19.sp, color = if (peligro) Colores.Mal else Colores.Texto, modifier = Modifier.weight(1f), maxLines = 2)
        if (valor.isNotEmpty()) Text(valor, fontSize = 18.sp, color = colorValor, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 380.dp).padding(start = 16.dp))
    }
}

/** Título de grupo en Ajustes (`.settings h3`). */
@Composable
fun TituloAjustes(texto: String) {
    Text(texto.uppercase(), fontSize = 14.sp, color = Colores.Apagado, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, modifier = Modifier.padding(top = 16.dp, bottom = 2.dp, start = 6.dp))
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
                .background(Colores.Panel, RoundedCornerShape(14.dp))
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
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(texto, fontSize = 16.sp, color = letra, fontWeight = if (foco || seleccionada) FontWeight.SemiBold else FontWeight.Medium)
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
