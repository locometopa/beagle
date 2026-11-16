package es.locometopa.beagle

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Lo que dice la rama `apk` (o el servidor): qué versión es la última. */
class VersionNueva(val version: String)

/**
 * Actualizaciones: mira `version.txt`, baja `Beagle.apk` y abre el instalador de Android.
 * Android no deja instalar en silencio: el último paso («Instalar») lo confirma quien tiene el mando.
 * Todo es bloqueante: se llama desde un hilo que no sea el de la pantalla.
 */
object Actualizador {
    const val URL_POR_DEFECTO = "https://github.com/locometopa/beagle/raw/apk/"
    const val PERMISO = "PERMISO"

    /** ¿«a» es una versión más alta que «b»? Compara número a número («0.10.0» > «0.9.0»). */
    fun esMayor(a: String, b: String): Boolean {
        val pa = a.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        val pb = b.split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun base(url: String): String = url.trim().let { if (it.endsWith("/")) it else "$it/" }

    /** La versión nueva, o null si ya se tiene la última. Lanza si no se puede comprobar. */
    fun buscar(url: String, actual: String): VersionNueva? {
        val texto = Servidor.pedir(base(url) + "version.txt?t=${System.currentTimeMillis()}", 10000).trim()
        val v = texto.split(Regex("\\s+")).firstOrNull().orEmpty()
        if (v.isEmpty() || !v.first().isDigit()) throw IOException("version.txt no tiene una versión válida")
        return if (esMayor(v, actual)) VersionNueva(v) else null
    }

    /** Baja la APK a la caché. [avance] recibe de 0 a 100 (o -1 si el servidor no dice el tamaño). */
    fun descargar(contexto: Context, url: String, avance: (Int) -> Unit = {}): File {
        val c = URL(base(url) + "Beagle.apk?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 20000
            c.readTimeout = 60000
            c.instanceFollowRedirects = true
            if (c.responseCode != 200) throw IOException("La descarga ha fallado (HTTP ${c.responseCode})")
            val total = c.contentLengthLong
            val destino = File(contexto.cacheDir, "actualizacion.apk")
            var hecho = 0L
            var ultimo = -2
            c.inputStream.use { entrada ->
                destino.outputStream().use { salida ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = entrada.read(buf)
                        if (n < 0) break
                        salida.write(buf, 0, n)
                        hecho += n
                        val p = if (total > 0) (hecho * 100 / total).toInt() else -1
                        if (p != ultimo) { ultimo = p; avance(p) }
                    }
                }
            }
            if (total > 0 && hecho != total) throw IOException("La descarga se ha cortado")
            return destino
        } finally {
            c.disconnect()
        }
    }

    /** Abre el instalador de Android. Devuelve null si se ha abierto, [PERMISO] si falta el permiso, o el motivo. */
    fun instalar(contexto: Context, apk: File): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !contexto.packageManager.canRequestPackageInstalls()) {
            val ajustes = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${contexto.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { contexto.startActivity(ajustes) } catch (e: Exception) { /* la tele no tiene esa pantalla */ }
            return PERMISO
        }
        val uri = FileProvider.getUriForFile(contexto, "${contexto.packageName}.fileprovider", apk)
        val intento = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            contexto.startActivity(intento)
            null
        } catch (e: ActivityNotFoundException) {
            "La tele no tiene instalador de apps"
        }
    }
}
