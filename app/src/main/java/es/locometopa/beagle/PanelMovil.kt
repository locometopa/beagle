package es.locometopa.beagle

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.text.Collator
import java.util.Locale

/**
 * El panel del móvil: un servidor dentro de la tele (puerto 8095) que sirve la misma página de HomeTV.
 * Las peticiones /api/... se resuelven aquí sobre los datos compartidos del servidor de casa.
 */
object PanelMovil {
    private var servidor: ServidorMovil? = null
    private lateinit var contexto: Context
    private lateinit var conexion: Conexion
    private lateinit var ajustes: Ajustes

    private class ErrorApi(val estado: Int, mensaje: String) : Exception(mensaje)

    fun preparar(c: Context, cx: Conexion, aj: Ajustes) {
        contexto = c.applicationContext
        conexion = cx
        ajustes = aj
        if (servidor == null) servidor = ServidorMovil(contexto.assets) { m, r, b -> atender(m, r, b) }
    }

    val activo: Boolean get() = servidor?.activo == true

    fun claveNueva(): String {
        val abc = "abcdefghjkmnpqrstuvwxyz23456789"
        val r = java.security.SecureRandom()
        return (1..6).map { abc[r.nextInt(abc.length)] }.joinToString("")
    }

    /** Enciende el panel (crea la clave si no hay una buena). Devuelve la dirección con la clave. */
    fun encender(): String {
        if (!Regex("[a-z0-9]{6}").matches(ajustes.movilToken)) ajustes.movilToken = claveNueva()
        val p = servidor!!.iniciar(ajustes.movilPuerto, ajustes.movilToken)
        ajustes.movilActivo = true
        return "http://${ServidorMovil.ipLocal()}:$p/${ajustes.movilToken}"
    }

    fun apagar() { servidor?.detener(); ajustes.movilActivo = false }

    fun direccion(): String = "http://${ServidorMovil.ipLocal()}:${ajustes.movilPuerto}/${ajustes.movilToken}"

    fun cambiarClave(): String { ajustes.movilToken = claveNueva(); return encender() }

    // ---------- API ----------

    private fun atender(metodo: String, ruta: String, cuerpo: String): Pair<Int, String> = try {
        val b = if (cuerpo.isBlank()) JSONObject() else JSONObject(cuerpo)
        val r = runBlocking { api("$metodo $ruta", b) }
        200 to (if (r.length() == 1 && r.has("_array")) r.getJSONArray("_array").toString() else r.toString())
    } catch (e: ErrorApi) {
        e.estado to JSONObject().put("error", e.message).toString()
    } catch (e: Exception) {
        400 to JSONObject().put("error", e.message ?: "Error").toString()
    }

    private fun datos(): JSONObject = conexion.copiaLocal()?.datos ?: throw ErrorApi(409, "Aún no hay datos: abre Ajustes en la tele para sincronizar")
    private fun canales(): List<JSONObject> = datos().optJSONArray("channels")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()
    private fun texto(o: JSONObject, k: String) = o.optString(k, "").let { if (it == "null") "" else it }
    private fun estadoDe(c: JSONObject) = c.optJSONArray("checks")?.optJSONObject(0)?.optString("status", "pending")?.ifEmpty { "pending" } ?: "pending"
    private fun clave(s: String) = Modelo.sinTildes(s).trim().lowercase(Locale.ROOT)

    private fun dto(c: JSONObject): JSONObject {
        val chk = c.optJSONArray("checks")?.optJSONObject(0)
        return JSONObject()
            .put("id", texto(c, "id")).put("title", texto(c, "title"))
            .put("category", if (texto(c, "category").isEmpty()) JSONObject.NULL else texto(c, "category"))
            .put("favorite", c.optBoolean("favorite", false)).put("status", estadoDe(c))
            .put("peers", if (chk != null && chk.has("peers")) chk.opt("peers") else JSONObject.NULL)
            .put("checkedAt", chk?.opt("at") ?: JSONObject.NULL)
            .put("checking", false)
            .put("listas", c.optJSONArray("listas") ?: JSONArray())
            .put("abierto", texto(c, "url").isNotEmpty())
    }

    private fun ocultas(): List<String> = datos().optJSONObject("ajustes")?.optJSONArray("catOcultas")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()

    private fun cambiar(ids: Set<String>, cambio: (JSONObject) -> Unit) = runBlocking {
        conexion.modificarDatos { d ->
            val cs = d.optJSONArray("channels") ?: return@modificarDatos
            for (i in 0 until cs.length()) { val c = cs.optJSONObject(i) ?: continue; if (texto(c, "id") in ids) cambio(c) }
        }
    }

    private fun idsDe(b: JSONObject): Set<String> {
        val a = b.optJSONArray("ids")
        val s = HashSet<String>()
        if (a != null) for (i in 0 until a.length()) s.add(a.optString(i))
        if (b.has("id")) s.add(b.optString("id"))
        return s
    }

    private suspend fun api(ruta: String, b: JSONObject): JSONObject {
        when (ruta) {
            "GET /api/estado" -> {
                val d = datos()
                val cs = canales()
                val oc = ocultas()
                val ocClaves = oc.map { clave(it) }.toSet()
                val colator = Collator.getInstance(Locale("es"))
                val cats = cs.map { texto(it, "category") }.filter { it.isNotEmpty() && clave(it) !in ocClaves }.distinct().sortedWith(colator)
                val cuenta = HashMap<String, Int>()
                for (c in cs) c.optJSONArray("listas")?.let { ls -> for (j in 0 until ls.length()) cuenta[ls.optString(j)] = (cuenta[ls.optString(j)] ?: 0) + 1 }
                val fuentes = JSONArray()
                d.optJSONArray("sources")?.let { a ->
                    for (i in 0 until a.length()) {
                        val f = a.optJSONObject(i) ?: continue
                        val id = texto(f, "id")
                        fuentes.put(JSONObject().put("id", id).put("name", texto(f, "name")).put("url", texto(f, "url"))
                            .put("fetchedAt", f.opt("fetchedAt") ?: JSONObject.NULL).put("count", cuenta[id] ?: 0)
                            .put("activa", f.optBoolean("enabled", true)).put("incluida", f.optBoolean("builtin", false)).put("nota", JSONObject.NULL))
                    }
                }
                val ocultasJ = JSONArray(oc.map { n -> JSONObject().put("nombre", n).put("n", cs.count { clave(texto(it, "category")) == clave(n) }) })
                val comp = try { conexion.comprobacion() } catch (e: Exception) { null }
                return JSONObject().put("app", ajustes.version()).put("motor", true).put("canales", cs.size)
                    .put("vivos", cs.count { estadoDe(it) == "alive" }).put("categorias", JSONArray(cats)).put("ocultas", ocultasJ)
                    .put("fuentes", fuentes)
                    .put("comprobando", if (comp != null && comp.activa) JSONObject().put("total", comp.total).put("hechos", comp.hechos).put("vivos", comp.vivos) else JSONObject.NULL)
                    .put("reproductores", JSONObject()).put("reproductor", "").put("vpn", "").put("nativa", false)
                    .put("ordenes", JSONObject().put("recomendado", "Recomendado").put("nombre", "Por nombre").put("peers", "Por peers").put("categoria", "Por categoría"))
                    .put("servidorCasa", "http://${conexion.servidor().substringBefore(':')}:8096/").put("orden", "recomendado")
                    .put("ajustes", JSONArray())
            }
            "GET /api/canales" -> return JSONObject().put("_array", JSONArray(canales().map { dto(it) }))
            "POST /api/ajustes" -> return JSONObject().put("ajustes", JSONArray())
            "POST /api/canal" -> {
                val id = b.optString("id")
                if (canales().none { texto(it, "id") == id }) throw ErrorApi(404, "Ese canal ya no existe")
                cambiar(setOf(id)) { c ->
                    if (b.optString("title").isNotBlank()) { c.put("title", b.optString("title").trim().take(200)); c.put("titlePending", false) }
                    if (b.has("category")) { val v = b.optString("category").trim(); if (v.isEmpty() || b.isNull("category")) c.remove("category") else c.put("category", v) }
                    if (b.has("favorite")) c.put("favorite", b.optBoolean("favorite"))
                }
                return dto(canales().first { texto(it, "id") == id })
            }
            "POST /api/lote" -> {
                val ids = idsDe(b)
                if (canales().none { texto(it, "id") in ids }) throw ErrorApi(400, "No hay canales seleccionados")
                when (val a = b.optString("accion")) {
                    "fav" -> cambiar(ids) { it.put("favorite", b.optBoolean("valor")) }
                    "categoria" -> { val v = b.optString("valor").trim(); cambiar(ids) { if (v.isEmpty()) it.remove("category") else it.put("category", v) } }
                    "lista" -> {
                        val nombre = b.optString("valor").trim()
                        runBlocking {
                            conexion.modificarDatos { d ->
                                val fs = d.optJSONArray("sources") ?: JSONArray().also { d.put("sources", it) }
                                var id = ""
                                for (i in 0 until fs.length()) { val f = fs.optJSONObject(i); if (f != null && !f.optBoolean("builtin") && texto(f, "name") == nombre) id = texto(f, "id") }
                                if (id.isEmpty()) { id = "l" + java.lang.Long.toString(System.currentTimeMillis(), 36); fs.put(JSONObject().put("id", id).put("name", nombre).put("enabled", true)) }
                                val cs = d.optJSONArray("channels") ?: return@modificarDatos
                                for (i in 0 until cs.length()) {
                                    val c = cs.optJSONObject(i) ?: continue
                                    if (texto(c, "id") !in ids) continue
                                    val ls = c.optJSONArray("listas") ?: JSONArray().also { c.put("listas", it) }
                                    if ((0 until ls.length()).none { ls.optString(it) == id }) ls.put(id)
                                }
                            }
                        }
                    }
                    "quitar-lista" -> {
                        val l = b.optString("valor")
                        cambiar(ids) { c -> val ls = c.optJSONArray("listas"); if (ls != null) c.put("listas", JSONArray((0 until ls.length()).map { ls.optString(it) }.filter { it != l })) }
                    }
                    "borrar" -> borrar(ids)
                    "comprobar" -> { conexion.comprobarIds(ids.toList()); return JSONObject().put("total", ids.size) }
                    else -> throw ErrorApi(400, "Acción desconocida: $a")
                }
                return JSONObject().put("total", ids.size)
            }
            "POST /api/borrar" -> { val ids = idsDe(b); val n = borrar(ids); return JSONObject().put("borrados", n) }
            "POST /api/borrar-todo" -> { borrar(canales().map { texto(it, "id") }.toSet()); return JSONObject().put("ok", true) }
            "POST /api/categoria-ocultar" -> {
                val n = b.optString("nombre")
                val actuales = ocultas().filter { clave(it) != clave(n) }.toMutableList()
                if (b.optBoolean("oculta")) actuales.add(n)
                conexion.ponerCatOcultas(actuales)
                return JSONObject().put("ok", true)
            }
            "POST /api/categoria" -> {
                val de = b.optString("de"); val a = b.optString("a").trim()
                val ids = canales().filter { texto(it, "category") == de }.map { texto(it, "id") }.toSet()
                cambiar(ids) { if (a.isEmpty()) it.remove("category") else it.put("category", a) }
                return JSONObject().put("total", ids.size)
            }
            "POST /api/unificar" -> {
                var total = 0
                val grupos = canales().filter { texto(it, "category").isNotEmpty() }.groupBy { clave(texto(it, "category")) }
                for ((_, g) in grupos) {
                    val nombres = g.map { texto(it, "category") }
                    if (nombres.distinct().size < 2) continue
                    val buena = nombres.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
                    val ids = g.filter { texto(it, "category") != buena }.map { texto(it, "id") }.toSet()
                    cambiar(ids) { it.put("category", buena) }
                    total += ids.size
                }
                return JSONObject().put("total", total)
            }
            "POST /api/fuente-renombrar" -> {
                conexion.modificarLista(b.optString("id")) { it.put("name", b.optString("nombre").trim()) }
                return JSONObject().put("ok", true)
            }
            "POST /api/fuente-activar" -> {
                conexion.modificarLista(b.optString("id")) { it.put("enabled", b.optBoolean("activa")) }
                return JSONObject().put("ok", true)
            }
            "POST /api/fuente-borrar" -> {
                val id = b.optString("id")
                if (b.optBoolean("borrarCanales")) conexion.quitarLista(id)
                else runBlocking {
                    conexion.modificarDatos { d ->
                        val fs = d.optJSONArray("sources") ?: return@modificarDatos
                        d.put("sources", JSONArray((0 until fs.length()).mapNotNull { fs.optJSONObject(it) }.filter { texto(it, "id") != id }))
                    }
                }
                return JSONObject().put("ok", true)
            }
            "POST /api/fuente-actualizar" -> {
                val f = conexion.listas().firstOrNull { it.id == b.optString("id") } ?: throw ErrorApi(404, "Esa lista no existe")
                val url = datos().optJSONArray("sources")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.firstOrNull { texto(it, "id") == f.id }?.let { texto(it, "url") } } ?: ""
                if (url.isEmpty()) throw ErrorApi(400, "Esta lista no tiene dirección web: se añadió con un archivo o texto")
                val r = try { conexion.anadirLista(f.nombre, url) } catch (e: Exception) { throw ErrorApi(502, "No se pudo descargar: ${e.message}") }
                return JSONObject().put("created", r.creados)
            }
            "POST /api/fuentes" -> {
                var n = 0
                val fs = datos().optJSONArray("sources")
                if (fs != null) for (i in 0 until fs.length()) {
                    val f = fs.optJSONObject(i) ?: continue
                    val url = texto(f, "url")
                    if (url.isEmpty() || !f.optBoolean("enabled", true) || f.optBoolean("builtin")) continue
                    try { n += conexion.anadirLista(texto(f, "name"), url).creados } catch (e: Exception) { /* sigue con las demás */ }
                }
                return JSONObject().put("created", n)
            }
            "POST /api/importar" -> {
                val texto = b.optString("texto")
                val lista = b.optString("lista").trim().ifEmpty { "Lista del móvil" }
                val r = try { conexion.anadirListaTexto(lista, texto) } catch (e: Exception) { throw ErrorApi(400, e.message ?: "No se pudo importar") }
                return JSONObject().put("created", r.creados).put("repeated", 0).put("invalid", 0)
            }
            "POST /api/importar-url" -> {
                val url = b.optString("url").trim()
                if (!Regex("(?i)https?://.*").matches(url)) throw ErrorApi(400, "La URL debe empezar por http:// o https://")
                val nombre = b.optString("nombre").trim().ifEmpty { url.substringAfter("://").substringBefore("/") }
                val r = try { conexion.anadirLista(nombre, url) } catch (e: Exception) { throw ErrorApi(400, e.message ?: "No se pudo importar") }
                return JSONObject().put("created", r.creados).put("repeated", 0).put("invalid", 0)
            }
            "POST /api/exportar" -> {
                val cs = canales()
                if (b.optString("formato") == "m3u") {
                    val sb = StringBuilder("#EXTM3U\n")
                    for (c in cs) {
                        val url = texto(c, "url").ifEmpty { "http://127.0.0.1:6878/ace/getstream?id=${texto(c, "id")}" }
                        sb.append("#EXTINF:-1 group-title=\"${texto(c, "category")}\",${texto(c, "title")}\n$url\n")
                    }
                    return JSONObject().put("nombre", "beagle.m3u").put("texto", sb.toString())
                }
                return JSONObject().put("nombre", "beagle.json").put("texto", datos().toString(2))
            }
            "POST /api/comprobar" -> {
                val ids = idsDe(b)
                if (ids.isEmpty()) conexion.comprobar(true) else conexion.comprobarIds(ids.toList())
                return JSONObject().put("total", if (ids.isEmpty()) canales().size else ids.size)
            }
            "GET /api/agenda" -> {
                val cat = conexion.catalogoLocal() ?: return JSONObject().put("_array", JSONArray())
                val porClave = cat.canales.associateBy { it.clave }
                val arr = JSONArray()
                for (ev in cat.eventos) {
                    val cs = JSONArray()
                    for (k in ev.claves) {
                        val c = porClave[k] ?: continue
                        val e = c.enlaces.firstOrNull() ?: continue
                        cs.put(JSONObject().put("id", e.id).put("title", c.titulo)
                            .put("status", when (c.estado) { 0 -> "alive"; 1 -> "pending"; else -> "no_peers" }).put("posible", ev.posible))
                    }
                    arr.put(JSONObject().put("id", ev.id).put("inicio", ev.inicio).put("deporte", ev.deporte)
                        .put("competicion", ev.competicion).put("titulo", ev.titulo).put("gratis", JSONArray(ev.gratis)).put("canales", cs))
                }
                return JSONObject().put("_array", arr)
            }
            "POST /api/reproducir" -> {
                val cat = conexion.catalogoLocal() ?: throw ErrorApi(409, "Aún no hay canales")
                val id = b.optString("id")
                val canal = cat.canales.firstOrNull { c -> c.enlaces.any { it.id == id } } ?: throw ErrorApi(404, "Ese canal ya no existe")
                val lista = b.optJSONArray("lista")?.let { a -> (0 until a.length()).mapNotNull { i -> cat.canales.firstOrNull { c -> c.enlaces.any { it.id == a.optString(i) } } } }?.ifEmpty { null } ?: listOf(canal)
                Sesion.canales = lista
                Sesion.indice = lista.indexOf(canal).coerceAtLeast(0)
                contexto.startActivity(Intent(contexto, ReproductorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return JSONObject().put("ok", true)
            }
        }
        throw ErrorApi(404, "No existe $ruta")
    }

    private fun borrar(ids: Set<String>): Int {
        val antes = canales().size
        runBlocking {
            conexion.modificarDatos { d ->
                val cs = d.optJSONArray("channels") ?: return@modificarDatos
                d.put("channels", JSONArray((0 until cs.length()).mapNotNull { cs.optJSONObject(it) }.filter { texto(it, "id") !in ids }))
            }
        }
        return antes - canales().size
    }
}
