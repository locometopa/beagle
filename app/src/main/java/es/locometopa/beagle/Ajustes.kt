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

    /** Si la tele guarda solo los canales de listas encendidas (HomeTV: `sincroLigero`). */
    var sincroLigero: Boolean
        get() = sp.getBoolean("sincroLigero", true)
        set(v) = sp.edit().putBoolean("sincroLigero", v).apply()

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
