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
    fun catalogoLocal(): Catalogo? = try { local.leerSimple()?.let { Modelo.leer(it) } } catch (e: Exception) { null }

    /** Pide los canales agrupados al servidor y los guarda. Lanza si el servidor no responde. */
    suspend fun cargarCatalogo(): Catalogo = withContext(Dispatchers.IO) {
        val equipo = ajustes.servidor
        if (equipo.isEmpty()) throw java.io.IOException("Falta la dirección del servidor de casa")
        val texto = Servidor.pedir(Servidor.casa(equipo) + "/api/simple", 30000)
        val c = Modelo.leer(texto)
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
    suspend fun modificarLista(id: String, cambio: (org.json.JSONObject) -> Unit) = withContext(Dispatchers.IO) {
        val equipo = servidorOError()
        val ident = ajustes.identidad()
        var copia = local.leer() ?: throw java.io.IOException("Aún no hay datos de la tele: abre Ajustes para sincronizar")
        repeat(3) {
            val fuentes = copia.datos.optJSONArray("sources") ?: throw java.io.IOException("No hay listas")
            var hecho = false
            for (i in 0 until fuentes.length()) {
                val f = fuentes.optJSONObject(i)
                if (f != null && f.optString("id", "") == id) { cambio(f); hecho = true }
            }
            if (!hecho) throw java.io.IOException("Esa lista ya no existe")
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

    suspend fun carpetas(): List<Carpeta> = withContext(Dispatchers.IO) { Servidor.carpetas(servidorOError()) }
    suspend fun listarCarpeta(id: String, sub: String): Listado = withContext(Dispatchers.IO) { Servidor.listarCarpeta(servidorOError(), id, sub) }
    suspend fun cambiarVpn(usar: String) = withContext(Dispatchers.IO) { Servidor.cambiarVpn(servidorOError(), usar) }

    /** Lo que dice el panel de esta tele: si está bloqueada (mensaje a pantalla completa) o si pide actualizar. Lanza si el servidor no responde. */
    suspend fun bloqueo(): Bloqueo = withContext(Dispatchers.IO) { Servidor.bloqueo(servidorOError(), ajustes.identidad()) }

    private fun servidorOError(): String =
        ajustes.servidor.ifEmpty { throw java.io.IOException("Falta la dirección del servidor de casa") }

    /** ¿El motor está en la propia tele? */
    suspend fun grabar(canal: Canal, minutos: Int): String = withContext(Dispatchers.IO) { Servidor.grabar(servidorOError(), canal, minutos) }
    suspend fun buscarApagados(q: String): List<Pair<String, String>> = withContext(Dispatchers.IO) { Servidor.buscarApagados(servidorOError(), q) }
    suspend fun copiaAhora(): String = withContext(Dispatchers.IO) { Servidor.copiaAhora(servidorOError(), ajustes.identidad()) }
    suspend fun copias(): List<String> = withContext(Dispatchers.IO) { Servidor.copias(servidorOError()) }
    /** Borra lo guardado en esta tele (copias locales, vistos, última sección); lo del servidor no se toca. */
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
