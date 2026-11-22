package es.locometopa.beagle

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings

/** Lo que la app recuerda entre un día y otro. */
class Ajustes(private val contexto: Context) {
    private val sp: SharedPreferences = contexto.getSharedPreferences("beagle", Context.MODE_PRIVATE)

    /** Nombre o IP del servidor de casa (sin http ni puerto). Vacío si aún no se ha puesto. */
    var servidor: String
        get() = sp.getString("servidor", "") ?: ""
        set(v) = sp.edit().putString("servidor", v).apply()

    /** Cómo se pide el canal al motor: «ts» (continuo) o «hls» (troceado). */
    var formato: String
        get() = if (sp.getString("formato", "hls") == "ts") "ts" else "hls"
        set(v) = sp.edit().putString("formato", v).apply()

    /** Duración de las películas que se ven con el sonido convertido (el servidor no la da en ese modo). */
    fun duracion(clave: String): Long = sp.getLong("d_" + clave.hashCode(), 0L)
    fun ponerDuracion(clave: String, ms: Long) { sp.edit().putLong("d_" + clave.hashCode(), ms).apply() }

    /** Fiabilidad de cada enlace (sube si aguanta, baja si falla): los mejores se prueban primero. */
    fun puntos(id: String): Int = sp.getInt("p_$id", 0)
    fun puntuar(id: String, delta: Int) { sp.edit().putInt("p_$id", (puntos(id) + delta).coerceIn(-6, 6)).apply() }

    /** Segundos de colchón respecto al directo en HLS para este enlace (sube solo si corta mucho). */
    fun colchon(id: String): Int = sp.getInt("c_$id", 12)
    fun ponerColchon(id: String, seg: Int) { sp.edit().putInt("c_$id", seg.coerceIn(12, 30)).apply() }

    /** Última sección abierta: la app vuelve a ella al arrancar. */
    var ultimaSeccion: String
        get() = sp.getString("ultimaSeccion", "") ?: ""
        set(v) = sp.edit().putString("ultimaSeccion", v).apply()

    /** Enlaces que esta tele no lee en TS: se abren directamente en HLS. */
    var soloHls: Set<String>
        get() = HashSet(sp.getStringSet("soloHls", emptySet()) ?: emptySet())
        set(v) = sp.edit().putStringSet("soloHls", HashSet(v)).apply()

    /** Enlaces cuyo sonido esta tele no reproduce: se piden al servidor con el sonido convertido. */
    var soloAac: Set<String>
        get() = HashSet(sp.getStringSet("soloAac", emptySet()) ?: emptySet())
        set(v) = sp.edit().putStringSet("soloAac", HashSet(v)).apply()

    /** Si la tele guarda solo los canales de listas encendidas (HomeTV: `sincroLigero`). */
    var sincroLigero: Boolean
        get() = sp.getBoolean("sincroLigero", true)
        set(v) = sp.edit().putBoolean("sincroLigero", v).apply()

    /** Con qué se ve un canal: Beagle u otra app (VLC, Ace Player, AceServe). */
    var reproductor: Reproductor
        get() = Reproductor.de(sp.getString("reproductor", "interno") ?: "interno")
        set(v) = sp.edit().putString("reproductor", v.clave).apply()

    /** Claves de los últimos canales vistos, el más reciente primero (como `vistos` en HomeTV). */
    var vistos: List<String>
        get() = (sp.getString("vistos", "") ?: "").split('\n').filter { it.isNotEmpty() }
        set(v) = sp.edit().putString("vistos", v.joinToString("\n")).apply()

    /** Apunta un canal como visto: sube al principio y se quedan los 12 últimos. */
    fun verCanal(clave: String) {
        vistos = (listOf(clave) + vistos.filter { it != clave }).take(12)
    }

    /** Vídeo de entrada al abrir la app (el `/intro.mp4` del servidor). Apagado de serie: es para pruebas. */
    var intro: Boolean
        get() = sp.getBoolean("intro", false)
        set(v) = sp.edit().putBoolean("intro", v).apply()

    /** Ocultar en «Todos» los canales caídos (HomeTV: `hideDead`). */
    /** Panel del móvil (servidor en esta tele, puerto 8095): encendido, clave corta y puerto. */
    var movilActivo: Boolean
        get() = sp.getBoolean("movilActivo", false)
        set(v) = sp.edit().putBoolean("movilActivo", v).apply()

    var movilToken: String
        get() = sp.getString("movilToken", "") ?: ""
        set(v) = sp.edit().putString("movilToken", v).apply()

    var movilPuerto: Int
        get() = sp.getInt("movilPuerto", 8095)
        set(v) = sp.edit().putInt("movilPuerto", v).apply()

    var logos: Boolean
        get() = sp.getBoolean("logos", true)
        set(v) = sp.edit().putBoolean("logos", v).apply()

    /** Canales en cuadrícula (si no, en lista). */
    var cuadricula: Boolean
        get() = sp.getBoolean("cuadricula", false)
        set(v) = sp.edit().putBoolean("cuadricula", v).apply()

    /** OK sobre un canal abre su ficha (opciones) en vez de reproducir. */
    var okFicha: Boolean
        get() = sp.getBoolean("okFicha", false)
        set(v) = sp.edit().putBoolean("okFicha", v).apply()

    /** Qué hace mantener OK pulsado en un canal: «favorito» o «opciones». */
    var okLargo: String
        get() = if (sp.getString("okLargo", "favorito") == "opciones") "opciones" else "favorito"
        set(v) = sp.edit().putString("okLargo", v).apply()

    /** Al abrir la app, el servidor vuelve a bajar las listas que vienen de una dirección web. */
    var actualizarListas: Boolean
        get() = sp.getBoolean("actualizarListas", false)
        set(v) = sp.edit().putBoolean("actualizarListas", v).apply()

    var comprobarAlAbrir: Boolean
        get() = sp.getBoolean("comprobarAlAbrir", false)
        set(v) = sp.edit().putBoolean("comprobarAlAbrir", v).apply()

    /** Orden de la lista de canales: 0 mejores, 1 con partido primero, 2 por categoría, 3 A-Z, 4 más peers. */
    var orden: Int
        get() = sp.getInt("orden", 0).coerceIn(0, 4)
        set(v) = sp.edit().putInt("orden", v).apply()

    /** Horas que tienen que pasar para volver a comprobar los canales al abrir la app. */
    var comprobarHoras: Int
        get() = sp.getInt("comprobarHoras", 12)
        set(v) = sp.edit().putInt("comprobarHoras", v).apply()

    var ultimaComprobacion: Long
        get() = sp.getLong("ultimaComprobacion", 0)
        set(v) = sp.edit().putLong("ultimaComprobacion", v).apply()

    /** Preparar (comprobar) los canales 15 min antes de un partido: «marcados», «todos» o «no». */
    var preComprobar: String
        get() = sp.getString("preComprobar", "marcados") ?: "marcados"
        set(v) = sp.edit().putString("preComprobar", v).apply()

    /** Cuadrados de eventos destacados en Inicio (0 = ninguno). */
    var destacados: Int
        get() = sp.getInt("destacados", 0)
        set(v) = sp.edit().putInt("destacados", v).apply()

    /** Filas por categoría de canales en Inicio. */
    var filasCategorias: Boolean
        get() = sp.getBoolean("filasCategorias", false)
        set(v) = sp.edit().putBoolean("filasCategorias", v).apply()

    var ocultarCaidos: Boolean
        get() = sp.getBoolean("ocultarCaidos", false)
        set(v) = sp.edit().putBoolean("ocultarCaidos", v).apply()

    /** Motor de Ace Stream: en el servidor de casa (false, lo normal) o en la propia tele (true, 127.0.0.1). */
    var motorEnTele: Boolean
        get() = sp.getBoolean("motorEnTele", false)
        set(v) = sp.edit().putBoolean("motorEnTele", v).apply()

    /** Dirección del motor según el ajuste, o null si hace falta servidor y aún no se ha puesto. */
    fun urlMotor(): String? = when {
        motorEnTele -> Motor.URL_TELE
        servidor.isNotEmpty() -> Motor.urlCasa(servidor)
        else -> null
    }

    /** Dónde están `version.txt` y `Beagle.apk` para actualizar (acaba en «/»). */
    /** De dónde mirar las actualizaciones: primero el servidor de casa (si hay y no se cambió la dirección), luego GitHub. */
    fun origenesActualizacion(): List<String> = buildList {
        if (servidor.isNotEmpty() && urlActualizaciones == Actualizador.URL_POR_DEFECTO) add(Servidor.casa(servidor) + "/beagle/")
        add(urlActualizaciones)
    }

    var urlActualizaciones: String
        get() = sp.getString("urlActualizaciones", Actualizador.URL_POR_DEFECTO) ?: Actualizador.URL_POR_DEFECTO
        set(v) = sp.edit().putString("urlActualizaciones", v).apply()

    /** Versión de esta app («0.2.0»). */
    fun version(): String = try {
        contexto.packageManager.getPackageInfo(contexto.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    /** Así se presenta esta tele al servidor. El id sale del aparato y no cambia aunque se reinstale la app. */
    fun identidad(): Identidad {
        val androidId = Settings.Secure.getString(contexto.contentResolver, Settings.Secure.ANDROID_ID).orEmpty().ifEmpty { "tele" }
        val modelo = Build.MODEL.orEmpty().ifEmpty { "Android" }
        return Identidad(androidId.take(12), "Beagle en $modelo", version())
    }
}
