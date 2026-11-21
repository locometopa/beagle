package es.locometopa.beagle

import android.app.Activity

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Reproductor nativo (Media3 / ExoPlayer). Es el de HomeTV pasado a Kotlin, con la misma receta que allí va bien:
 * pide cada canal al motor de Ace Stream del servidor de casa, reproduce su stream, enseña peers y velocidad,
 * salta solo al enlace de reserva si el primero no va y hace zapping con ▲▼ o los botones de canal.
 */
class ReproductorActivity : Activity() {

    /** Una emisión abierta: de dónde leerla y a quién preguntar por su estado o pedir que la cierre. */
    private class Emision(val url: String, val estado: String?, val orden: String?)

    private companion object {
        const val MAX_VUELTAS = 2
    }

    private lateinit var ajustes: Ajustes
    private var canales: List<Canal> = emptyList()
    private var indice = 0
    private var equipo = ""
    private var motor = ""
    private var casa = ""

    // Enlaces del canal actual; si uno falla se pasa al siguiente
    private var fuentes: List<Enlace> = emptyList()
    private var fuente = 0

    // Cómo se pide el canal al motor: «ts» (flujo continuo) o «hls» (troceado). «formato» puede cambiar solo para un enlace
    private var formato = "ts"
    private var formatoElegido = "ts"
    private var soloHls = HashSet<String>()
    private var soloAac = HashSet<String>()
    private val aacFallido = HashSet<String>()

    private lateinit var player: ExoPlayer
    private lateinit var vista: PlayerView
    private lateinit var panel: LinearLayout
    private lateinit var txtNumero: TextView
    /** Canales que se están grabando en el servidor desde aquí: clave → hasta cuándo (ms). */
    private val grabando = HashMap<String, Long>()
    private lateinit var txtTitulo: TextView
    private lateinit var txtEstado: TextView
    private lateinit var txtAyuda: TextView
    private lateinit var txtZap: TextView
    // Controles al estilo de las plataformas de directo (OK una vez): pausa, volver al directo, sonido…
    private lateinit var controles: LinearLayout
    private lateinit var btnPausa: TextView
    private lateinit var btnDirecto: TextView
    private lateinit var txtDirecto: TextView
    private lateinit var barraFondo: FrameLayout
    private lateinit var barraRelleno: View
    private lateinit var lateral: LinearLayout
    private var botonesOpcionales = emptyList<Pair<View, () -> Boolean>>()
    private var filasValores = emptyList<Pair<TextView, () -> String>>()
    private var anchoLateral = 0
    private var lateralAbierto = false
    private lateinit var btnAjustes: TextView
    private var controlesVisibles = false
    private var retrasoMs = 0L       // cuánto se ha quedado atrás del directo (pausas y retrocesos)
    private var modoPantalla = 0
    private val ocultarControlesR = Runnable { ocultarControles() }
    private var gestos: GestureDetector? = null

    private val ui = Handler(Looper.getMainLooper())
    private val red: ExecutorService = Executors.newSingleThreadExecutor()
    private var esTele = true
    private var listo = false

    @Volatile private var generacion = 0
    @Volatile private var statUrl: String? = null
    @Volatile private var commandUrl: String? = null
    private var reintentos = 0
    private val sinPasarela = HashSet<String>()      // enlaces directos que fallaron por la VPN del servidor: se prueban sin ella
    @Volatile private var porVpnActual = false
    private var vueltas = 0          // vueltas completas a todos los enlaces sin conseguir que suene
    private var yaSonando = false
    private var avisoAudio = false
    private var avisoVideo = false
    private var ultimoOk = 0L
    private var cargaPendiente: Runnable? = null

    private val ocultarPanel = Runnable { panel.animate().alpha(0f).setDuration(300) }
    private val ocultarZap = Runnable { txtZap.visibility = View.GONE }

    // Si no arranca o se queda cargando demasiado, se prueba otra cosa
    private val vigilante = Runnable {
        if (player.playbackState != Player.STATE_READY) {
            // Si nunca llegó a sonar, mejor otro enlace; si se ha cortado a mitad, primero se intenta recuperar
            if (yaSonando) recuperar("Se ha quedado cargando") else siguienteFuente("No arranca")
        }
    }
    private val sondeo = object : Runnable {
        override fun run() {
            consultarEstado()
            ui.postDelayed(this, 3000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        val modo = getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        esTele = modo?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

        ajustes = Ajustes(this)
        canales = Sesion.canales
        equipo = ajustes.servidor
        // Si Android cerró la app por detrás ya no hay lista: se vuelve a la pantalla principal
        if (canales.isEmpty() || equipo.isEmpty()) { finish(); return }
        indice = Sesion.indice.coerceIn(0, canales.size - 1)
        motor = ajustes.urlMotor() ?: Servidor.motor(equipo)
        casa = Servidor.casa(equipo)
        formato = ajustes.formato
        formatoElegido = formato
        soloHls = HashSet(ajustes.soloHls)
        soloAac = HashSet(ajustes.soloAac)

        construirVista()

        // La misma base que en HomeTV: búfer amplio, lectura del MPEG-TS por defecto y decodificadores de la tele
        val carga = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 60000, 2500, 5000)
            .build()
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(30000)
            .setReadTimeoutMs(30000)
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("Beagle")
        player = ExoPlayer.Builder(this)
            .setLoadControl(carga)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
        vista.player = player
        player.addListener(escucha)
        listo = true
        ui.postDelayed(relojVideo, 1000)
        cargar(nuevo = true)
    }

    private val escucha = object : Player.Listener {
        override fun onPlaybackStateChanged(estado: Int) {
            if (estado == Player.STATE_BUFFERING) {
                txtEstado.text = "Cargando…"
                if (yaSonando) { ui.removeCallbacks(vigilante); ui.postDelayed(vigilante, 25000) }
            }
            if (estado == Player.STATE_ENDED && esVideo() && indice < canales.size - 1) zap(1)
            if (estado == Player.STATE_READY) {
                reintentos = 0
                vueltas = 0
                yaSonando = true
                ui.removeCallbacks(vigilante)
                mostrarPanel(4000)
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            // Sonido que la tele no sabe reproducir: se ve pero no se oye
            var hayAudio = false
            var audioOk = false
            var mimeAudio: String? = null
            var hayVideo = false
            var videoOk = false
            var mimeVideo: String? = null
            for (g in tracks.groups) {
                if (g.type == C.TRACK_TYPE_AUDIO) {
                    hayAudio = true
                    for (i in 0 until g.length) {
                        if (g.isTrackSupported(i, true)) audioOk = true
                        if (mimeAudio == null) mimeAudio = g.getTrackFormat(i).sampleMimeType
                    }
                } else if (g.type == C.TRACK_TYPE_VIDEO) {
                    hayVideo = true
                    for (i in 0 until g.length) {
                        if (g.isTrackSupported(i, true)) videoOk = true
                        if (mimeVideo == null) mimeVideo = g.getTrackFormat(i).sampleMimeType
                    }
                }
            }
            if (hayAudio && !audioOk) audioNoCompatible(mimeAudio)
            // Vídeo que la tele no sabe decodificar (MPEG-2, HEVC de 10 bits…): se oye pero la imagen no sale
            if (hayVideo && !videoOk) videoNoCompatible(mimeVideo)
        }

        override fun onPlayerError(error: PlaybackException) {
            // Emisión HLS que se ha quedado atrás: volver al directo sin más
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                player.seekToDefaultPosition()
                player.prepare()
                return
            }
            // El reproductor no reconoce lo que manda el motor en TS: ese enlace se pasa a HLS,
            // que el motor manda ya troceado, y se recuerda para la próxima vez
            val noLeeTs = error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
            val e = fuentes.getOrNull(fuente)
            if (noLeeTs && formato != "hls" && e != null && !e.directo && player.currentMediaItem != null) {
                formato = "hls"
                soloHls.add(e.id)
                ajustes.soloHls = soloHls
                reintentos = 0
                cargar(nuevo = false)
                txtEstado.text = "Este canal no se lee en TS: pasando a HLS…"
                return
            }
            recuperar("Error " + error.errorCodeName)
        }
    }

    // ---------------------------------------------------------------- pantalla

    private fun texto(sp: Float, color: Int, negrita: Boolean): TextView = Estilo.texto(this, sp, color, negrita)

    private fun construirVista() {
        val raiz = FrameLayout(this)
        raiz.setBackgroundColor(Color.BLACK)

        vista = PlayerView(this)
        vista.useController = false
        vista.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        vista.setKeepContentOnPlayerReset(false)
        vista.isFocusable = false
        raiz.addView(vista, FrameLayout.LayoutParams(-1, -1))

        panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(64, 40, 64, 48)
        panel.background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0xE6000000.toInt(), 0x00000000))
        txtNumero = texto(16f, Estilo.ACENTO, true)
        txtTitulo = texto(30f, Color.WHITE, true)
        txtEstado = texto(18f, 0xFFC9D1DB.toInt(), false)
        txtAyuda = texto(14f, 0xFF8E9AAB.toInt(), false)
        panel.addView(txtNumero)
        panel.addView(txtTitulo)
        panel.addView(txtEstado)
        panel.addView(txtAyuda)
        raiz.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        construirControles()

        txtZap = texto(26f, Color.WHITE, true)
        txtZap.setPadding(36, 20, 36, 20)
        txtZap.background = Estilo.redondo(0xC8000000.toInt(), 24f)
        txtZap.visibility = View.GONE
        val zl = FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END)
        zl.setMargins(0, 48, 64, 0)
        raiz.addView(txtZap, zl)

        setContentView(raiz)
        if (!esTele) prepararTactil()
    }

    // ---------------------------------------------------------------- controles (OK una vez)

    private fun boton(txt: String, accion: () -> Unit): TextView = TextView(this).apply {
        text = txt; textSize = 17f; setTextColor(Estilo.TEXTO); gravity = Gravity.CENTER; typeface = android.graphics.Typeface.DEFAULT_BOLD
        setPadding(Estilo.dp(context, 18), Estilo.dp(context, 10), Estilo.dp(context, 18), Estilo.dp(context, 10))
        isFocusable = true; isClickable = true
        val normal = GradientDrawable().apply { setColor(0xCC19212D.toInt()); cornerRadius = 999f; setStroke(Estilo.dp(context, 2), Estilo.LINEA) }
        val foco = GradientDrawable().apply { setColor(Estilo.ACENTO); cornerRadius = 999f; setStroke(Estilo.dp(context, 2), Estilo.ACENTO) }
        background = android.graphics.drawable.StateListDrawable().apply { addState(intArrayOf(android.R.attr.state_focused), foco); addState(intArrayOf(), normal) }
        onFocusChangeListener = View.OnFocusChangeListener { v, f -> (v as TextView).setTextColor(if (f) Estilo.FONDO else Estilo.TEXTO) }
        setSingleLine(true); maxLines = 1
        setOnClickListener { accion(); programarOcultarControles() }
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = Estilo.dp(context, 8) }
    }

    private fun construirControles() {
        val d = Estilo.dp(this, 1)
        controles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(0, 12 * d, 0, 0) }
        // Estado respecto al directo y barra
        val fila0 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        txtDirecto = texto(17f, Estilo.BIEN, true)
        barraFondo = FrameLayout(this).apply { background = Estilo.redondo(0x44FFFFFF, 99f) }
        barraRelleno = View(this).apply { background = Estilo.redondo(Estilo.ACENTO, 99f) }
        barraFondo.addView(barraRelleno, FrameLayout.LayoutParams(0, -1))
        controles.addView(txtDirecto, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = 8 * d })
        controles.addView(barraFondo, LinearLayout.LayoutParams(-1, 6 * d).apply { bottomMargin = 14 * d })

        val fila1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fila1.addView(boton("◀◀  10 s") { saltarEnDirecto(-10_000) })
        btnPausa = boton("❚❚  Pausa") { alternarPausa() }
        fila1.addView(btnPausa)
        fila1.addView(boton("10 s  ▶▶") { saltarEnDirecto(10_000) })
        btnDirecto = boton("●  Directo") { volverAlDirecto() }
        fila1.addView(btnDirecto)
        btnAjustes = boton("☰  Reproducción  ›") { abrirLateral() }
        fila1.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        fila1.addView(btnAjustes)
        controles.addView(fila1, LinearLayout.LayoutParams(-1, -2))
        panel.addView(controles)

        // Panel lateral con los ajustes de la emisión: una lista de filas, cada una con su valor actual
        val ancho = (resources.displayMetrics.widthPixels * 0.34f).toInt()
        lateral = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; visibility = View.GONE
            setPadding(22 * d, 30 * d, 22 * d, 30 * d)
            setBackgroundColor(0xF20D141E.toInt())
        }
        lateral.addView(texto(24f, Estilo.TEXTO, true).apply { text = "Reproducción"; setPadding(0, 0, 0, 12 * d) })
        val filas = ArrayList<Pair<View, () -> Boolean>>()
        val valores = ArrayList<Pair<TextView, () -> String>>()
        fun fila(icono: String, nombre: String, valor: () -> String, visible: () -> Boolean = { true }, accion: () -> Unit) {
            val v = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(14 * d, 10 * d, 14 * d, 10 * d)
                isFocusable = true; isClickable = true
                val normal = GradientDrawable().apply { setColor(0); cornerRadius = 14f * d; setStroke(2 * d, 0) }
                val foco = GradientDrawable().apply { setColor(0xFF27313F.toInt()); cornerRadius = 14f * d; setStroke(2 * d, Estilo.ACENTO) }
                background = android.graphics.drawable.StateListDrawable().apply { addState(intArrayOf(android.R.attr.state_focused), foco); addState(intArrayOf(), normal) }
                setOnClickListener { ui.removeCallbacks(ocultarControlesR); accion(); ui.postDelayed({ refrescarFilas(); programarOcultarControles() }, 1200) }
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 4 * d }
            }
            v.addView(texto(20f, Estilo.APAGADO, true).apply { text = icono; minWidth = 34 * d })
            v.addView(texto(19f, Estilo.TEXTO, false).apply { text = nombre }, LinearLayout.LayoutParams(0, -2, 1f))
            val t = texto(17f, Estilo.ACENTO, false).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; maxWidth = (ancho * 0.45f).toInt() }
            v.addView(t)
            lateral.addView(v)
            filas.add(v to visible)
            valores.add(t to valor)
        }
        fila("♪", "Sonido", { pistaActual(C.TRACK_TYPE_AUDIO) }) { menuPistas(C.TRACK_TYPE_AUDIO) }
        fila("▤", "Subtítulos", { pistaActual(C.TRACK_TYPE_TEXT) }) { menuPistas(C.TRACK_TYPE_TEXT) }
        fila("▦", "Calidad", { calidadActual() }, { player.currentTracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.length > 1 } }) { menuCalidad() }
        fila("⇄", "Enlace", { "${fuente + 1} de ${fuentes.size}" }, { fuentes.size > 1 }) { menuEnlaces() }
        fila("◧", "Formato", { if (formato == "hls") "HLS" else "TS" }, { fuentes.getOrNull(fuente)?.directo == false }) { alternarFormato() }
        fila("⛶", "Pantalla", { listOf("Ajustada", "Ampliada", "Estirada")[modoPantalla] }) { cambiarPantalla() }
        lateral.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        lateral.addView(texto(14f, Estilo.APAGADO, false).apply { text = "▲▼ elegir  ·  OK cambiar  ·  ◀ o Atrás: encoger el panel" })
        botonesOpcionales = filas
        filasValores = valores
        anchoLateral = ancho
        (panel.parent as FrameLayout).addView(lateral, FrameLayout.LayoutParams(ancho, -1, Gravity.END))
    }

    private fun pistaActual(tipo: Int): String {
        if (tipo == C.TRACK_TYPE_TEXT && player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) return "Sin"
        val g = player.currentTracks.groups.firstOrNull { it.type == tipo && it.isSelected } ?: return if (tipo == C.TRACK_TYPE_TEXT) "Sin" else "—"
        val f = g.getTrackFormat(0)
        return f.label ?: f.language?.let { Locale(it).getDisplayLanguage(Locale("es", "ES")).replaceFirstChar { c -> c.uppercase() } } ?: "Pista"
    }

    private fun calidadActual(): String {
        val g = player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.length > 1 } ?: return "—"
        val forzada = player.trackSelectionParameters.overrides.keys.any { it == g.mediaTrackGroup }
        if (!forzada) return "Automática"
        val i = (0 until g.length).firstOrNull { g.isTrackSelected(it) } ?: return "Automática"
        return g.getTrackFormat(i).height.takeIf { it > 0 }?.let { "${it}p" } ?: "Fijada"
    }

    private fun refrescarFilas() {
        for ((v, visible) in botonesOpcionales) v.visibility = if (visible()) View.VISIBLE else View.GONE
        for ((t, valor) in filasValores) t.text = valor()
    }

    private fun mostrarControles() {
        if (!listo || esVideo()) return
        controlesVisibles = true
        txtAyuda.visibility = View.GONE
        controles.visibility = View.VISIBLE
        lateral.visibility = View.GONE
        lateralAbierto = false
        panel.setPadding(64, 40, 64, 48)
        refrescarFilas()
        mostrarPanel(0)
        actualizarControles()
        btnPausa.requestFocus()
        programarOcultarControles()
    }

    private fun ocultarControles() {
        ui.removeCallbacks(ocultarControlesR)
        if (!controlesVisibles) return
        controlesVisibles = false
        controles.visibility = View.GONE
        lateral.visibility = View.GONE
        lateralAbierto = false
        panel.setPadding(64, 40, 64, 48)
        txtAyuda.visibility = View.VISIBLE
        mostrarPanel(3000)
    }

    /** El panel de ajustes sale encogido (solo el botón) y se extiende por el lateral al pulsarlo. */
    private fun abrirLateral() {
        refrescarFilas()
        lateralAbierto = true
        lateral.visibility = View.VISIBLE
        panel.setPadding(64, 40, anchoLateral + 48, 48)
        (1 until lateral.childCount).map { lateral.getChildAt(it) }.firstOrNull { it.isFocusable && it.visibility == View.VISIBLE }?.requestFocus()
    }

    private fun cerrarLateral() {
        lateralAbierto = false
        lateral.visibility = View.GONE
        panel.setPadding(64, 40, 64, 48)
        btnAjustes.requestFocus()
    }

    private fun programarOcultarControles() {
        ui.removeCallbacks(ocultarControlesR)
        if (controlesVisibles) ui.postDelayed(ocultarControlesR, 10000)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (controlesVisibles && event.action == KeyEvent.ACTION_DOWN) programarOcultarControles()
        return super.dispatchKeyEvent(event)
    }

    /** Cuánto se ha quedado atrás del directo: en HLS lo dice el reproductor; en TS se cuenta lo que ha estado en pausa. */
    private fun retrasoActual(): Long {
        val off = player.currentLiveOffset
        return if (player.isCurrentMediaItemLive && off != C.TIME_UNSET) (off - 13_000L).coerceAtLeast(0L) else retrasoMs
    }

    private fun actualizarControles() {
        if (!controlesVisibles) return
        val retraso = retrasoActual()
        val pausado = !player.playWhenReady
        btnPausa.text = if (pausado) "▶  Reanudar" else "❚❚  Pausa"
        if (retraso > 4000 || pausado) {
            txtDirecto.text = (if (pausado) "❚❚  EN PAUSA" else "◀◀  EN DIFERIDO") + (if (retraso > 1000) "  -" + hms(retraso) else "")
            txtDirecto.setTextColor(Estilo.AVISO)
        } else {
            txtDirecto.text = "●  EN DIRECTO"
            txtDirecto.setTextColor(Estilo.BIEN)
        }
        val fraccion = 1f - (retraso.coerceAtMost(60_000L) / 60_000f)
        barraFondo.post {
            val lp = barraRelleno.layoutParams
            lp.width = (barraFondo.width * fraccion).toInt()
            barraRelleno.layoutParams = lp
        }
    }

    private fun alternarPausa() {
        if (player.playWhenReady) player.pause() else player.play()
        actualizarControles()
    }

    /** Atrás o adelante unos segundos: solo si el formato lo permite (HLS); en TS no hay nada detrás que ver. */
    private fun saltarEnDirecto(ms: Long) {
        if (!player.isCurrentMediaItemSeekable) {
            txtDirecto.text = "TS no permite saltar: usa ◧ Formato (HLS)"
            txtDirecto.setTextColor(Estilo.AVISO)
            return
        }
        player.seekTo((player.currentPosition + ms).coerceAtLeast(0L))
        ui.postDelayed({ actualizarControles() }, 400)
    }

    private fun volverAlDirecto() {
        retrasoMs = 0
        if (player.isCurrentMediaItemLive && player.isCurrentMediaItemSeekable) {
            player.seekToDefaultPosition()
            player.play()
            ui.postDelayed({ actualizarControles() }, 400)
        } else {
            // Flujo continuo: lo único que hay es volver a pedir el canal en su punto actual
            recolocar()
            ocultarControles()
        }
    }

    private fun cambiarPantalla() {
        modoPantalla = (modoPantalla + 1) % 3
        vista.resizeMode = when (modoPantalla) {
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        txtDirecto.text = "⛶  Pantalla: " + listOf("ajustada", "ampliada (recorta los bordes)", "estirada")[modoPantalla]
        txtDirecto.setTextColor(Estilo.TEXTO)
        refrescarFilas()
    }

    /** Calidad de imagen cuando el canal trae varias (HLS adaptativo). */
    private fun menuCalidad() {
        val grupo = player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO && it.length > 1 }.maxByOrNull { it.length }
        if (grupo == null) { txtEstado.text = "Este canal solo trae una calidad"; mostrarPanel(3000); return }
        val textos = arrayListOf("Automática (la mejor que aguante)")
        val acciones = arrayListOf<() -> Unit>({ player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO).build() })
        val orden = (0 until grupo.length).sortedByDescending { grupo.getTrackFormat(it).height }
        val actual = if (player.trackSelectionParameters.overrides.keys.any { it == grupo.mediaTrackGroup }) orden.firstOrNull { grupo.isTrackSelected(it) } else null
        for (i in orden) {
            val f = grupo.getTrackFormat(i)
            val nombre = (if (f.height > 0) "${f.height}p" else "Calidad ${i + 1}") + (if (f.bitrate > 0) "  (${f.bitrate / 1000} kbps)" else "")
            textos.add((if (actual == i) "✓ " else "    ") + nombre)
            acciones.add { player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setOverrideForType(TrackSelectionOverride(grupo.mediaTrackGroup, i)).build() }
        }
        textos[0] = (if (actual == null) "✓ " else "    ") + textos[0]
        dialogo("Calidad de imagen", textos) { acciones[it]() }.show()
    }

    private fun ayuda(): String =
        if (esTele && esVideo()) "◀▶ ±10 s  ·  ▲▼ ±1 min  ·  OK pausa  ·  mantén OK: favorito  ·  ≡ opciones  ·  Atrás: salir"
        else if (esTele) "OK: controles (pausa, directo, sonido…)  ·  ≡ opciones del canal  ·  ▲▼ canal  ·  ◀▶ enlace  ·  Atrás: salir"
        else "Toca: controles  ·  Desliza ↑↓ canal  ·  ←→ enlace  ·  Mantén pulsado: opciones del canal"

    /** Móvil: gestos con el dedo en lugar del mando. */
    private fun prepararTactil() {
        gestos = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (controlesVisibles) ocultarControles() else if (esVideo()) { if (panel.alpha > 0.5f) ui.post(ocultarPanel) else mostrarPanel(5000) } else mostrarControles()
                return true
            }
            override fun onLongPress(e: MotionEvent) = opcionesCanal()
            override fun onFling(a: MotionEvent?, b: MotionEvent, vx: Float, vy: Float): Boolean {
                if (a == null) return false
                val dx = b.x - a.x
                val dy = b.y - a.y
                if (abs(dy) > abs(dx) && abs(dy) > 120) { zap(if (dy < 0) 1 else -1); return true }
                if (abs(dx) > abs(dy) && abs(dx) > 120) { cambiarFuente(if (dx < 0) 1 else -1); return true }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val g = gestos
        if (g != null && listo) g.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    /** Los vídeos de las carpetas del servidor (y las grabaciones) se ven como un vídeo normal: con pausa y saltos. */
    private fun esVideo(): Boolean = canales.getOrNull(indice)?.clave?.startsWith("carpeta|") == true

    private fun saltar(ms: Long) {
        val dur = player.duration
        val nueva = (player.currentPosition + ms).coerceAtLeast(0L)
        player.seekTo(if (dur != C.TIME_UNSET) nueva.coerceAtMost(dur) else nueva)
        mostrarPanel(4000)
        txtEstado.text = posicionTexto()
    }

    private fun hms(ms: Long): String {
        val t = ms / 1000
        return if (t >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", t / 3600, t / 60 % 60, t % 60) else String.format(Locale.ROOT, "%d:%02d", t / 60, t % 60)
    }

    private fun posicionTexto(): String {
        val dur = player.duration
        return hms(player.currentPosition) + (if (dur != C.TIME_UNSET) " / " + hms(dur) else "") + (if (!player.isPlaying) "   ⏸ en pausa" else "")
    }

    /** Cada segundo, en los vídeos, pone la posición en el panel. */
    private val relojVideo = object : Runnable {
        override fun run() {
            if (esVideo() && player.playbackState == Player.STATE_READY) txtEstado.text = posicionTexto()
            if (!player.playWhenReady && yaSonando && !esVideo()) retrasoMs += 1000
            if (controlesVisibles) actualizarControles()
            ui.postDelayed(this, 1000)
        }
    }

    private fun mostrarPanel(ms: Long) {
        panel.animate().cancel()
        panel.alpha = 1f
        ui.removeCallbacks(ocultarPanel)
        if (ms > 0) ui.postDelayed(ocultarPanel, ms)
    }

    private fun rotular() {
        val partes = ArrayList<String>()
        if (canales.size > 1) partes.add("${indice + 1} / ${canales.size}")
        if (fuentes.size > 1) partes.add("Enlace ${fuente + 1} de ${fuentes.size}")
        if (formato == "hls") partes.add("HLS")
        if (canales[indice].favorito) partes.add("★")
        if ((grabando[canales[indice].clave] ?: 0L) > System.currentTimeMillis()) partes.add("⏺ REC")
        txtNumero.text = partes.joinToString("   ·   ")
        txtTitulo.text = canales[indice].titulo
    }

    /** Línea técnica (en lugar de la ayuda, cuando el canal ya se ve): para saber de qué tipo es un corte. */
    private fun datosTecnicos(): String {
        val sb = StringBuilder(if (formato == "hls") "HLS" else "TS")
        val v = player.videoFormat
        if (v != null) {
            if (v.height > 0) sb.append(" · ").append(v.height).append("p")
            val m = when (val tipo = (v.sampleMimeType ?: "").replace("video/", "")) {
                "avc" -> "H.264"
                "hevc" -> "HEVC"
                "mpeg2" -> "MPEG-2"
                else -> tipo
            }
            if (m.isNotEmpty()) sb.append(" · ").append(m)
            if (v.frameRate > 0) sb.append(" · ").append(Math.round(v.frameRate)).append(" fps")
        }
        val a = player.audioFormat?.sampleMimeType
        if (a != null) sb.append(" · audio ").append(a.replace("audio/", "").replace("mp4a-latm", "AAC").replace("mpeg", "MP2").uppercase(Locale.ROOT))
        sb.append("   ·   ").append(if (esTele) "Mantén OK: opciones · OK×2: recolocar" else "Mantén pulsado: opciones")
        return sb.toString()
    }

    // ---------------------------------------------------------------- cargar un canal

    /**
     * Algo ha fallado o se ha quedado cargando. De menos a más:
     * 1) volver a conectar con la misma sesión del motor (lo más rápido, el motor sigue descargando),
     * 2) pedir el canal de nuevo al motor,
     * 3) pasar al siguiente enlace del canal.
     */
    /** Si el enlace va por la pasarela VPN del servidor y falla, se prueba directo (sin VPN) antes de rendirse. */
    private fun probarSinPasarela(motivo: String): Boolean {
        val e = fuentes.getOrNull(fuente) ?: return false
        if (!porVpnActual || e.id in sinPasarela) return false
        sinPasarela.add(e.id)
        reintentos = 0
        txtEstado.text = "$motivo · probando sin pasar por la VPN…"
        mostrarPanel(0)
        cargar(nuevo = false)
        return true
    }

    private fun recuperar(motivo: String) {
        ui.removeCallbacks(vigilante)
        if ((!yaSonando || reintentos >= 1) && probarSinPasarela(motivo)) return
        val g = generacion
        mostrarPanel(0)
        if (reintentos == 0 && player.currentMediaItem != null) {
            reintentos++
            txtEstado.text = "$motivo · reconectando…"
            ui.postDelayed({
                if (g == generacion) {
                    player.prepare()
                    player.playWhenReady = true
                    ui.postDelayed(vigilante, 25000)
                }
            }, 1500)
        } else if (reintentos <= 1) {
            reintentos = 2
            txtEstado.text = "$motivo · volviendo a pedir el canal…"
            ui.postDelayed({ if (g == generacion) cargar(nuevo = false) }, 2000)
        } else {
            siguienteFuente(motivo)
        }
    }

    /** El enlace actual no va: el siguiente del mismo canal; al acabarlos, otra vuelta tras una pausa. */
    private fun siguienteFuente(motivo: String) {
        ui.removeCallbacks(vigilante)
        if (probarSinPasarela(motivo)) return
        val g = generacion
        if (fuente + 1 < fuentes.size) {
            fuente++
            reintentos = 0
            cargar(nuevo = false)
            txtEstado.text = "$motivo · probando el enlace ${fuente + 1} de ${fuentes.size}…"
        } else if (vueltas + 1 < MAX_VUELTAS) {
            vueltas++
            txtEstado.text = "$motivo · se vuelve a intentar en unos segundos…"
            mostrarPanel(0)
            ui.postDelayed({
                if (g == generacion) {
                    fuente = 0
                    reintentos = 0
                    cargar(nuevo = false)
                }
            }, 8000)
        } else {
            txtEstado.text = motivo + (if (fuentes.size > 1) ". Probados los ${fuentes.size} enlaces" else "") +
                ". Prueba otro canal (" + (if (esTele) "▲▼" else "desliza") + ")."
            mostrarPanel(0)
        }
    }

    private fun cambiarFuente(paso: Int) {
        if (fuentes.size < 2) { mostrarPanel(5000); return }
        fuente = (fuente + paso + fuentes.size) % fuentes.size
        reintentos = 0
        vueltas = 0
        cargar(nuevo = false)
    }

    private fun reproducir(emision: Emision, hls: Boolean, mensaje: String, esperaMs: Long) {
        statUrl = emision.estado
        commandUrl = emision.orden
        val mi = MediaItem.Builder().setUri(Uri.parse(emision.url))
        if (hls) {
            mi.setMimeType(MimeTypes.APPLICATION_M3U8)
            // Unos segundos de colchón respecto al directo: los parones cortos del P2P no llegan a la imagen
            mi.setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(12000).build())
        }
        player.setMediaItem(mi.build())
        player.prepare()
        player.playWhenReady = true
        txtEstado.text = mensaje
        if (statUrl != null) ui.postDelayed(sondeo, 2000)
        ui.postDelayed(vigilante, esperaMs)
    }

    /** Pide el canal al motor y lo reproduce. Con «nuevo» se empieza por el primer enlace del canal. */
    private fun cargar(nuevo: Boolean) {
        val g = ++generacion
        if (nuevo) {
            reintentos = 0
            vueltas = 0
            fuentes = canales[indice].enlaces
            fuente = 0
        }
        val e = fuentes.getOrNull(fuente)
        // Cada enlace en su formato: el elegido en Ajustes, salvo los que ya se sabe que solo van en HLS
        if (e != null) formato = if (e.id in soloHls) "hls" else formatoElegido
        yaSonando = false
        retrasoMs = 0
        porVpnActual = false
        avisoVideo = false
        avisoAudio = false
        ui.removeCallbacks(vigilante)
        ui.removeCallbacks(sondeo)
        rotular()
        txtAyuda.text = ayuda()
        txtEstado.text = "Pidiendo el canal al motor…"
        mostrarPanel(0)
        val anterior = commandUrl
        statUrl = null
        commandUrl = null
        player.stop()
        player.clearMediaItems()
        if (e == null) {
            txtEstado.text = "Este canal no tiene enlaces."
            return
        }
        if (e.directo) {
            // Canal de TV normal por internet: sin motor de Ace Stream
            if (anterior != null) red.execute { parar(anterior) }
            val gen = generacion
            red.execute {
                // Si la VPN del servidor tiene pasarela y el ajuste lo permite, la emisión se pide por ella
                var url = e.url
                var porVpn = false
                try {
                    val quiere = CopiaLocal(applicationContext).leer()?.datos?.optJSONObject("ajustes")?.optBoolean("tvPorVpn", true) ?: true
                    if (quiere && equipo.isNotEmpty() && Regex("(?i)https?://.*").matches(url) && e.id !in sinPasarela && Servidor.salud(equipo, 2500).pasarela) { url = Servidor.porPasarela(equipo, url); porVpn = true }
                } catch (ex: Exception) { /* sin pasarela: directa */ }
                ui.post {
                    if (isFinishing || isDestroyed || gen != generacion) return@post
                    porVpnActual = porVpn
                    // Por la VPN se espera menos: si no arranca, se prueba directo enseguida
                    reproducir(Emision(url, null, null), hls = false, mensaje = if (porVpn) "Emisión en abierto (por la VPN)" else "Emisión en abierto", esperaMs = if (porVpn) 15000 else 30000)
                }
            }
            return
        }
        val pedirHls = formato == "hls"
        val ligera = Servidor.fueraDeCasa(equipo) && e.id !in aacFallido
        val convertido = e.id in soloAac || ligera
        red.execute {
            if (anterior != null) parar(anterior)
            var emision: Emision? = null
            var error: String? = null
            var sinMotor = false
            try {
                if (convertido) {
                    // Sonido convertido a AAC por el servidor de casa (fuera de casa, además, imagen reducida)
                    val r = Servidor.pedirJson(
                        "$casa/aac/abrir?id=${e.id}&kind=${if (e.kind == "infohash") "infohash" else "content_id"}${if (ligera) "&calidad=baja" else ""}",
                        60000, tolerante = true
                    )
                    if (r.has("lista")) emision = Emision(casa + r.getString("lista"), rebase(r.optString("statUrl", "")), rebase(r.optString("commandUrl", "")))
                    else error = r.optString("error", "").ifEmpty { "El servidor no puede convertir el sonido" }
                } else {
                    val ruta = if (pedirHls) "/ace/manifest.m3u8" else "/ace/getstream"
                    val parametros = if (e.kind == "infohash") arrayOf("infohash", "id") else arrayOf("id", "infohash")
                    for (param in parametros) {
                        val r = Servidor.pedirJson(
                            "$motor$ruta?format=json&$param=${e.id}&pid=${URLEncoder.encode(UUID.randomUUID().toString(), "UTF-8")}",
                            if (param == "id") 20000 else 40000, tolerante = true
                        )
                        val fallo = if (r.isNull("error")) "" else r.optString("error", "")
                        val resp = r.optJSONObject("response")
                        if (fallo.isEmpty() && resp != null) {
                            emision = Emision(rebase(resp.getString("playback_url")) ?: "", rebase(resp.optString("stat_url", "")), rebase(resp.optString("command_url", "")))
                            error = null
                            break
                        }
                        error = fallo.ifEmpty { "El motor no ha abierto el canal" }
                        if (fallo != "failed to load content") break
                    }
                    if (error == "failed to load content") error = "El canal no existe o ha caducado"
                }
            } catch (ex: Exception) {
                error = if (convertido) "El servidor de casa no responde" else "El motor de Ace Stream del servidor no responde"
                sinMotor = !convertido
            }
            val lista = emision
            val fallo = error
            ui.post {
                if (g != generacion) {
                    // Mientras tanto se ha cambiado de canal: esta emisión ya no hace falta
                    val orden = lista?.orden
                    if (orden != null && !red.isShutdown) red.execute { parar(orden) }
                    return@post
                }
                if (lista != null && lista.url.isNotEmpty()) {
                    if (convertido) reproducir(lista, hls = true, mensaje = if (ligera) "Calidad ligera (fuera de casa)…" else "Sonido convertido por el servidor…", esperaMs = 60000)
                    else reproducir(lista, hls = pedirHls, mensaje = "Conectando con los peers…", esperaMs = 45000)
                } else if (convertido) {
                    // Sin conversión: se vuelve al modo normal (se ve, aunque quizá sin sonido)
                    soloAac.remove(e.id)
                    ajustes.soloAac = soloAac
                    aacFallido.add(e.id)
                    cargar(nuevo = false)
                    avisoAudio = true
                    txtEstado.text = "${fallo ?: "No se pudo convertir"}. Se abre sin convertir el sonido."
                } else if (sinMotor) {
                    // Sin motor no tiene sentido probar otro enlace
                    txtEstado.text = fallo ?: "El motor no responde"
                    mostrarPanel(0)
                } else {
                    siguienteFuente(fallo ?: "No se pudo abrir el enlace")
                }
            }
        }
    }

    /** Las direcciones que devuelve el motor vienen con 127.0.0.1:6878; se apuntan al servidor de casa. */
    private fun rebase(url: String): String? {
        if (url.isEmpty()) return null
        return try {
            val u = URL(url)
            motor + u.path + (if (u.query != null) "?" + u.query else "")
        } catch (e: Exception) {
            url
        }
    }

    private fun parar(orden: String) {
        try {
            Servidor.pedir(orden + (if (orden.contains("?")) "&" else "?") + "method=stop", 5000, tolerante = true)
        } catch (e: Exception) {
            // el motor la cerrará solo al cabo de un rato
        }
    }

    private fun consultarEstado() {
        val url = statUrl ?: return
        val g = generacion
        red.execute {
            try {
                val resp = Servidor.pedirJson(url, 5000, tolerante = true).optJSONObject("response") ?: return@execute
                val st = resp.optString("status", "")
                val peers = resp.optInt("peers", -1)
                val vel = resp.optInt("speed_down", -1)
                val sb = StringBuilder(if (st == "dl") "Reproduciendo" else if (st == "prebuf") "Cargando" else "Conectando")
                if (peers >= 0) sb.append(" · ").append(peers).append(if (peers == 1) " peer" else " peers")
                if (vel >= 0) sb.append(" · ").append(if (vel >= 1024) String.format(Locale.ROOT, "%.1f MB/s", vel / 1024f) else "$vel KB/s")
                val txt = sb.toString()
                ui.post {
                    if (g == generacion && player.playerError == null) {
                        val bufer = player.totalBufferedDuration / 1000
                        txtEstado.text = if (bufer > 0) "$txt · búfer $bufer s" else txt
                        if (yaSonando) txtAyuda.text = datosTecnicos()
                    }
                }
            } catch (e: Exception) {
                // se reintenta en el siguiente ciclo
            }
        }
    }

    private fun audioNoCompatible(mime: String?) {
        val e = fuentes.getOrNull(fuente) ?: return
        if (avisoAudio) return
        avisoAudio = true
        val tipo = (mime ?: "desconocido").replace("audio/", "").uppercase(Locale.ROOT)
        if (!e.directo && e.id !in soloAac && e.id !in aacFallido) {
            soloAac.add(e.id)
            ajustes.soloAac = soloAac
            reintentos = 0
            cargar(nuevo = false)
            txtEstado.text = "La tele no reproduce el sonido de este canal ($tipo): pasándolo por el servidor…"
        } else {
            txtEstado.text = "Se ve, pero esta tele no reproduce el sonido de este canal ($tipo). Prueba otro enlace."
        }
        mostrarPanel(0)
    }

    private fun videoNoCompatible(mime: String?) {
        if (avisoVideo) return
        avisoVideo = true
        val tipo = (mime ?: "desconocido").replace("video/", "").uppercase(Locale.ROOT)
        if (fuentes.size > 1) {
            siguienteFuente("La tele no puede mostrar el vídeo de este enlace ($tipo)")
        } else {
            txtEstado.text = "Se oye, pero esta tele no puede mostrar el vídeo de este canal ($tipo)."
            mostrarPanel(0)
        }
    }

    /** Recolocar a mano (OK dos veces): reconecta con la misma sesión, por si la imagen se ha quedado congelada. */
    private fun recolocar() {
        if (player.currentMediaItem == null) return
        retrasoMs = 0
        txtEstado.text = "Recolocando la imagen…"
        mostrarPanel(2500)
        player.stop()
        player.prepare()
        player.playWhenReady = true
    }

    /** Zapping: se espera un instante por si se pulsan varias teclas seguidas. */
    private fun zap(paso: Int) {
        if (canales.size < 2) return
        indice = ((indice + paso) % canales.size + canales.size) % canales.size
        Sesion.indice = indice
        fuentes = canales[indice].enlaces
        fuente = 0
        rotular()
        txtZap.text = "${indice + 1}   ${canales[indice].titulo}"
        txtZap.visibility = View.VISIBLE
        ui.removeCallbacks(ocultarZap)
        ui.postDelayed(ocultarZap, 1500)
        mostrarPanel(0)
        cargaPendiente?.let { ui.removeCallbacks(it) }
        val r = Runnable { cargar(nuevo = true) }
        cargaPendiente = r
        ui.postDelayed(r, 700)
    }

    // ---------------------------------------------------------------- opciones

    private fun dialogo(titulo: String, textos: List<String>, alElegir: (Int) -> Unit): DialogoVista =
        DialogoVista(this, titulo, textos, alElegir)

    /** ≡ (o mantener OK): opciones del canal. Lo de la emisión (pausa, sonido, calidad…) está en los controles de OK. */
    private fun opcionesCanal() {
        if (!listo) return
        ocultarControles()
        mostrarPanel(8000)
        val canal = canales[indice]
        val textos = ArrayList<String>()
        val acciones = ArrayList<() -> Unit>()
        textos.add(if (canal.favorito) "☆  Quitar de favoritos" else "★  Añadir a favoritos")
        acciones.add { alternarFavorito(canal) }
        textos.add("⏺  Grabar este canal en el servidor…")
        acciones.add { menuGrabar(canal) }
        if (canales.size > 1) {
            textos.add("☰  Ir a otro canal de la lista…")
            acciones.add { menuCanales() }
        }
        textos.add("⟳  Volver a cargar el canal")
        acciones.add {
            reintentos = 0
            vueltas = 0
            cargar(nuevo = false)
        }
        textos.add("▣  Recolocar la imagen (si se queda congelada)")
        acciones.add { recolocar() }
        textos.add("ⓘ  Datos técnicos")
        acciones.add { txtAyuda.text = datosTecnicos(); mostrarPanel(8000) }
        textos.add("←  Salir a la lista de canales")
        acciones.add { finish() }
        DialogoVista(this, "Opciones del canal", textos, { acciones[it]() }, canal.titulo).show()
    }

    /** Sonido y subtítulos, en dos pasos: primero qué cambiar y luego la pista. */
    private fun menuSonido() {
        val grupos = player.currentTracks.groups
        val nAudio = grupos.count { it.type == C.TRACK_TYPE_AUDIO }
        val nSubs = grupos.count { it.type == C.TRACK_TYPE_TEXT }
        dialogo("Sonido y subtítulos", listOf("♪  Pista de audio ($nAudio)", "▤  Subtítulos ($nSubs)")) { menuPistas(if (it == 0) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT) }.show()
    }

    private fun menuPistas(tipo: Int) {
        val grupos = player.currentTracks.groups.filter { it.type == tipo }
        if (grupos.isEmpty()) {
            txtEstado.text = if (tipo == C.TRACK_TYPE_TEXT) "Este canal no trae subtítulos" else "Este canal no trae pistas de audio"
            mostrarPanel(4000)
            return
        }
        val textos = ArrayList<String>()
        val acciones = ArrayList<() -> Unit>()
        val base = { player.trackSelectionParameters.buildUpon() }
        if (tipo == C.TRACK_TYPE_TEXT) {
            val sin = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)
            textos.add((if (sin) "✓ " else "    ") + "Sin subtítulos")
            acciones.add { player.trackSelectionParameters = base().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build() }
        }
        for ((i, g) in grupos.withIndex()) {
            val f = g.getTrackFormat(0)
            val idioma = f.language?.let { Locale(it).getDisplayLanguage(Locale("es", "ES")).replaceFirstChar { c -> c.uppercase() } }
            val nombre = f.label ?: idioma ?: "Pista ${i + 1}"
            val detalle = listOfNotNull(
                if (tipo == C.TRACK_TYPE_AUDIO && f.channelCount > 0) "${f.channelCount} canales" else null,
                f.sampleMimeType?.substringAfter('/')?.uppercase(),
            ).joinToString(" · ")
            textos.add((if (g.isSelected) "✓ " else "    ") + nombre + if (detalle.isNotEmpty()) "  ($detalle)" else "")
            acciones.add {
                player.trackSelectionParameters = base().setTrackTypeDisabled(tipo, false)
                    .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0)).build()
            }
        }
        dialogo(if (tipo == C.TRACK_TYPE_TEXT) "Subtítulos" else "Pista de audio", textos) { acciones[it]() }.show()
    }

    private fun menuGrabar(canal: Canal) {
        val minutos = listOf(30, 60, 90, 120, 180)
        dialogo("Grabar ${canal.titulo}", minutos.map { "$it minutos" }) { i ->
            Thread {
                var error = ""
                try { Servidor.grabar(equipo, canal, minutos[i]) } catch (e: Exception) { error = e.message ?: "sin conexión con el servidor" }
                ui.post {
                    if (isFinishing || isDestroyed) return@post
                    if (error.isEmpty()) { grabando[canal.clave] = System.currentTimeMillis() + minutos[i] * 60_000L; rotular() }
                    txtEstado.text = if (error.isEmpty()) "⏺ Grabando ${minutos[i]} min en el servidor (míralo en Vídeos › Grabaciones)" else "No se ha podido grabar: $error"
                    mostrarPanel(5000)
                }
            }.start()
        }.show()
    }

    private fun menuEnlaces() {
        val textos = fuentes.mapIndexed { i, e ->
            val estado = when {
                e.directo -> "en abierto"
                e.estado == "alive" -> "funcionaba · ${e.peers} peers"
                e.estado.isEmpty() -> "sin comprobar"
                else -> "caído la última vez"
            }
            (if (i == fuente) "✓ " else "    ") + "Enlace ${i + 1} · $estado"
        }
        dialogo("Enlaces de ${canales[indice].titulo}", textos) {
            if (it != fuente) {
                fuente = it
                reintentos = 0
                vueltas = 0
                cargar(nuevo = false)
            }
        }.show()
    }

    private fun menuCanales() {
        val textos = canales.mapIndexed { i, c -> (if (i == indice) "▶ " else "") + (if (c.favorito) "★ " else "") + c.titulo }
        val d = dialogo("Canales", textos) { if (it != indice) zap(it - indice) }
        d.seleccionar(indice)
        d.show()
    }

    /** Cambia entre flujo continuo (TS) y troceado (HLS) y lo deja elegido para los demás canales. */
    private fun alternarFormato() {
        formatoElegido = if (formato == "hls") "ts" else "hls"
        ajustes.formato = formatoElegido
        val e = fuentes.getOrNull(fuente)
        if (e != null && formatoElegido == "ts" && soloHls.remove(e.id)) ajustes.soloHls = soloHls
        reintentos = 0
        cargar(nuevo = false)
        txtEstado.text = "Probando en formato " + (if (formatoElegido == "hls") "HLS (troceado)" else "TS (continuo)") + "…"
    }

    private fun alternarFavorito(canal: Canal) {
        val poner = !canal.favorito
        Thread {
            var error = ""
            try { Servidor.favorito(equipo, canal, poner) } catch (e: Exception) { error = e.message ?: "sin conexión con el servidor" }
            ui.post {
                if (isFinishing || isDestroyed) return@post
                if (error.isEmpty()) {
                    canal.favorito = poner
                    rotular()
                    txtEstado.text = if (poner) "★ Añadido a favoritos" else "Quitado de favoritos"
                } else {
                    txtEstado.text = "No se ha podido cambiar el favorito: $error"
                }
                mostrarPanel(4000)
            }
        }.start()
    }

    // ---------------------------------------------------------------- mando

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!listo) return super.onKeyDown(keyCode, event)
        // Con los controles a la vista, las flechas y OK son del foco (mover entre botones y pulsar): se dejan pasar
        if (controlesVisibles && keyCode in setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER)) {
            programarOcultarControles()
            // Con el panel abierto, ◀ lo vuelve a encoger
            if (lateralAbierto && keyCode == KeyEvent.KEYCODE_DPAD_LEFT) { cerrarLateral(); return true }
            return false
        }
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (lateralAbierto) cerrarLateral() else if (controlesVisibles) ocultarControles() else return super.onKeyDown(keyCode, event)
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (controlesVisibles) { programarOcultarControles(); return true }
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> if (esVideo()) saltar(60_000) else zap(-1)
                    KeyEvent.KEYCODE_DPAD_DOWN -> if (esVideo()) saltar(-60_000) else zap(1)
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (esVideo()) saltar(-10_000) else cambiarFuente(-1)
                    else -> if (esVideo()) saltar(10_000) else cambiarFuente(1)
                }
            }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> zap(-1)
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_MEDIA_NEXT -> zap(1)
            KeyEvent.KEYCODE_MEDIA_REWIND -> if (esVideo()) saltar(-10_000) else { mostrarControles(); saltarEnDirecto(-10_000) }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> if (esVideo()) saltar(10_000) else { mostrarControles(); saltarEnDirecto(10_000) }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                if (player.playWhenReady) player.pause() else player.play()
                if (controlesVisibles) actualizarControles() else mostrarPanel(3000)
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                // Mantener OK pulsado: opciones (muchos mandos no tienen tecla Menú)
                if (event.repeatCount == 3) {
                    ultimoOk = 0
                    // Mantener OK: favorito u opciones (Ajustes); la tecla ≡ abre siempre las opciones
                    if (ajustes.okLargo == "opciones") opcionesCanal() else alternarFavorito(canales[indice])
                } else if (event.repeatCount == 0 && esVideo()) {
                    if (player.isPlaying) player.pause() else player.play()
                    mostrarPanel(4000)
                } else if (event.repeatCount == 0) {
                    mostrarControles()
                }
            }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> opcionesCanal()
            KeyEvent.KEYCODE_INFO -> mostrarPanel(5000)
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    override fun onStop() {
        super.onStop()
        // Al salir (Atrás o botón de inicio) se cierra el canal y se libera el motor
        if (!isChangingConfigurations) finish()
    }

    override fun onDestroy() {
        generacion++
        ui.removeCallbacksAndMessages(null)
        val orden = commandUrl
        if (orden != null) red.execute { parar(orden) }
        red.shutdown()
        if (listo) {
            listo = false
            player.release()
        }
        super.onDestroy()
    }
}
