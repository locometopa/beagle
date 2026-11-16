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

    suspend fun carpetas(): List<Carpeta> = withContext(Dispatchers.IO) { Servidor.carpetas(servidorOError()) }
    suspend fun listarCarpeta(id: String, sub: String): Listado = withContext(Dispatchers.IO) { Servidor.listarCarpeta(servidorOError(), id, sub) }
    suspend fun cambiarVpn(usar: String) = withContext(Dispatchers.IO) { Servidor.cambiarVpn(servidorOError(), usar) }

    /** Lo que dice el panel de esta tele: si está bloqueada (mensaje a pantalla completa) o si pide actualizar. Lanza si el servidor no responde. */
    suspend fun bloqueo(): Bloqueo = withContext(Dispatchers.IO) { Servidor.bloqueo(servidorOError(), ajustes.identidad()) }

    private fun servidorOError(): String =
        ajustes.servidor.ifEmpty { throw java.io.IOException("Falta la dirección del servidor de casa") }

    /** ¿El motor está en la propia tele? */
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
