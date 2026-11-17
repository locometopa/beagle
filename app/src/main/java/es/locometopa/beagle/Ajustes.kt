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
        get() = if (sp.getString("formato", "ts") == "hls") "hls" else "ts"
        set(v) = sp.edit().putString("formato", v).apply()

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
    var comprobarAlAbrir: Boolean
        get() = sp.getBoolean("comprobarAlAbrir", false)
        set(v) = sp.edit().putBoolean("comprobarAlAbrir", v).apply()

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
