package es.locometopa.beagle

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** Con qué se ve un canal: el reproductor de Beagle u otra app de la tele. */
enum class Reproductor(val clave: String, val nombre: String) {
    Interno("interno", "Ver aquí (Beagle)"),
    AcePlayer("aceplayer", "Ace Player"),
    AceServe("aceserve", "AceServe"),
    Vlc("vlc", "VLC"),
    Elegir("elegir", "Elegir cada vez");

    companion object {
        fun de(clave: String): Reproductor = values().firstOrNull { it.clave == clave } ?: Interno
    }
}

/** Abrir un canal en VLC, Ace Player o AceServe, como hace HomeTV (`AceListNativoPlugin.abrir`). */
object Externos {
    private val ACESTREAM = listOf("org.acestream.media.atv", "org.acestream.media", "org.acestream.core.atv", "org.acestream.core", "org.acestream.live", "org.acestream.node")
    private val ACESERVE = listOf("org.free.aceserve", "com.jopsis.httpaceserveproxy")
    private val VLC = listOf("org.videolan.vlc", "org.videolan.vlc.betav7neon")

    /** Paquetes de cada app, para saber si está instalada o llevar a la tienda. */
    fun paquetes(r: Reproductor): List<String> = when (r) {
        Reproductor.AcePlayer -> ACESTREAM
        Reproductor.AceServe -> ACESERVE
        Reproductor.Vlc -> VLC
        else -> emptyList()
    }

    private fun instalado(contexto: Context, paquete: String): Boolean = try {
        contexto.packageManager.getPackageInfo(paquete, 0); true
    } catch (e: Exception) {
        false
    }

    /** El primer paquete instalado de esa app, o null si no está. */
    fun instalada(contexto: Context, r: Reproductor): String? = paquetes(r).firstOrNull { instalado(contexto, it) }

    /** Los reproductores que sirven para este canal: los externos de Ace Stream no valen para una dirección web directa. */
    fun disponibles(directo: Boolean): List<Reproductor> =
        if (directo) listOf(Reproductor.Interno, Reproductor.Vlc, Reproductor.Elegir) else Reproductor.values().toList()

    /** Dirección HTTP del canal en el motor (para VLC), o la propia dirección si es un canal web directo. */
    fun urlStream(ajustes: Ajustes, enlace: Enlace): String =
        if (enlace.directo) enlace.url else (ajustes.urlMotor() ?: Motor.URL_TELE) + "/ace/getstream?id=" + enlace.id

    /**
     * Abre el canal en [r]. Devuelve null si se ha abierto, o el motivo si no (p. ej. la app no está instalada).
     * Si falta la app y hay tienda, se abre la ficha de la tienda.
     */
    fun abrir(contexto: Context, ajustes: Ajustes, r: Reproductor, canal: Canal): String? {
        val enlace = canal.enlaces.firstOrNull() ?: return "El canal no tiene enlaces"
        // Un canal web no va por Ace Stream: se pasa a una app que lea direcciones
        val reproductor = if (enlace.directo && (r == Reproductor.AcePlayer || r == Reproductor.AceServe)) Reproductor.Elegir else r
        val intento: Intent
        var elegir = false
        when (reproductor) {
            Reproductor.AcePlayer -> {
                // API oficial de Ace Stream: «type 3» fuerza su reproductor interno
                intento = Intent("org.acestream.action.start_content")
                    .setData(Uri.parse("acestream:?content_id=${enlace.id}"))
                    .putExtra("org.acestream.EXTRA_SELECTED_PLAYER", "{\"type\": 3}")
            }
            Reproductor.AceServe -> {
                intento = Intent(Intent.ACTION_VIEW, Uri.parse("acestream://${enlace.id}"))
                val pkg = instalada(contexto, Reproductor.AceServe)
                if (pkg != null) intento.setPackage(pkg) else return faltaApp(contexto, Reproductor.AceServe)
            }
            Reproductor.Vlc -> {
                intento = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(urlStream(ajustes, enlace)), "video/*").putExtra("title", canal.titulo)
                val pkg = instalada(contexto, Reproductor.Vlc)
                if (pkg != null) intento.setPackage(pkg) else return faltaApp(contexto, Reproductor.Vlc)
            }
            else -> {
                elegir = true
                intento = if (enlace.directo) Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(enlace.url), "video/*")
                else Intent(Intent.ACTION_VIEW, Uri.parse("acestream://${enlace.id}"))
            }
        }
        intento.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            contexto.startActivity(if (elegir) Intent.createChooser(intento, "Abrir con…").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else intento)
            null
        } catch (e: ActivityNotFoundException) {
            if (reproductor == Reproductor.AcePlayer) {
                // Versiones antiguas de Ace Stream: enlace acestream:// normal
                try {
                    contexto.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("acestream://${enlace.id}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return null
                } catch (e2: ActivityNotFoundException) { /* sigue abajo */ }
                faltaApp(contexto, Reproductor.AcePlayer)
            } else "No hay ninguna app instalada que pueda abrirlo"
        }
    }

    /** Lleva a la ficha de la tienda de la app que falta. Devuelve el aviso para enseñar. */
    private fun faltaApp(contexto: Context, r: Reproductor): String {
        val pkg = paquetes(r).first()
        return try {
            contexto.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "${r.nombre} no está instalada: te llevo a la tienda"
        } catch (e: ActivityNotFoundException) {
            "${r.nombre} no está instalada y esta tele no tiene tienda de apps"
        }
    }

    /** Abre el canal con la app elegida en Ajustes y avisa si algo falla. Devuelve true si se ha abierto fuera de Beagle. */
    fun abrirSegunAjuste(contexto: Context, canal: Canal): Boolean {
        val ajustes = Ajustes(contexto.applicationContext)
        val r = ajustes.reproductor
        if (r == Reproductor.Interno) return false
        val enlace = canal.enlaces.firstOrNull()
        // Canal web con Ace Player/AceServe: no sirven, se usa el reproductor de Beagle
        if (enlace != null && enlace.directo && (r == Reproductor.AcePlayer || r == Reproductor.AceServe)) return false
        ajustes.verCanal(canal.clave)
        val fallo = abrir(contexto, ajustes, r, canal)
        if (fallo != null) Toast.makeText(contexto, fallo, Toast.LENGTH_LONG).show()
        return true
    }
}
