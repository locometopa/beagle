package es.locometopa.beagle

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Quién es esta tele ante el servidor: así sale en el panel, en «Teles». */
class Identidad(val equipo: String, val nombre: String, val version: String) {
    /** Parámetros que el servidor lee en /sincro y /bloqueo (`tele`, `equipo`, `nombre`, `v`). */
    fun parametros(): String {
        fun e(t: String) = URLEncoder.encode(t, "UTF-8")
        return "tele=${e("beagle-$equipo")}&equipo=${e("beagle-$equipo")}&nombre=${e(nombre)}&v=${e(version)}"
    }
}

/** Respuesta de /salud. */
class Salud(
    val motor: String?, val vpn: Boolean, val apk: String?, val copias: Int, val ultimaCopia: String?,
    /** Cómo sale el servidor a internet con la VPN (proveedor · ciudad · país · IP), o null si está apagada. */
    val vpnDetalle: String? = null,
    /** Puerto de entrada que da la VPN al motor (0 = ninguno: llega a menos peers y el canal tarda más). */
    val puerto: Int = 0,
    /** La VPN del servidor tiene pasarela para canales de TV por internet (IPTV/HLS). */
    val pasarela: Boolean = false,
    /** Países de salida que ofrece Proton: (id para el servidor, nombre en castellano, recomendado). */
    val paisesVpn: List<Triple<String, String, Boolean>> = emptyList(),
    /** Proton está en uso con su clave guardada (si no, no se puede elegir país). */
    val protonListo: Boolean = false,
)

/** Una carpeta de vídeos del servidor (o de su NAS). La de grabaciones sale también aquí, con id «grabaciones». */
class Carpeta(val id: String, val nombre: String, val ruta: String, val existe: Boolean, val elementos: String, val encendido: Boolean?)

/** Un vídeo de una carpeta. [url] ya es completa (con la dirección del servidor). */
class VideoCarpeta(val nombre: String, val sub: String, val bytes: Long, val url: String)

/** Lo que hay dentro de una carpeta: subcarpetas (nombre y ruta relativa) y vídeos. */
class Listado(val subcarpetas: List<Pair<String, String>>, val videos: List<VideoCarpeta>)

/** Respuesta de /sincro: los datos compartidos entre las teles, tal cual los guarda el servidor. */
class Sincro(val rev: Long, val datos: JSONObject?, val ligero: Boolean, val sinCambios: Boolean)

/** Lo que pasa al guardar: o se acepta, o otra tele guardó antes y hay que cargar lo suyo. */
sealed interface Guardado {
    class Hecho(val rev: Long, val recargar: Boolean) : Guardado
    class Conflicto(val actual: Sincro) : Guardado
}

/** Cómo va la comprobación de canales: activa, canales hechos de total y cuántos están vivos. */
class Comprobacion(val activa: Boolean, val hechos: Int, val total: Int, val vivos: Int, val nota: String)

/** Respuesta de /bloqueo. */
class Bloqueo(val bloqueada: Boolean, val mensaje: String, val actualizar: Boolean)

/**
 * Lo que Beagle habla con el servidor de casa de HomeTV (puerto 8096).
 * Todo es bloqueante: se llama siempre desde un hilo que no sea el de la pantalla.
 */
object Servidor {
    const val PUERTO_CASA = 8096

    fun casa(equipo: String): String = "http://$equipo:$PUERTO_CASA"
    fun motor(equipo: String): String = Motor.urlCasa(equipo)

    /** Fuera de casa el servidor se alcanza por Tailscale (100.x.x.x o nombre .ts.net). */
    fun fueraDeCasa(equipo: String): Boolean = equipo.startsWith("100.") || equipo.endsWith(".ts.net")

    /**
     * Deja solo el nombre o la IP del servidor: se admite escribir «192.168.1.180», «servidor.local»
     * o pegar la dirección entera («http://192.168.1.180:8096/tv»).
     */
    fun normalizar(texto: String): String {
        var t = texto.trim().lowercase()
        t = t.replace(Regex("^[a-z][a-z0-9+.-]*://"), "")
        t = t.substringBefore('/').substringBefore('?').substringBefore('#')
        t = t.substringAfter('@')
        t = t.replace(Regex(":\\d*$"), "")
        return t.filter { it.isLetterOrDigit() || it == '.' || it == '-' }
    }

    /** Pide una dirección y devuelve el texto. Con un código de error HTTP lanza IOException con el motivo. */
    fun pedir(url: String, espera: Int, metodo: String = "GET", cuerpo: String? = null, tolerante: Boolean = false): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = minOf(espera, 10000)
            c.readTimeout = espera
            c.setRequestProperty("User-Agent", "Beagle")
            c.requestMethod = metodo
            if (cuerpo != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                c.outputStream.use { it.write(cuerpo.toByteArray(Charsets.UTF_8)) }
            }
            val codigo = c.responseCode
            val flujo = if (codigo < 400) c.inputStream else c.errorStream
            val texto = flujo?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (codigo >= 400 && !tolerante) {
                val motivo = try { JSONObject(texto).optString("error", "") } catch (e: Exception) { "" }
                throw IOException(motivo.ifEmpty { "El servidor ha respondido con el error $codigo" })
            }
            return texto
        } finally {
            c.disconnect()
        }
    }

    /** Como [pedir], pero ya leído como JSON. */
    fun pedirJson(url: String, espera: Int, metodo: String = "GET", cuerpo: String? = null, tolerante: Boolean = false): JSONObject =
        JSONObject(pedir(url, espera, metodo, cuerpo, tolerante))

    // --- /salud ---

    fun salud(equipo: String, espera: Int = 5000): Salud {
        val j = pedirJson(casa(equipo) + "/salud", espera)
        if (j.optString("servicio", "") != "hometv") throw IOException("Ahí no hay un servidor de casa de HomeTV")
        val v = if (j.isNull("vpn")) null else j.opt("vpn")
        val vpnActiva = when (v) {
            is JSONObject -> v.optBoolean("activa", v.optBoolean("ok", true))
            is Boolean -> v
            else -> false
        }
        val detalle = (v as? JSONObject)?.takeIf { vpnActiva }?.let { o ->
            listOf("proveedor", "ciudad", "pais", "ip").mapNotNull { k -> if (o.isNull(k)) null else o.optString(k, "").ifEmpty { null } }
                .joinToString(" · ").ifEmpty { null }
        }
        return Salud(
            vpnDetalle = detalle,
            puerto = (v as? JSONObject)?.optInt("puerto", 0) ?: 0,
            pasarela = vpnActiva && (v as? JSONObject)?.optBoolean("pasarela", false) == true,
            paisesVpn = j.optJSONObject("vpnCambio")?.optJSONArray("disponibles")?.let { a ->
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { Triple(it.optString("id"), it.optString("es", it.optString("id")), it.optBoolean("rec", false)) } }
            } ?: emptyList(),
            protonListo = j.optJSONObject("vpnCambio")?.optBoolean("protonListo", false) == true,
            motor = if (j.isNull("motor")) null else j.optString("motor", "").ifEmpty { null },
            vpn = vpnActiva,
            apk = if (j.isNull("apk")) null else j.optString("apk", "").ifEmpty { null },
            copias = j.optInt("copias", 0),
            ultimaCopia = if (j.isNull("ultimaCopia")) null else j.optString("ultimaCopia", "").ifEmpty { null },
        )
    }

    /** Dirección de una emisión de TV por internet a través de la pasarela VPN del servidor (conserva el nombre del archivo). */
    fun porPasarela(equipo: String, url: String): String {
        val n = url.substringBefore('?').substringAfterLast('/')
        val nombre = if (Regex("[\\w.-]{1,80}").matches(n)) n else "emision"
        return casa(equipo) + "/vpn/" + nombre + "?u=" + java.net.URLEncoder.encode(url, "UTF-8")
    }

    /** ¿Hay en esa dirección un servidor de casa de HomeTV? */
    fun esServidor(equipo: String, espera: Int = 4000): Boolean = try {
        pedirJson(casa(equipo) + "/salud", espera).optString("servicio", "") == "hometv"
    } catch (e: Exception) {
        false
    }

    // --- /sincro ---

    /**
     * Lee lo compartido. Con [desde] (la `rev` que ya se tiene) el servidor responde «sinCambios» si no hay nada nuevo.
     * Con [ligero] no manda los canales de listas apagadas.
     */
    fun leerSincro(equipo: String, id: Identidad, desde: Long = 0, ligero: Boolean = true): Sincro {
        var url = casa(equipo) + "/sincro?" + id.parametros()
        if (desde > 0) url += "&desde=$desde"
        if (ligero) url += "&ligero=1"
        return leerRespuestaSincro(pedirJson(url, 60000))
    }

    internal fun leerRespuestaSincro(j: JSONObject): Sincro = Sincro(
        rev = j.optLong("rev", 0),
        datos = j.optJSONObject("datos"),
        ligero = j.optBoolean("ligero", false),
        sinCambios = j.optBoolean("sinCambios", false),
    )

    /**
     * Guarda los datos. [base] es la `rev` de lo último que esta tele vio: si otra guardó después, el servidor
     * responde 409 con lo nuevo y aquí sale [Guardado.Conflicto] para cargarlo antes de volver a intentarlo.
     * [parcial] es para cuando los datos son los ligeros (sin canales de listas apagadas).
     */
    fun guardarSincro(equipo: String, id: Identidad, base: Long, datos: JSONObject, parcial: Boolean): Guardado {
        val cuerpo = JSONObject().put("base", base).put("datos", datos).put("parcial", parcial).put("de", id.nombre).toString()
        val c = URL(casa(equipo) + "/sincro?" + id.parametros()).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10000
            c.readTimeout = 60000
            c.requestMethod = "PUT"
            c.doOutput = true
            c.setRequestProperty("User-Agent", "Beagle")
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(cuerpo.toByteArray(Charsets.UTF_8)) }
            val codigo = c.responseCode
            val texto = (if (codigo < 400) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            if (codigo == 409) return Guardado.Conflicto(leerRespuestaSincro(JSONObject(texto)))
            if (codigo >= 400) {
                val motivo = try { JSONObject(texto).optString("error", "") } catch (e: Exception) { "" }
                throw IOException(motivo.ifEmpty { "El servidor ha respondido con el error $codigo" })
            }
            val j = JSONObject(texto)
            return Guardado.Hecho(j.optLong("rev", 0), j.optBoolean("recargar", false))
        } finally {
            c.disconnect()
        }
    }

    /** Marca o quita de favoritos un canal (todos sus enlaces) en el servidor: lo ven todas las teles. */
    fun favorito(equipo: String, canal: Canal, poner: Boolean) {
        val ids = org.json.JSONArray()
        for (e in canal.enlaces) ids.put(e.id.ifEmpty { e.url })
        val cuerpo = JSONObject().put("ids", ids).put("favorito", poner).toString()
        val r = pedirJson(casa(equipo) + "/api/favorito", 10000, "POST", cuerpo)
        if (r.optInt("cambiados", 0) == 0) throw IOException("El servidor no ha encontrado ese canal")
    }

    // --- Carpetas y grabaciones ---

    fun carpetas(equipo: String): List<Carpeta> {
        val a = pedirJson(casa(equipo) + "/api/carpetas", 15000).optJSONArray("carpetas") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val c = a.optJSONObject(i) ?: return@mapNotNull null
            Carpeta(
                id = c.optString("id", ""), nombre = c.optString("nombre", ""), ruta = if (c.isNull("ruta")) "" else c.optString("ruta", ""),
                existe = c.optBoolean("existe", true), elementos = if (c.isNull("elementos")) "" else c.optString("elementos", ""),
                encendido = if (c.isNull("encendido")) null else c.optBoolean("encendido"),
            )
        }
    }

    fun listarCarpeta(equipo: String, id: String, sub: String): Listado {
        val u = casa(equipo) + "/api/carpetas/" + URLEncoder.encode(id, "UTF-8") + "/listar?sub=" + URLEncoder.encode(sub, "UTF-8")
        val j = pedirJson(u, 30000)
        val carpetas = j.optJSONArray("carpetas")?.let { a ->
            (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { it.optString("nombre", "") to it.optString("sub", "") } }
        } ?: emptyList()
        val videos = j.optJSONArray("videos")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val v = a.optJSONObject(i) ?: return@mapNotNull null
                val url = v.optString("url", "")
                if (url.isEmpty()) null else VideoCarpeta(v.optString("nombre", ""), v.optString("sub", ""), v.optLong("bytes", 0), if (url.startsWith("http")) url else casa(equipo) + url)
            }
        } ?: emptyList()
        return Listado(carpetas, videos)
    }

    // --- Listas ---

    /** Resultado de añadir una lista: cómo se llama y cuántos canales nuevos trajo. */
    class ListaAnadida(val nombre: String, val creados: Int)

    /**
     * Pide al servidor que baje una lista desde [url] (M3U, JSON de AceList, Xtream, MylinkPaste) y la añada a lo compartido.
     * Lo hace el servidor, así que sirve cualquier dirección que él alcance. Puede tardar hasta un minuto.
     */
    fun anadirLista(equipo: String, nombre: String, url: String): ListaAnadida {
        val cuerpo = JSONObject().put("nombre", nombre).put("url", url).toString()
        val r = pedirJson(casa(equipo) + "/api/lista", 120000, "POST", cuerpo)
        return ListaAnadida(r.optString("fuente", nombre), r.optInt("creados", 0))
    }

    /** Añade una lista desde texto pegado (M3U, JSON de AceList o enlaces acestream:// sueltos). */
    fun anadirListaTexto(equipo: String, nombre: String, texto: String): ListaAnadida {
        val cuerpo = JSONObject().put("nombre", nombre).put("texto", texto).toString()
        val r = pedirJson(casa(equipo) + "/api/lista", 120000, "POST", cuerpo)
        return ListaAnadida(r.optString("fuente", nombre), r.optInt("creados", 0))
    }

    /** Graba un canal en el servidor (empieza ya, dura [minutos]). Devuelve el id de la grabación. */
    fun grabar(equipo: String, canal: Canal, minutos: Int): String {
        val enl = org.json.JSONArray()
        for (e in canal.enlaces.take(6)) enl.put(if (e.directo) JSONObject().put("url", e.url) else JSONObject().put("id", e.id))
        val cuerpo = JSONObject().put("titulo", canal.titulo).put("canal", canal.titulo).put("enlaces", enl).put("minutos", minutos).toString()
        return pedirJson(casa(equipo) + "/api/grabar", 20000, "POST", cuerpo).optString("id", "")
    }

    /** Apunta una incidencia (corte, pantalla negra…) en el servidor para el informe. Sin respuesta si no hay conexión. */
    fun incidencia(equipo: String, canal: String, enlace: String, formato: String, vpn: Boolean, tipo: String, nota: String) {
        val o = JSONObject().put("t", System.currentTimeMillis()).put("canal", canal).put("enlace", enlace.take(8)).put("formato", formato)
            .put("vpn", vpn).put("tipo", tipo).put("nota", nota)
        pedirJson(casa(equipo) + "/api/incidencias", 8000, "POST", JSONObject().put("lista", org.json.JSONArray().put(o)).toString())
    }

    fun informeIncidencias(equipo: String, dias: Int = 7): String = pedir(casa(equipo) + "/api/incidencias/informe?dias=$dias", 10000)

    fun borrarIncidencias(equipo: String) { pedirJson(casa(equipo) + "/api/incidencias", 10000, "DELETE", null) }

    /** Grabaciones en marcha (o programadas) del servidor: (id, canal). */
    fun grabacionesActivas(equipo: String): List<Pair<String, String>> {
        val a = pedirJson(casa(equipo) + "/api/grabaciones", 10000).optJSONArray("lista") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            if (o.optString("estado") != "grabando" && o.optString("estado") != "programada") return@mapNotNull null
            o.optString("id") to o.optString("canal")
        }
    }

    /** Para una grabación y deja guardado lo grabado hasta ahora. */
    fun pararGrabacion(equipo: String, id: String) { pedirJson(casa(equipo) + "/api/grabaciones/$id/parar", 15000, "POST", "{}") }

    /** Canales de las listas apagadas del servidor que se llaman como [q]: (nombre del canal, listas donde está). */
    fun buscarApagados(equipo: String, q: String): List<Pair<String, String>> {
        val a = pedirJson(casa(equipo) + "/api/buscar-canal?q=" + URLEncoder.encode(q, "UTF-8"), 15000).optJSONArray("items") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val ls = o.optJSONArray("listas")
            val nombres = if (ls == null) "" else (0 until ls.length()).joinToString(", ") { ls.optJSONObject(it)?.optString("name", "") ?: "" }
            o.optString("title", "") to nombres
        }
    }

    /** Pide al servidor guardar una copia de seguridad de lo compartido (con todos los datos, no los ligeros). Devuelve su nombre. */
    fun copiaAhora(equipo: String, id: Identidad): String {
        val s = leerSincro(equipo, id, 0, false)
        val datos = s.datos ?: throw IOException("El servidor aún no tiene datos que copiar")
        datos.put("manual", true)
        val r = pedirJson(casa(equipo) + "/copia", 60000, "PUT", datos.toString())
        return r.optString("nombre", r.optString("omitida", "hecha"))
    }

    /** Nombres de las copias que guarda el servidor, la más reciente primero. */
    fun copias(equipo: String): List<String> {
        val a = org.json.JSONArray(pedir(casa(equipo) + "/copias", 10000))
        return (0 until a.length()).map { a.optJSONObject(it)?.optString("nombre", "") ?: a.optString(it) }.filter { it.isNotEmpty() }
    }

    // --- Comprobar canales (lo hace el servidor con su motor) ---

    /** Cómo va la comprobación de canales del servidor (de /api/estado). */
    fun comprobacion(equipo: String): Comprobacion {
        val c = pedirJson(casa(equipo) + "/api/estado", 15000).optJSONObject("comprobacion")
        return Comprobacion(
            c?.optBoolean("activa", false) == true, c?.optInt("hechos", 0) ?: 0, c?.optInt("total", 0) ?: 0, c?.optInt("vivos", 0) ?: 0,
            if (c == null || c.isNull("nota")) "" else c.optString("nota", ""),
        )
    }

    /** Empieza o para la comprobación de todos los canales en el servidor. */
    fun comprobar(equipo: String, empezar: Boolean) {
        pedirJson(casa(equipo) + "/api/comprobar", 10000, "POST", JSONObject().put("accion", if (empezar) "empezar" else "parar").toString())
    }

    /** Pide al servidor que compruebe solo estos canales (por sus ids). */
    fun comprobarIds(equipo: String, ids: List<String>) {
        pedirJson(casa(equipo) + "/api/comprobar", 10000, "POST", JSONObject().put("ids", org.json.JSONArray(ids)).toString())
    }

    /** Mantenimiento en el servidor: quita los canales caídos o las listas apagadas (con sus canales). Devuelve cuántos. */
    fun mantenimiento(equipo: String, caidos: Boolean): Int {
        val r = pedirJson(casa(equipo) + "/api/mantenimiento", 60000, "POST", JSONObject().put(if (caidos) "caidos" else "apagadas", true).toString())
        return if (caidos) r.optInt("caidos", 0) else r.optInt("canales", 0)
    }

    /** Recupera una copia del servidor: sustituye los canales y listas compartidos por los de esa copia. */
    fun restaurar(equipo: String, nombre: String) {
        pedirJson(casa(equipo) + "/api/restaurar", 30000, "POST", JSONObject().put("nombre", nombre).toString())
    }

    // --- VPN del servidor ---

    /** Pide al servidor cambiar su VPN: «warp», «proton» o «ninguna». Tarda unos segundos en aplicarse. */
    /** Cambia el país de salida de Proton (se reconecta en unos segundos). */
    fun cambiarPaisVpn(equipo: String, pais: String) {
        pedirJson(casa(equipo) + "/api/vpn", 15000, "POST", JSONObject().put("pais", pais).toString())
    }

    fun cambiarVpn(equipo: String, usar: String) {
        pedirJson(casa(equipo) + "/api/vpn", 15000, "POST", JSONObject().put("usar", usar).toString())
    }

    // --- /bloqueo ---

    /** Lo que se pregunta cada poco: si el panel ha bloqueado esta tele o le pide actualizar. */
    fun bloqueo(equipo: String, id: Identidad): Bloqueo {
        val j = pedirJson(casa(equipo) + "/bloqueo?" + id.parametros(), 5000)
        return Bloqueo(j.optBoolean("bloqueada", false), if (j.isNull("mensaje")) "" else j.optString("mensaje", ""), j.optBoolean("actualizar", false))
    }

    // --- Buscar el servidor en la red local ---

    /** Primeros tres números de las redes locales de este aparato («192.168.1.»). */
    private fun redes(): List<String> {
        val out = LinkedHashSet<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (ni in interfaces) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in ni.inetAddresses) {
                    if (a is Inet4Address && a.isSiteLocalAddress) {
                        val ip = a.hostAddress ?: continue
                        out.add(ip.substringBeforeLast('.') + ".")
                    }
                }
            }
        } catch (e: Exception) {
            // sin permiso o sin red: no se puede buscar
        }
        return out.toList()
    }

    /** Recorre la red local buscando el servidor de casa. Devuelve su IP, o null si no aparece. Tarda unos segundos. */
    fun buscarEnRed(prefijos: List<String> = redes()): String? {
        if (prefijos.isEmpty()) return null
        val hilos = Executors.newFixedThreadPool(48)
        try {
            val tareas = ArrayList<Callable<String>>()
            for (p in prefijos) {
                for (n in 1..254) {
                    val ip = p + n
                    tareas.add(Callable {
                        Socket().use { it.connect(InetSocketAddress(ip, PUERTO_CASA), 700) }
                        if (!esServidor(ip, 3000)) throw IOException("no es el servidor")
                        ip
                    })
                }
            }
            return try {
                hilos.invokeAny(tareas, 30, TimeUnit.SECONDS)
            } catch (e: Exception) {
                null
            }
        } finally {
            hilos.shutdownNow()
        }
    }
}
