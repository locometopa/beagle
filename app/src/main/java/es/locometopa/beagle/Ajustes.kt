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
