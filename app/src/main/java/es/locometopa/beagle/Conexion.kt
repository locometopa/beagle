package es.locometopa.beagle

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** En qué punto está Beagle respecto al servidor de casa. */
sealed interface Estado {
    /** Aún no se ha puesto la dirección del servidor. */
    data object SinServidor : Estado
    /** Recorriendo la red local para encontrarlo. */
    data object Buscando : Estado
    /** Preguntando al servidor. */
    data object Comprobando : Estado
    /** El servidor responde. [copia] son los datos compartidos (de ahora, o la copia local si falló la lectura). */
    class Conectado(val salud: Salud, val copia: Copia?, val aviso: String?) : Estado
    /** No responde: se sigue con la copia local, si la hay. */
    class NoResponde(val motivo: String, val copia: Copia?) : Estado
}

/**
 * Une el servidor, los ajustes y la copia local: lo que necesita la pantalla en cuatro pasos
 * (poner la dirección, buscarla en la red, conectar, sincronizar).
 */
class Conexion(private val ajustes: Ajustes, private val local: CopiaLocal) {

    /** La copia que hay en la tele, para abrir al instante antes de hablar con nadie. */
    fun copiaLocal(): Copia? = local.leer()

    /** Dirección ya guardada, o vacía. */
    fun servidor(): String = ajustes.servidor

    /** Guarda la dirección que se ha escrito (admite una URL entera). Devuelve la dirección limpia. */
    fun ponerServidor(texto: String): String {
        val limpio = Servidor.normalizar(texto)
        ajustes.servidor = limpio
        return limpio
    }

    /** Busca el servidor en la red local y, si lo encuentra, lo recuerda. */
    suspend fun buscar(): String? = withContext(Dispatchers.IO) {
        val ip = Servidor.buscarEnRed()
        if (ip != null) ajustes.servidor = ip
        ip
    }

    /**
     * Comprueba el servidor y trae lo compartido. Si ya hay copia local, solo se descarga lo nuevo (`desde`).
     * Nunca lanza: si algo falla, el estado dice qué y se conserva la copia que hubiera.
     */
    suspend fun sincronizar(): Estado = withContext(Dispatchers.IO) {
        val equipo = ajustes.servidor
        val previa = local.leer()
        if (equipo.isEmpty()) return@withContext Estado.SinServidor
        val salud = try {
            Servidor.salud(equipo)
        } catch (e: Exception) {
            return@withContext Estado.NoResponde(motivoLegible(e, equipo), previa)
        }
        try {
            val s = Servidor.leerSincro(equipo, ajustes.identidad(), previa?.rev ?: 0, ajustes.sincroLigero)
            when {
                s.sinCambios && previa != null -> Estado.Conectado(salud, previa, null)
                s.datos != null -> {
                    val nueva = Copia(s.rev, s.datos, s.ligero)
                    local.guardar(nueva)
                    Estado.Conectado(salud, nueva, null)
                }
                // El servidor aún no tiene canales compartidos (nadie ha sincronizado desde ninguna tele)
                else -> Estado.Conectado(salud, previa, "El servidor todavía no tiene canales compartidos")
            }
        } catch (e: Exception) {
            Estado.Conectado(salud, previa, "No se han podido leer los canales: ${motivoLegible(e, equipo)}")
        }
    }

    /**
     * Sube los datos cambiados. Si otra tele guardó antes, se carga lo suyo en la copia local y se devuelve
     * [Guardado.Conflicto] para que quien llama rehaga el cambio sobre lo nuevo.
     */
    suspend fun guardar(copia: Copia): Guardado = withContext(Dispatchers.IO) {
        val equipo = ajustes.servidor
        val r = Servidor.guardarSincro(equipo, ajustes.identidad(), copia.rev, copia.datos, copia.ligero)
        when (r) {
            is Guardado.Hecho -> local.guardar(Copia(r.rev, copia.datos, copia.ligero))
            is Guardado.Conflicto -> r.actual.datos?.let { local.guardar(Copia(r.actual.rev, it, r.actual.ligero)) }
        }
        r
    }

    /** Canales ya agrupados que guardó la última vez la tele, para abrir al instante. Null si no hay. */
    fun catalogoLocal(): Catalogo? = try { local.leerSimple()?.let { conLogos(Modelo.leer(it)) } } catch (e: Exception) { null }

    /** Pone a cada canal el logo que traen sus enlaces en los datos compartidos (el servidor no lo manda en /api/simple). */
    private fun conLogos(c: Catalogo): Catalogo {
        try {
            val cs = local.leer()?.datos?.optJSONArray("channels") ?: return c
            val logos = HashMap<String, String>()
            for (i in 0 until cs.length()) {
                val o = cs.optJSONObject(i) ?: continue
                val l = o.optString("logo", "")
                if (l.startsWith("http")) logos[o.optString("id", "")] = l
            }
            if (logos.isEmpty()) return c
            for (canal in c.canales) canal.logo = canal.enlaces.firstNotNullOfOrNull { logos[it.id] } ?: ""
        } catch (e: Exception) { /* sin logos */ }
        return c
    }

    /** Pide los canales agrupados al servidor y los guarda. Lanza si el servidor no responde. */
    suspend fun cargarCatalogo(): Catalogo = withContext(Dispatchers.IO) {
        val equipo = ajustes.servidor
        if (equipo.isEmpty()) throw java.io.IOException("Falta la dirección del servidor de casa")
        val texto = Servidor.pedir(Servidor.casa(equipo) + "/api/simple", 30000)
        val c = conLogos(Modelo.leer(texto))
        local.guardarSimple(texto)
        c
    }

    /** Marca o quita un favorito en el servidor. */
    suspend fun favorito(canal: Canal, poner: Boolean) = withContext(Dispatchers.IO) {
        Servidor.favorito(ajustes.servidor, canal, poner)
    }

    /** Una lista de canales, tal como está en los datos compartidos. */
    class Lista(val id: String, val nombre: String, val encendida: Boolean, val canales: Int, val deSerie: Boolean)

    /** Las listas que hay en la copia local, con cuántos canales trae cada una. */
    fun listas(): List<Lista> {
        val datos = local.leer()?.datos ?: return emptyList()
        val cuenta = HashMap<String, Int>()
        datos.optJSONArray("channels")?.let { cs ->
            for (i in 0 until cs.length()) {
                val ls = cs.optJSONObject(i)?.optJSONArray("listas") ?: continue
                for (j in 0 until ls.length()) { val id = ls.optString(j, ""); if (id.isNotEmpty()) cuenta[id] = (cuenta[id] ?: 0) + 1 }
            }
        }
        val fuentes = datos.optJSONArray("sources") ?: return emptyList()
        return (0 until fuentes.length()).mapNotNull { i ->
            val f = fuentes.optJSONObject(i) ?: return@mapNotNull null
            val id = f.optString("id", "")
            if (id.isEmpty()) null else Lista(id, f.optString("name", "").ifEmpty { "Lista sin nombre" }, f.optBoolean("enabled", true), cuenta[id] ?: 0, f.optBoolean("builtin", false))
        }
    }

    /**
     * Cambia algo de una lista (encenderla, apagarla, renombrarla) y lo sube al servidor.
     * Si otra tele guardó antes, se carga lo suyo y se repite el cambio encima, hasta tres veces.
     */
    suspend fun modificarLista(id: String, cambio: (org.json.JSONObject) -> Unit) = modificarDatos { datos ->
        val fuentes = datos.optJSONArray("sources") ?: throw java.io.IOException("No hay listas")
        var hecho = false
        for (i in 0 until fuentes.length()) {
            val f = fuentes.optJSONObject(i)
            if (f != null && f.optString("id", "") == id) { cambio(f); hecho = true }
        }
        if (!hecho) throw java.io.IOException("Esa lista ya no existe")
    }

    /** Cambia los enlaces de un canal (por sus ids) en los datos compartidos: renombrar, cambiar de categoría u ocultar. */
    suspend fun cambiarCanal(ids: Set<String>, cambio: (org.json.JSONObject) -> Unit) = modificarDatos { datos ->
        val cs = datos.optJSONArray("channels") ?: throw java.io.IOException("No hay canales")
        var n = 0
        for (i in 0 until cs.length()) {
            val c = cs.optJSONObject(i) ?: continue
            if (c.optString("id", "") in ids) { cambio(c); n++ }
        }
        if (n == 0) throw java.io.IOException("Este canal no se puede cambiar desde aquí (es un enlace directo)")
    }

    /** Junta las categorías que solo se diferencian en mayúsculas o tildes («MOTOR» y «Motor»). Devuelve cuántos canales cambian. */
    suspend fun unificarCategorias(): Int {
        var total = 0
        modificarDatos { d ->
            total = 0
            val cs = d.optJSONArray("channels") ?: return@modificarDatos
            val lista = (0 until cs.length()).mapNotNull { cs.optJSONObject(it) }.filter { it.optString("category", "").isNotEmpty() }
            for ((_, g) in lista.groupBy { Modelo.sinTildes(it.optString("category")).trim().lowercase() }) {
                val nombres = g.map { it.optString("category") }
                if (nombres.distinct().size < 2) continue
                val buena = nombres.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
                for (c in g) if (c.optString("category") != buena) { c.put("category", buena); total++ }
            }
        }
        return total
    }

    /** Vuelve a bajar las listas que vienen de una dirección web (las baja el servidor). Devuelve los canales nuevos. */
    suspend fun actualizarListas(): Int {
        var nuevos = 0
        val fs = local.leer()?.datos?.optJSONArray("sources") ?: return 0
        for (i in 0 until fs.length()) {
            val f = fs.optJSONObject(i) ?: continue
            val url = f.optString("url", "")
            if (url.isEmpty() || f.optBoolean("builtin") || !f.optBoolean("enabled", true)) continue
            try { nuevos += anadirLista(f.optString("name", "Lista"), url).creados } catch (e: Exception) { /* sigue con las demás */ }
        }
        return nuevos
    }

    /** Avisos de eventos (`recordatorios` compartido): id, inicio, titulo, avisado. */
    class Aviso(val id: String, val inicio: Long, val titulo: String, val avisado: Boolean)

    fun avisos(): List<Aviso> {
        val a = local.leer()?.datos?.optJSONArray("recordatorios") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val r = a.optJSONObject(i) ?: return@mapNotNull null
            Aviso(r.optString("id", ""), r.optLong("inicio", 0), r.optString("titulo", ""), r.optBoolean("avisado", false))
        }.filter { it.id.isNotEmpty() }
    }

    /** Pone o quita el aviso de un evento. Devuelve true si ha quedado puesto. */
    suspend fun alternarAviso(e: Evento): Boolean {
        var poner = false
        modificarDatos { d ->
            val a = d.optJSONArray("recordatorios") ?: org.json.JSONArray()
            val resto = org.json.JSONArray()
            var estaba = false
            for (i in 0 until a.length()) { val r = a.optJSONObject(i) ?: continue; if (r.optString("id") == e.id) estaba = true else resto.put(r) }
            poner = !estaba
            if (poner) resto.put(org.json.JSONObject().put("id", e.id).put("inicio", e.inicio).put("titulo", e.titulo).put("avisado", false))
            d.put("recordatorios", resto)
        }
        return poner
    }

    /** Marca un aviso como ya enseñado y quita los que pasaron hace más de tres horas. */
    suspend fun avisoMostrado(id: String) = modificarDatos { d ->
        val a = d.optJSONArray("recordatorios") ?: return@modificarDatos
        val limite = System.currentTimeMillis() - 3 * 3600_000L
        val nuevo = org.json.JSONArray()
        for (i in 0 until a.length()) {
            val r = a.optJSONObject(i) ?: continue
            if (r.optLong("inicio", 0) < limite) continue
            if (r.optString("id") == id) r.put("avisado", true)
            nuevo.put(r)
        }
        d.put("recordatorios", nuevo)
    }

    /** ¿Tiene el servidor un vídeo de entrada (`/intro.mp4`)? */
    suspend fun hayIntro(): Boolean = withContext(Dispatchers.IO) {
        val equipo = ajustes.servidor
        if (equipo.isEmpty()) return@withContext false
        try {
            val c = java.net.URL(Servidor.casa(equipo) + "/intro.mp4").openConnection() as java.net.HttpURLConnection
            try { c.requestMethod = "HEAD"; c.connectTimeout = 3000; c.readTimeout = 3000; c.responseCode in 200..299 } finally { c.disconnect() }
        } catch (e: Exception) { false }
    }

    fun urlIntro(): String = Servidor.casa(ajustes.servidor) + "/intro.mp4"

    /** Un ajuste compartido entre teles (en `ajustes` de los datos del servidor), leído de la copia local. */
    fun ajusteCompartido(clave: String, porDefecto: Boolean): Boolean = local.leer()?.datos?.optJSONObject("ajustes")?.optBoolean(clave, porDefecto) ?: porDefecto

    suspend fun ponerAjusteCompartido(clave: String, valor: Boolean) = modificarDatos { datos ->
        val aj = datos.optJSONObject("ajustes") ?: org.json.JSONObject().also { datos.put("ajustes", it) }
        aj.put(clave, valor)
    }

    suspend fun mantenimiento(caidos: Boolean): Int = withContext(Dispatchers.IO) { Servidor.mantenimiento(servidorOError(), caidos) }

    /** Equipos favoritos (`equipos` compartido). */
    fun equipos(): List<String> {
        val a = local.leer()?.datos?.optJSONObject("ajustes")?.optJSONArray("equipos") ?: return emptyList()
        return (0 until a.length()).map { a.optString(it, "") }.filter { it.isNotBlank() }
    }

    suspend fun ponerEquipos(lista: List<String>) = modificarDatos { datos ->
        val aj = datos.optJSONObject("ajustes") ?: org.json.JSONObject().also { datos.put("ajustes", it) }
        aj.put("equipos", org.json.JSONArray(lista))
    }

    /** Pone el aviso de un evento solo si no estaba (para los equipos favoritos). */
    suspend fun ponerAviso(e: Evento) { if (avisos().none { it.id == e.id }) alternarAviso(e) }

    /** Categorías ocultas (`catOcultas` compartido), sin tildes ni mayúsculas para compararlas. */
    fun catOcultas(): Set<String> {
        val a = local.leer()?.datos?.optJSONObject("ajustes")?.optJSONArray("catOcultas") ?: return emptySet()
        return (0 until a.length()).map { Modelo.sinTildes(a.optString(it, "")) }.toSet()
    }

    suspend fun ponerCatOcultas(nombres: List<String>) = modificarDatos { datos ->
        val aj = datos.optJSONObject("ajustes") ?: org.json.JSONObject().also { datos.put("ajustes", it) }
        aj.put("catOcultas", org.json.JSONArray(nombres))
    }

    /** Fija a mano qué canal da un evento de la agenda (`agendaMapa`: nombre de la cadena → ids de mis canales). */
    suspend fun asignarAgenda(nombre: String, ids: List<String>) = modificarDatos { datos ->
        val aj = datos.optJSONObject("ajustes") ?: org.json.JSONObject().also { datos.put("ajustes", it) }
        val mapa = aj.optJSONObject("agendaMapa") ?: org.json.JSONObject().also { aj.put("agendaMapa", it) }
        mapa.put(nombre, org.json.JSONArray(ids))
    }

    /** Ids de los canales (enlaces) que vienen de una lista, según la copia local. */
    fun idsDeLista(id: String): Set<String> {
        val cs = local.leer()?.datos?.optJSONArray("channels") ?: return emptySet()
        val out = HashSet<String>()
        for (i in 0 until cs.length()) {
            val c = cs.optJSONObject(i) ?: continue
            val ls = c.optJSONArray("listas") ?: continue
            for (j in 0 until ls.length()) if (ls.optString(j, "") == id) { out.add(c.optString("id", "")); break }
        }
        return out
    }

    /** Quita una lista y los canales que solo venían de ella (los que tienen otra lista, o se añadieron a mano, se quedan). */
    suspend fun quitarLista(id: String) = modificarDatos { datos ->
        val fuentes = datos.optJSONArray("sources") ?: throw java.io.IOException("No hay listas")
        val nuevas = org.json.JSONArray()
        for (i in 0 until fuentes.length()) { val f = fuentes.optJSONObject(i); if (f != null && f.optString("id", "") != id) nuevas.put(f) }
        datos.put("sources", nuevas)
        val canales = datos.optJSONArray("channels") ?: return@modificarDatos
        val quedan = org.json.JSONArray()
        for (i in 0 until canales.length()) {
            val c = canales.optJSONObject(i) ?: continue
            val ls = c.optJSONArray("listas")
            if (ls == null) { quedan.put(c); continue }
            val resto = org.json.JSONArray()
            var estaba = false
            for (j in 0 until ls.length()) { if (ls.optString(j, "") == id) estaba = true else resto.put(ls.optString(j, "")) }
            if (!estaba) quedan.put(c)
            else if (resto.length() > 0) { c.put("listas", resto); quedan.put(c) }
        }
        datos.put("channels", quedan)
    }

    /** Cambia los datos compartidos con [cambio] y los sube al servidor; si otra tele guardó antes, repite el cambio encima de lo suyo (hasta tres veces). */
    suspend fun modificarDatos(cambio: (org.json.JSONObject) -> Unit) = withContext(Dispatchers.IO) {
        val equipo = servidorOError()
        val ident = ajustes.identidad()
        var copia = local.leer() ?: throw java.io.IOException("Aún no hay datos de la tele: abre Ajustes para sincronizar")
        repeat(3) {
            cambio(copia.datos)
            when (val r = Servidor.guardarSincro(equipo, ident, copia.rev, copia.datos, copia.ligero)) {
                is Guardado.Hecho -> {
                    local.guardar(Copia(r.rev, copia.datos, copia.ligero))
                    // Al encender una lista, el servidor tiene canales que esta tele aún no recibió: se piden
                    if (r.recargar) {
                        val s = Servidor.leerSincro(equipo, ident, 0, ajustes.sincroLigero)
                        s.datos?.let { local.guardar(Copia(s.rev, it, s.ligero)) }
                    }
                    return@withContext
                }
                is Guardado.Conflicto -> {
                    val d = r.actual.datos ?: throw java.io.IOException("El servidor no ha devuelto datos")
                    copia = Copia(r.actual.rev, d, r.actual.ligero)
                    local.guardar(copia)
                }
            }
        }
        throw java.io.IOException("Otra tele está cambiando lo mismo; prueba otra vez")
    }

    /** Añade una lista desde una dirección web (la baja el servidor) y refresca la copia local. */
    suspend fun anadirLista(nombre: String, url: String): Servidor.ListaAnadida = withContext(Dispatchers.IO) {
        val equipo = servidorOError()
        val r = Servidor.anadirLista(equipo, nombre.trim().ifEmpty { "Lista" }, url.trim())
        val s = Servidor.leerSincro(equipo, ajustes.identidad(), 0, ajustes.sincroLigero)
        s.datos?.let { local.guardar(Copia(s.rev, it, s.ligero)) }
        r
    }

    /** Añade una lista desde texto pegado y refresca la copia local. */
    suspend fun anadirListaTexto(nombre: String, texto: String): Servidor.ListaAnadida = withContext(Dispatchers.IO) {
        val equipo = servidorOError()
        val r = Servidor.anadirListaTexto(equipo, nombre.trim().ifEmpty { "Lista pegada" }, texto)
        val s = Servidor.leerSincro(equipo, ajustes.identidad(), 0, ajustes.sincroLigero)
        s.datos?.let { local.guardar(Copia(s.rev, it, s.ligero)) }
        r
    }

    suspend fun carpetas(): List<Carpeta> = withContext(Dispatchers.IO) { Servidor.carpetas(servidorOError()) }
    suspend fun listarCarpeta(id: String, sub: String): Listado = withContext(Dispatchers.IO) { Servidor.listarCarpeta(servidorOError(), id, sub) }
    suspend fun cambiarPaisVpn(pais: String) = withContext(Dispatchers.IO) { Servidor.cambiarPaisVpn(servidorOError(), pais) }
    suspend fun cambiarVpn(usar: String) = withContext(Dispatchers.IO) { Servidor.cambiarVpn(servidorOError(), usar) }

    /** Lo que dice el panel de esta tele: si está bloqueada (mensaje a pantalla completa) o si pide actualizar. Lanza si el servidor no responde. */
    suspend fun bloqueo(): Bloqueo = withContext(Dispatchers.IO) { Servidor.bloqueo(servidorOError(), ajustes.identidad()) }

    private fun servidorOError(): String =
        ajustes.servidor.ifEmpty { throw java.io.IOException("Falta la dirección del servidor de casa") }

    /** ¿El motor está en la propia tele? */
    suspend fun informeIncidencias(): String = withContext(Dispatchers.IO) { Servidor.informeIncidencias(servidorOError()) }
    suspend fun borrarIncidencias() = withContext(Dispatchers.IO) { Servidor.borrarIncidencias(servidorOError()) }
    suspend fun grabar(canal: Canal, minutos: Int): String = withContext(Dispatchers.IO) { Servidor.grabar(servidorOError(), canal, minutos) }
    suspend fun buscarMotor(q: String): List<Array<String>> = withContext(Dispatchers.IO) { Servidor.buscarMotor(servidorOError(), q) }
    suspend fun buscarApagados(q: String): List<Pair<String, String>> = withContext(Dispatchers.IO) { Servidor.buscarApagados(servidorOError(), q) }
    suspend fun copiaAhora(): String = withContext(Dispatchers.IO) { Servidor.copiaAhora(servidorOError(), ajustes.identidad()) }
    suspend fun copias(): List<String> = withContext(Dispatchers.IO) { Servidor.copias(servidorOError()) }
    /** Borra lo guardado en esta tele (copias locales, vistos, última sección); lo del servidor no se toca. */
    suspend fun saludServidor(): Salud = withContext(Dispatchers.IO) { Servidor.salud(servidorOError(), 4000) }

    suspend fun comprobacion(): Comprobacion = withContext(Dispatchers.IO) { Servidor.comprobacion(servidorOError()) }
    suspend fun comprobar(empezar: Boolean) = withContext(Dispatchers.IO) { Servidor.comprobar(servidorOError(), empezar) }
    suspend fun comprobarIds(ids: List<String>) = withContext(Dispatchers.IO) { Servidor.comprobarIds(servidorOError(), ids) }
    suspend fun restaurar(nombre: String) = withContext(Dispatchers.IO) { Servidor.restaurar(servidorOError(), nombre) }

    fun empezarDeCero() { local.borrarTodo(); ajustes.vistos = emptyList(); ajustes.ultimaSeccion = "" }

    fun motorEnTele(): Boolean = ajustes.motorEnTele

    /** Cambia entre el motor del servidor de casa y el de la propia tele. */
    fun ponerMotorEnTele(valor: Boolean) { ajustes.motorEnTele = valor }

    /** Pregunta la versión al motor que toca según el ajuste. Devuelve un texto para enseñar, o lanza si no responde. */
    suspend fun comprobarMotor(): String = withContext(Dispatchers.IO) {
        val url = ajustes.urlMotor() ?: throw java.io.IOException("Primero hay que poner el servidor de casa")
        val v = Motor(url, 8000).version()
        "Motor Ace Stream ${v.version}" + (v.plataforma?.let { " ($it)" } ?: "")
    }

    private fun motivoLegible(e: Exception, equipo: String): String = when (e) {
        is java.net.UnknownHostException -> "No se encuentra «$equipo» en la red"
        is java.net.SocketTimeoutException, is java.net.ConnectException ->
            if (Servidor.fueraDeCasa(equipo)) "No responde por Tailscale: ¿está conectado?" else "No responde: ¿está encendido el servidor?"
        else -> e.message ?: "Error desconocido"
    }
}
