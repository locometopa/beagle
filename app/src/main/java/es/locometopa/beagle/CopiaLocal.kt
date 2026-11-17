package es.locometopa.beagle

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Lo que se sabe de los datos compartidos, para enseñarlo en pantalla. */
class Resumen(val canales: Int, val listas: Int, val listasEncendidas: Int, val favoritos: Int, val ligero: Boolean) {
    companion object {
        fun de(datos: JSONObject, ligero: Boolean): Resumen {
            val canales = datos.optJSONArray("channels")
            var favoritos = 0
            if (canales != null) for (i in 0 until canales.length()) if (canales.optJSONObject(i)?.optBoolean("favorite", false) == true) favoritos++
            val listas = datos.optJSONArray("sources")
            var encendidas = 0
            if (listas != null) for (i in 0 until listas.length()) if (listas.optJSONObject(i)?.optBoolean("enabled", true) != false) encendidas++
            return Resumen(canales?.length() ?: 0, listas?.length() ?: 0, encendidas, favoritos, ligero)
        }
    }
}

/** Los datos compartidos tal como los dio el servidor, con la `rev` que tenían. */
class Copia(val rev: Long, val datos: JSONObject, val ligero: Boolean) {
    val resumen: Resumen get() = Resumen.de(datos, ligero)
}

/**
 * Copia en la tele de lo compartido: la app abre al instante con ella y sigue funcionando si el servidor tarda
 * o no está. Se guarda el JSON entero (también los campos que Beagle aún no usa) para no perder nada al devolverlo.
 */
class CopiaLocal(private val contexto: Context) {
    private val archivo = File(contexto.filesDir, "sincro.json")

    fun leer(): Copia? = try {
        if (!archivo.exists()) null else {
            val j = JSONObject(archivo.readText(Charsets.UTF_8))
            Copia(j.optLong("rev", 0), j.getJSONObject("datos"), j.optBoolean("ligero", false))
        }
    } catch (e: Exception) {
        null // copia dañada: se pide de nuevo al servidor
    }

    fun guardar(copia: Copia) {
        val tmp = File(archivo.parentFile, "sincro.json.tmp")
        tmp.writeText(JSONObject().put("rev", copia.rev).put("ligero", copia.ligero).put("datos", copia.datos).toString(), Charsets.UTF_8)
        if (!tmp.renameTo(archivo)) { archivo.delete(); tmp.renameTo(archivo) }
    }

    private val simple = File(contexto.filesDir, "simple.json")

    /** Borra las copias locales: la próxima vez se vuelve a pedir todo al servidor. */
    fun borrarTodo() { archivo.delete(); simple.delete() }

    /** Última lista de canales agrupados (/api/simple), tal como la dio el servidor. Null si no hay. */
    fun leerSimple(): String? = try { if (simple.exists()) simple.readText(Charsets.UTF_8) else null } catch (e: Exception) { null }

    fun guardarSimple(texto: String) {
        try { simple.writeText(texto, Charsets.UTF_8) } catch (e: Exception) { /* sin sitio: se sigue sin guardar */ }
    }
}
