package es.locometopa.beagle

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.UUID

/** No se puede hablar con el motor de Ace Stream (apagado, sin red, o la pasarela no lo alcanza). */
class MotorNoDisponible(mensaje: String) : IOException(mensaje)

/** El motor no encuentra ese contenido (id o infohash que no existe o no tiene fuentes). */
class ContenidoNoEncontrado(val id: String) : IOException("El motor no encuentra ese canal")

/** Una emisión abierta en el motor: la dirección del vídeo, la de estado y la de órdenes (ya con la dirección del motor que se usa). */
class Emision(val id: String, val kind: String, val infohash: String?, val urlVideo: String, val urlEstado: String, val urlOrden: String)

/** Lo que cuenta el motor de una emisión: «dl» (descargando), «prebuf», «check»…, peers y velocidad. */
class EstadoEmision(val estado: String, val peers: Int?, val velocidadBajada: Int?, val descargado: Long?)

/** Versión del motor, de /webui/api/service. */
class VersionMotor(val version: String, val plataforma: String?)

/**
 * Cliente del motor de Ace Stream (la misma API HTTP que usa HomeTV en engine.js).
 * [base] es «http://IP:6878». Todo es bloqueante: se llama desde un hilo que no sea el de la pantalla.
 */
class Motor(base: String, private val espera: Int = 15000) {
    private val base = base.trimEnd('/')

    companion object {
        const val PUERTO = 6878
        const val URL_TELE = "http://127.0.0.1:$PUERTO"
        private const val NO_ENCONTRADO = "failed to load content"
        private val CONTENT_ID = Regex("^[0-9a-fA-F]{40}$")

        /** El motor del servidor de casa. */
        fun urlCasa(equipo: String): String = "http://$equipo:$PUERTO"

        /** Un Content ID válido (40 caracteres hexadecimales), con o sin `acestream://`. Null si no lo es. */
        fun idValido(valor: String): String? {
            var v = valor.trim()
            if (v.lowercase().startsWith("acestream://")) v = v.substring(12)
            return if (CONTENT_ID.matches(v)) v.lowercase() else null
        }
    }

    private fun url(ruta: String): String = base + "/" + ruta.trimStart('/')

    /**
     * El motor devuelve direcciones con su propia dirección interna («http://127.0.0.1:6878/ace/c/…»):
     * se reescriben para que vayan siempre por la dirección con la que se le habla.
     */
    fun rebase(url: String): String {
        val r = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://[^/]+(/.*)?$").matchEntire(url.trim()) ?: throw IOException("Dirección no válida del motor: $url")
        return base + (r.groupValues[1])
    }

    private fun json(url: String, tiempo: Int = espera): JSONObject {
        val c = try { URL(url).openConnection() as HttpURLConnection } catch (e: Exception) { throw MotorNoDisponible("Dirección del motor no válida") }
        try {
            c.connectTimeout = minOf(tiempo, 10000)
            c.readTimeout = tiempo
            c.setRequestProperty("User-Agent", "Beagle")
            val codigo = try { c.responseCode } catch (e: SocketTimeoutException) {
                throw IOException("El motor no ha respondido a tiempo")
            } catch (e: IOException) {
                throw MotorNoDisponible("No se puede contactar con el motor de Ace Stream")
            }
            if (codigo == 502 || codigo == 504) throw MotorNoDisponible("No se puede contactar con el motor de Ace Stream")
            if (codigo >= 400) throw IOException("Respuesta HTTP $codigo del motor")
            val texto = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            return try { JSONObject(texto) } catch (e: Exception) { throw IOException("El motor ha devuelto una respuesta no válida") }
        } finally {
            c.disconnect()
        }
    }

    private fun JSONObject.texto(nombre: String): String? = if (isNull(nombre)) null else optString(nombre, "").ifEmpty { null }

    fun version(): VersionMotor {
        val j = json(url("webui/api/service?method=get_version&format=json"), 5000)
        val r = j.optJSONObject("result")
        if (r == null || !j.isNull("error")) throw IOException(j.texto("error") ?: "Respuesta inesperada del motor")
        return VersionMotor(r.optString("version", "?"), r.texto("platform"))
    }

    /**
     * Abre una emisión. Con [hls] pide el manifiesto HLS en vez del flujo continuo (TS).
     * Prueba el identificador como Content ID y como infohash (según [kind] primero uno y luego el otro), igual que HomeTV.
     */
    fun abrir(id: String, kind: String? = null, hls: Boolean = false): Emision {
        val tipos = if (kind == "infohash") listOf("infohash", "content_id") else listOf("content_id", "infohash")
        val ruta = if (hls) "ace/manifest.m3u8" else "ace/getstream"
        for (k in tipos) {
            val param = if (k == "infohash") "infohash" else "id"
            val u = url("$ruta?format=json&pid=${UUID.randomUUID()}&$param=$id")
            val j = try {
                json(u, if (k == "infohash") 40000 else espera)
            } catch (e: MotorNoDisponible) {
                throw e
            } catch (e: IOException) {
                if (k == "infohash") continue
                throw e
            }
            val error = j.texto("error")
            if (error == NO_ENCONTRADO) continue
            if (error != null) throw IOException("Error del motor: $error")
            val r = j.optJSONObject("response")
            val video = r?.texto("playback_url")
            val estado = r?.texto("stat_url")
            val orden = r?.texto("command_url")
            if (r == null || video == null || estado == null || orden == null) throw IOException("Respuesta inesperada del motor")
            return Emision(id, k, r.texto("infohash"), rebase(video), rebase(estado), rebase(orden))
        }
        throw ContenidoNoEncontrado(id)
    }

    fun estado(e: Emision): EstadoEmision {
        val j = json(e.urlEstado)
        j.texto("error")?.let { throw IOException("Error del motor: $it") }
        val r = j.optJSONObject("response")
        return EstadoEmision(
            estado = r?.texto("status") ?: "idle",
            peers = if (r == null || r.isNull("peers")) null else r.optInt("peers"),
            velocidadBajada = if (r == null || r.isNull("speed_down")) null else r.optInt("speed_down"),
            descargado = if (r == null || r.isNull("downloaded")) null else r.optLong("downloaded"),
        )
    }

    /** Dice al motor que pare la emisión. Si falla no pasa nada: no debe tapar otros errores. */
    fun parar(e: Emision) {
        try {
            val sep = if (e.urlOrden.contains('?')) '&' else '?'
            json(e.urlOrden + sep + "method=stop", 5000)
        } catch (ignorado: Exception) {
            // una parada fallida no importa
        }
    }
}
