package es.locometopa.beagle

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.BackHandler
import androidx.tv.material3.Text
import kotlinx.coroutines.launch

/** Ajustes como los de HomeTV: una lista de filas (nombre a la izquierda, valor en verde a la derecha), por grupos. */
@Composable
fun PantallaAjustes(conexion: Conexion, ajustes: Ajustes, servidorVacio: Boolean, alCambiarServidor: () -> Unit) {
    var verListas by remember { mutableStateOf(false) }
    BackHandler(enabled = verListas) { verListas = false }
    if (verListas) PantallaListas(conexion, servidorVacio, alCambiarServidor)
    else AjustesGenerales(conexion, ajustes, alCambiarServidor) { verListas = true }
}

@Composable
private fun AjustesGenerales(conexion: Conexion, ajustes: Ajustes, alCambiarServidor: () -> Unit, abrirListas: () -> Unit) {
    val alcance = rememberCoroutineScope()
    val contexto = androidx.compose.ui.platform.LocalContext.current
    var texto by remember { mutableStateOf(conexion.servidor()) }
    var estado by remember { mutableStateOf<Estado>(if (conexion.servidor().isEmpty()) Estado.SinServidor else Estado.Comprobando) }
    var copia by remember { mutableStateOf(conexion.copiaLocal()) }
    var enTele by remember { mutableStateOf(conexion.motorEnTele()) }
    var motor by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var reproductor by remember { mutableStateOf(ajustes.reproductor) }
    var mensaje by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var seguro by remember { mutableStateOf(false) }
    var ocultarCaidos by remember { mutableStateOf(ajustes.ocultarCaidos) }
    var comprobando by remember { mutableStateOf<Comprobacion?>(null) }

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

    fun cambiarVpn(usar: String) {
        mensaje = true to "Cambiando la VPN del servidor… tarda unos segundos"
        alcance.launch {
            try {
                conexion.cambiarVpn(usar)
                kotlinx.coroutines.delay(8000)
                comprobar()
                mensaje = null
            } catch (x: Exception) {
                mensaje = false to (x.message ?: "No se ha podido cambiar la VPN")
            }
        }
    }

    // Al abrir se pregunta al servidor (la copia local ya está a la vista)
    LaunchedEffect(Unit) { if (conexion.servidor().isNotEmpty()) comprobar() }

    val (colorEstado, tituloEstado, detalleEstado) = when (val e = estado) {
        is Estado.SinServidor -> Triple(Colores.Apagado, "Sin servidor", "Escribe la IP del servidor o pulsa «Buscar en la red».")
        is Estado.Buscando -> Triple(Colores.Aviso, "Buscando…", "Recorriendo la red local (tarda unos segundos).")
        is Estado.Comprobando -> Triple(Colores.Aviso, "Comprobando…", "")
        is Estado.Conectado -> Triple(
            Colores.Bien, "Conectado",
            listOfNotNull(
                e.salud.motor?.let { "Motor Ace Stream $it" } ?: "Motor Ace Stream parado",
                if (e.salud.vpn) "VPN activa" else null,
                e.salud.apk?.let { "HomeTV $it en el servidor" },
                e.aviso,
            ).joinToString(" · "),
        )
        is Estado.NoResponde -> Triple(Colores.Mal, "Sin conexión", e.motivo + if (e.copia != null) " · se usa la copia guardada en la tele" else "")
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        var avanzado by remember { mutableStateOf(false) }
        BackHandler(enabled = avanzado) { avanzado = false }
        Text(if (avanzado) "Ajustes avanzados" else "Ajustes", fontSize = 28.sp, color = Colores.Texto, fontWeight = FontWeight.SemiBold)
        if (!avanzado) {
        TituloAjustes("Canales y móvil")
        FilaAjuste("Listas de canales", "encender, apagar, añadir ›", abrirListas)
        var movil by remember { mutableStateOf(PanelMovil.activo) }
        FilaAjuste("Controlar desde el móvil (código QR)", if (movil) "Encendido ›" else "Apagado ›", {
            PanelMovil.preparar(contexto, conexion, ajustes)
            mostrarPanelMovil(contexto) { movil = PanelMovil.activo }
        })
        TituloAjustes("Reproducción y vista")
        FilaAjuste("Reproductor", reproductor.nombre, {
            val todos = Reproductor.values()
            reproductor = todos[(reproductor.ordinal + 1) % todos.size]
            ajustes.reproductor = reproductor
        })
        val apps = listOf(Reproductor.Vlc, Reproductor.AcePlayer, Reproductor.AceServe)
            .joinToString(" · ") { it.nombre + if (Externos.instalada(contexto, it) != null) " ✓" else " ✗" }
        Text("Instaladas: $apps", fontSize = 14.sp, color = Colores.Apagado, modifier = Modifier.padding(start = 6.dp))
        var okFicha by remember { mutableStateOf(ajustes.okFicha) }
        FilaAjuste("OK sobre un canal", if (okFicha) "Abre su ficha" else "Reproduce", { okFicha = !okFicha; ajustes.okFicha = okFicha })
        var okLargo by remember { mutableStateOf(ajustes.okLargo) }
        FilaAjuste("Mantener OK pulsado en un canal", if (okLargo == "opciones") "Abre sus opciones" else "Marca favorito", { okLargo = if (okLargo == "opciones") "favorito" else "opciones"; ajustes.okLargo = okLargo })
        Text("La tecla ≡ del mando abre siempre las opciones del canal, y OK reproduce.", fontSize = 14.sp, color = Colores.Apagado, modifier = Modifier.padding(start = 6.dp))
        var orden by remember { mutableStateOf(ajustes.orden) }
        val nombresOrden = listOf("Mejores primero", "Con partido primero", "Por categoría", "A-Z", "Más peers")
        FilaAjuste("Orden de los canales", nombresOrden[orden], { orden = (orden + 1) % nombresOrden.size; ajustes.orden = orden; mensaje = true to "Se aplica al volver a Canales" })
        var formato by remember { mutableStateOf(ajustes.formato) }
        FilaAjuste("Formato de Ace Stream (reproductor de Beagle)", if (formato == "hls") "HLS troceado (si se congela)" else "Normal (TS)", { formato = if (formato == "hls") "ts" else "hls"; ajustes.formato = formato })
        var cuadricula by remember { mutableStateOf(ajustes.cuadricula) }
        FilaAjuste("Canales en cuadrícula", if (cuadricula) "Sí" else "No (lista)", { cuadricula = !cuadricula; ajustes.cuadricula = cuadricula; mensaje = true to "Se aplica al volver a Canales" })
        var logos by remember { mutableStateOf(ajustes.logos) }
        FilaAjuste("Logos de los canales", if (logos) "Sí" else "No (más ligero)", { logos = !logos; ajustes.logos = logos; mensaje = true to "Se aplica al volver a Canales" })
        FilaAjuste("Ocultar los caídos en «Todos»", if (ocultarCaidos) "Sí" else "No", { ocultarCaidos = !ocultarCaidos; ajustes.ocultarCaidos = ocultarCaidos })
        TituloAjustes("Servidor de casa")
        FilaAjuste("Estado", tituloEstado, { comprobar() }, colorValor = colorEstado)
        if (detalleEstado.isNotEmpty()) Text(detalleEstado, fontSize = 14.sp, color = Colores.Apagado, modifier = Modifier.padding(start = 6.dp))
        Text("Beagle coge los canales, las listas y los favoritos del servidor de casa de HomeTV (puerto 8096).", fontSize = 14.sp, color = Colores.Apagado, modifier = Modifier.padding(start = 6.dp))
        if (conexion.servidor().isNotEmpty())
            Text("Panel de gestión (desde el móvil o el PC, en el mismo wifi): http://${conexion.servidor().substringBefore(':')}:8096", fontSize = 14.sp, color = Colores.Texto, modifier = Modifier.padding(start = 6.dp))
        CampoTexto(texto, { texto = it }, "Servidor: pulsa OK y escribe la IP (192.168.1.180)", Modifier.fillMaxWidth(),
            teclado = KeyboardType.Uri, alAceptar = { conectar() })
        FilaAjuste("Buscar el servidor en la red", "›", {
            estado = Estado.Buscando
            alcance.launch {
                val ip = conexion.buscar()
                if (ip != null) { texto = ip; alCambiarServidor(); comprobar() }
                else estado = Estado.NoResponde("No se ha encontrado el servidor en esta red", copia)
            }
        })
        TituloAjustes("Partidos")
        var equipos by remember { mutableStateOf(conexion.equipos()) }
        FilaAjuste("Equipos favoritos (sus partidos se marcan solos con 🔔)", if (equipos.isEmpty()) "Ninguno ›" else equipos.joinToString(", ") + " ›", {
            fun guardar(nueva: List<String>) { alcance.launch { try { conexion.ponerEquipos(nueva); equipos = nueva } catch (e: Exception) { mensaje = false to (e.message ?: "No se ha podido") } } }
            val ops = equipos.map { "✕ Quitar $it" } + "＋ Añadir un equipo…"
            DialogoBeagle(contexto).setTitle("Equipos favoritos").setItems(ops.toTypedArray()) { _, i ->
                if (i < equipos.size) guardar(equipos.filterIndexed { j, _ -> j != i })
                else {
                    val caja = android.widget.EditText(contexto).apply { hint = "Nombre del equipo (Getafe, Real Madrid…)"; setSingleLine(true) }
                    DialogoBeagle(contexto).setTitle("Añadir un equipo").setView(caja)
                        .setPositiveButton("Añadir") { _, _ -> val t = caja.text.toString().trim(); if (t.isNotEmpty() && equipos.none { it.equals(t, true) }) guardar(equipos + t) }
                        .setNegativeButton("Cancelar", null).show()
                }
            }.setNegativeButton("Cerrar", null).show()
        })
        var pre by remember { mutableStateOf(ajustes.preComprobar) }
        FilaAjuste("Preparar canales 15 min antes de los partidos", when (pre) { "todos" -> "Todos los partidos"; "no" -> "No"; else -> "Solo los marcados 🔔" }, {
            pre = when (pre) { "marcados" -> "todos"; "todos" -> "no"; else -> "marcados" }; ajustes.preComprobar = pre
        })
        TituloAjustes("Copia de seguridad")
        FilaAjuste("Hacer una copia ahora en el servidor", "›", {
            mensaje = true to "Guardando la copia en el servidor…"
            alcance.launch {
                mensaje = try { true to "Copia guardada: " + conexion.copiaAhora() } catch (e: Exception) { false to (e.message ?: "No se ha podido") }
            }
        })
        TituloAjustes("Más")
        FilaAjuste("Ajustes avanzados", "comprobación, VPN, motor, copias… ›", { avanzado = true })
        } else {
        FilaAjuste("‹ Volver a Ajustes", "", { avanzado = false })
        TituloAjustes("Comprobación de canales")
        val cp = comprobando
        FilaAjuste(
            if (cp?.activa == true) "Parar la comprobación de canales" else "Comprobar todos los canales (lo hace el servidor)",
            if (cp == null) "›" else if (cp.activa) "${cp.hechos}/${cp.total} · ${cp.vivos} vivos" else "${cp.vivos} vivos de ${cp.total}",
            {
                val empezar = cp?.activa != true
                comprobando = Comprobacion(empezar, 0, cp?.total ?: 0, 0, "")
                alcance.launch {
                    try {
                        conexion.comprobar(empezar)
                        while (true) {
                            kotlinx.coroutines.delay(3000)
                            val p = conexion.comprobacion()
                            comprobando = p
                            if (!p.activa) { alCambiarServidor(); break }
                        }
                    } catch (e: Exception) { mensaje = false to (e.message ?: "No se ha podido") ; comprobando = null }
                }
            },
        )
        var alAbrir by remember { mutableStateOf(ajustes.comprobarAlAbrir) }
        FilaAjuste("Comprobar los canales al abrir la app", if (alAbrir) "Sí" else "No", { alAbrir = !alAbrir; ajustes.comprobarAlAbrir = alAbrir })
        var horas by remember { mutableStateOf(ajustes.comprobarHoras) }
        val opcionesHoras = listOf(1, 3, 6, 12, 24, 48)
        FilaAjuste("Recomprobar si han pasado", "$horas h", { horas = opcionesHoras[(opcionesHoras.indexOf(horas) + 1) % opcionesHoras.size]; ajustes.comprobarHoras = horas })
        TituloAjustes("Listas")
        var nOcultas by remember { mutableStateOf(conexion.catOcultas().size) }
        FilaAjuste("Ocultar categorías", if (nOcultas == 0) "Ninguna ›" else "$nOcultas ocultas ›", {
            val todas = AccionesCanal.categorias
            if (todas.isEmpty()) { mensaje = false to "Aún no hay categorías: espera a que carguen los canales"; return@FilaAjuste }
            val ocultas = conexion.catOcultas()
            val marcadas = BooleanArray(todas.size) { Modelo.sinTildes(todas[it]) in ocultas }
            DialogoBeagle(contexto)
                .setTitle("Marca las categorías que quieres ocultar")
                .setMultiChoiceItems(todas.toTypedArray(), marcadas) { _, i, v -> marcadas[i] = v }
                .setPositiveButton("Guardar") { _, _ ->
                    val elegidas = todas.filterIndexed { i, _ -> marcadas[i] }
                    alcance.launch {
                        try { conexion.ponerCatOcultas(elegidas); nOcultas = elegidas.size; alCambiarServidor() }
                        catch (e: Exception) { mensaje = false to (e.message ?: "No se ha podido") }
                    }
                }.setNegativeButton("Cancelar", null).show()
        })
        FilaAjuste("Juntar categorías repetidas («MOTOR» y «Motor»)", "›", {
            alcance.launch {
                try {
                    val n = conexion.unificarCategorias()
                    mensaje = true to (if (n > 0) "$n canales juntados en su categoría" else "No había categorías repetidas")
                    alCambiarServidor()
                } catch (x: Exception) { mensaje = false to (x.message ?: "No se ha podido") }
            }
        })
        var actListas by remember { mutableStateOf(ajustes.actualizarListas) }
        FilaAjuste("Actualizar las listas con dirección web al abrir la app", if (actListas) "Sí" else "No", { actListas = !actListas; ajustes.actualizarListas = actListas })
        var ligera by remember { mutableStateOf(ajustes.sincroLigero) }
        FilaAjuste("Tele ligera: no guardar aquí los canales de listas apagadas", if (ligera) "Sí" else "No", { ligera = !ligera; ajustes.sincroLigero = ligera; mensaje = true to "Se aplica en la próxima sincronización" })
        val conectado = estado as? Estado.Conectado
        if (conectado != null) {
            TituloAjustes("VPN del servidor")
            FilaAjuste("Estado de la VPN", if (conectado.salud.vpn) "activa" + (conectado.salud.vpnDetalle?.let { " · $it" } ?: "") else "apagada",
                { comprobar() }, colorValor = if (conectado.salud.vpn) Colores.Bien else Colores.Apagado)
            var tvVpn by remember { mutableStateOf(conexion.ajusteCompartido("tvPorVpn", true)) }
            FilaAjuste("Canales de TV (IPTV/HLS) por la VPN del servidor", if (tvVpn) "Sí" else "No", {
                tvVpn = !tvVpn
                alcance.launch { try { conexion.ponerAjusteCompartido("tvPorVpn", tvVpn) } catch (e: Exception) { tvVpn = !tvVpn; mensaje = false to (e.message ?: "No se ha podido") } }
            })
            FilaAjuste("Cloudflare WARP", "usar ›", { cambiarVpn("warp") })
            FilaAjuste("Proton VPN", "usar ›", { cambiarVpn("proton") })
            FilaAjuste("Sin VPN", "›", { cambiarVpn("ninguna") })
        }
        TituloAjustes("Motor de Ace Stream")
        FilaAjuste("Dónde está el motor", if (enTele) "En esta tele" else "En el servidor de casa", {
            enTele = !enTele; conexion.ponerMotorEnTele(enTele); motor = null
        })
        FilaAjuste("Probar el motor", motor?.second ?: "›", {
            motor = true to "Probando…"
            alcance.launch {
                motor = try { true to conexion.comprobarMotor() } catch (e: Exception) { false to (e.message ?: "No responde") }
            }
        }, colorValor = if (motor?.first == false) Colores.Mal else Colores.Acento)
        TituloAjustes("Recuperar copias")
        FilaAjuste("Ver las copias del servidor", "›", {
            alcance.launch {
                mensaje = try {
                    val c = conexion.copias()
                    true to if (c.isEmpty()) "Aún no hay copias" else "Copias (${c.size}): " + c.take(4).joinToString(" · ")
                } catch (e: Exception) { false to (e.message ?: "No se ha podido") }
            }
        })
        FilaAjuste("Recuperar una copia del servidor…", "›", {
            alcance.launch {
                try {
                    val nombres = conexion.copias().take(12)
                    if (nombres.isEmpty()) { mensaje = true to "Aún no hay copias"; return@launch }
                    DialogoBeagle(contexto)
                        .setTitle("Recuperar una copia (sustituye los canales y listas de todas las teles)")
                        .setItems(nombres.toTypedArray()) { _, i ->
                            mensaje = true to "Recuperando…"
                            alcance.launch {
                                mensaje = try { conexion.restaurar(nombres[i]); alCambiarServidor(); true to "Copia recuperada: ${nombres[i]}" }
                                catch (e: Exception) { false to (e.message ?: "No se ha podido") }
                            }
                        }
                        .setNegativeButton("Cancelar", null).show()
                } catch (e: Exception) { mensaje = false to (e.message ?: "No se ha podido") }
            }
        })
        TituloAjustes("Mantenimiento")
        FilaAjuste("Quitar las listas apagadas y sus canales", "›", {
            DialogoBeagle(contexto).setTitle("¿Quitar las listas apagadas?").setMessage("Se borran del servidor (para todas las teles) con sus canales. Antes se guarda una copia.")
                .setPositiveButton("Quitar") { _, _ -> mensaje = true to "Quitando…"; alcance.launch {
                    mensaje = try { val n = conexion.mantenimiento(false); alCambiarServidor(); true to "Hecho: $n canales quitados" } catch (e: Exception) { false to (e.message ?: "No se ha podido") }
                } }.setNegativeButton("Cancelar", null).show()
        })
        FilaAjuste("Borrar los canales caídos (salvo favoritos)", "›", {
            DialogoBeagle(contexto).setTitle("¿Borrar los canales caídos?").setMessage("Se borran del servidor (para todas las teles) los que no funcionan. Los favoritos se quedan.")
                .setPositiveButton("Borrar") { _, _ -> mensaje = true to "Borrando…"; alcance.launch {
                    mensaje = try { val n = conexion.mantenimiento(true); alCambiarServidor(); true to "Hecho: $n canales borrados" } catch (e: Exception) { false to (e.message ?: "No se ha podido") }
                } }.setNegativeButton("Cancelar", null).show()
        })
        FilaAjuste("Limpiar la caché de la app", "›", {
            val n = try { contexto.cacheDir.deleteRecursively() } catch (e: Exception) { false }
            mensaje = true to if (n) "Caché limpia" else "No había nada que limpiar"
        })
        FilaAjuste(if (seguro) "¿Seguro? Pulsa otra vez para borrar lo guardado en esta tele" else "Empezar de cero en esta tele", "›", {
            if (!seguro) { seguro = true; mensaje = false to "Pulsa otra vez «Empezar de cero» para borrar lo guardado en esta tele (el servidor no se toca)" }
            else { seguro = false; conexion.empezarDeCero(); mensaje = true to "Hecho: cierra y abre la app para volver a cargarlo todo del servidor" }
        }, peligro = true)
        TituloAjustes("Pantalla")
        var destacados by remember { mutableStateOf(ajustes.destacados) }
        FilaAjuste("Eventos destacados del día en Inicio", if (destacados == 0) "No" else "$destacados cuadrados", { destacados = when (destacados) { 0 -> 2; 2 -> 3; 3 -> 4; else -> 0 }; ajustes.destacados = destacados })
        var filasCat by remember { mutableStateOf(ajustes.filasCategorias) }
        FilaAjuste("Filas por categoría en Inicio", if (filasCat) "Sí" else "No", { filasCat = !filasCat; ajustes.filasCategorias = filasCat })
        var intro by remember { mutableStateOf(ajustes.intro) }
        FilaAjuste("Vídeo de entrada al abrir la app (prueba)", if (intro) "Sí" else "No", { intro = !intro; ajustes.intro = intro })
        }
        mensaje?.let { (bien, t) -> Text(t, fontSize = 15.sp, color = if (bien) Colores.Bien else Colores.Aviso, modifier = Modifier.padding(start = 6.dp, top = 4.dp)) }
        if (avanzado) {
        copia?.let { c ->
            TituloAjustes("Lo que hay en la tele")
            val r = c.resumen
            Text("${r.canales} canales" + (if (r.ligero) " (sin los de listas apagadas)" else "") + " · ${r.listas} listas, ${r.listasEncendidas} encendidas · ${r.favoritos} favoritos", fontSize = 15.sp, color = Colores.Apagado, modifier = Modifier.padding(start = 6.dp))
        }
        }
        Text("Beagle ${ajustes.version()}", fontSize = 14.sp, color = Colores.Apagado, modifier = Modifier.padding(start = 6.dp, top = 12.dp, bottom = 24.dp))
    }
}


/** Diálogo del panel del móvil: código QR, dirección, y encender/apagar/cambiar la clave. */
private fun mostrarPanelMovil(contexto: android.content.Context, alCambiar: () -> Unit) {
    val dp = contexto.resources.displayMetrics.density
    val caja = android.widget.LinearLayout(contexto).apply { orientation = android.widget.LinearLayout.VERTICAL; setPadding((24 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt(), 0) }
    fun pintar(): Unit {
        caja.removeAllViews()
        if (!PanelMovil.activo) {
            caja.addView(android.widget.TextView(contexto).apply { text = "El panel del móvil está apagado."; textSize = 16f })
            return
        }
        val url = PanelMovil.direccion()
        val matriz = com.google.zxing.qrcode.QRCodeWriter().encode(url, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0, mapOf(com.google.zxing.EncodeHintType.MARGIN to 2))
        val lado = 6
        val bmp = android.graphics.Bitmap.createBitmap(matriz.width * lado, matriz.height * lado, android.graphics.Bitmap.Config.RGB_565)
        for (x in 0 until matriz.width) for (y in 0 until matriz.height) {
            val c = if (matriz.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            for (i in 0 until lado) for (j in 0 until lado) bmp.setPixel(x * lado + i, y * lado + j, c)
        }
        caja.addView(android.widget.ImageView(contexto).apply { setImageBitmap(bmp); layoutParams = android.widget.LinearLayout.LayoutParams((200 * dp).toInt(), (200 * dp).toInt()).apply { gravity = android.view.Gravity.CENTER_HORIZONTAL } })
        caja.addView(android.widget.TextView(contexto).apply { text = "Escanea el código con la cámara del móvil, o escribe esta dirección en el navegador del móvil o del ordenador (en la misma red):"; textSize = 14f; setPadding(0, (8 * dp).toInt(), 0, 0) })
        caja.addView(android.widget.TextView(contexto).apply { text = url.removePrefix("http://"); textSize = 18f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        caja.addView(android.widget.TextView(contexto).apply { text = "Desde el móvil puedes importar listas, renombrar y ordenar canales por categoría, borrar, comprobar y mandar un canal a reproducir en la tele. Funciona mientras Beagle esté abierta."; textSize = 13f; setPadding(0, (8 * dp).toInt(), 0, 0) })
    }
    var d: DialogoBeagle? = null
    fun botones() {
        d?.dismiss()
        pintar()
        val b = DialogoBeagle(contexto).setTitle("Controlar desde el móvil").setView(caja)
        if (PanelMovil.activo) {
            b.setNeutralButton("Apagar") { _, _ -> PanelMovil.apagar(); alCambiar() }
            b.setNegativeButton("Cambiar la clave") { _, _ -> try { PanelMovil.cambiarClave() } catch (e: Exception) { }; alCambiar(); mostrarPanelMovil(contexto, alCambiar) }
            b.setPositiveButton("Cerrar", null)
        } else {
            b.setPositiveButton("Encender") { _, _ ->
                try { PanelMovil.encender() } catch (e: Exception) { android.widget.Toast.makeText(contexto, "No se pudo encender: ${e.message}", android.widget.Toast.LENGTH_LONG).show() }
                alCambiar(); mostrarPanelMovil(contexto, alCambiar)
            }
            b.setNegativeButton("Cerrar", null)
        }
        d = b.show()
    }
    botones()
}
