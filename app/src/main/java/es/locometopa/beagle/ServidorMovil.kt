package es.locometopa.beagle

import android.content.res.AssetManager
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Servidor HTTP mínimo del panel del móvil (el mismo de HomeTV, puerto 8095).
 * Sirve la página (assets/movil) y pasa cada /api/... al manejador, comprobando antes la clave.
 */
class ServidorMovil(private val assets: AssetManager, private val manejador: (String, String, String) -> Pair<Int, String>) {

    @Volatile private var socket: ServerSocket? = null
    @Volatile private var token: String = ""
    private var pool: ExecutorService? = null
    private var puerto = 0

    val activo: Boolean get() = socket?.isClosed == false

    @Synchronized
    fun iniciar(puertoPedido: Int, nuevoToken: String): Int {
        token = nuevoToken
        val s0 = socket
        if (s0 != null && !s0.isClosed && puerto == puertoPedido) return puerto
        detener()
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(puertoPedido))
        socket = s
        puerto = puertoPedido
        val p = Executors.newFixedThreadPool(6)
        pool = p
        Thread({
            while (!s.isClosed) {
                try {
                    val cliente = s.accept()
                    p.execute { atender(cliente) }
                } catch (e: Exception) { /* socket cerrado */ }
            }
        }, "BeagleMovil").also { it.isDaemon = true }.start()
        return puerto
    }

    @Synchronized
    fun detener() {
        try { socket?.close() } catch (e: Exception) { }
        socket = null
        pool?.shutdownNow()
        pool = null
    }

    private fun atender(cliente: Socket) {
        try {
            cliente.use { c ->
                c.soTimeout = 30000
                val entrada = c.getInputStream()
                val salida = c.getOutputStream()
                val cabecera = leerCabecera(entrada) ?: return
                val lineas = cabecera.split("\r\n")
                val primera = lineas[0].split(" ")
                if (primera.size < 2) { enviar(salida, 400, "text/plain", "petición no válida"); return }
                val metodo = primera[0].uppercase(Locale.ROOT)
                val destino = primera[1]
                val headers = HashMap<String, String>()
                for (i in 1 until lineas.size) {
                    val dp = lineas[i].indexOf(':')
                    if (dp > 0) headers[lineas[i].substring(0, dp).trim().lowercase(Locale.ROOT)] = lineas[i].substring(dp + 1).trim()
                }
                var ruta = destino
                var consulta = ""
                val q = destino.indexOf('?')
                if (q >= 0) { ruta = destino.substring(0, q); consulta = destino.substring(q + 1) }

                if (ruta.startsWith("/api/")) {
                    val clave = headers["x-token"] ?: parametro(consulta, "t")
                    if (!claveCorrecta(clave)) {
                        enviar(salida, 401, "application/json; charset=utf-8", "{\"error\":\"Clave incorrecta: vuelve a escanear el código de la tele\"}")
                        return
                    }
                    val largo = headers["content-length"]?.toIntOrNull() ?: 0
                    if (largo > 25 * 1024 * 1024) { enviar(salida, 413, "application/json", "{\"error\":\"Demasiado grande\"}"); return }
                    val cuerpo = String(leerExacto(entrada, largo), Charsets.UTF_8)
                    val r = try { manejador(metodo, ruta, cuerpo) } catch (e: Exception) { 500 to "{\"error\":\"${(e.message ?: "Error").replace("\"", "'")}\"}" }
                    enviar(salida, r.first, "application/json; charset=utf-8", r.second)
                    return
                }
                if (metodo != "GET" && metodo != "HEAD") { enviar(salida, 405, "text/plain", "método no permitido"); return }
                servirArchivo(salida, ruta)
            }
        } catch (e: Exception) { /* conexión cortada */ }
    }

    private fun claveCorrecta(clave: String?): Boolean {
        val t = token
        if (t.isEmpty() || clave == null) return false
        return MessageDigest.isEqual(t.toByteArray(), clave.toByteArray())
    }

    private fun servirArchivo(salida: OutputStream, ruta: String) {
        var nombre = if (ruta == "/" || Regex("/[A-Za-z0-9]{6,16}/?").matches(ruta)) "index.html" else ruta.substring(1)
        try { nombre = URLDecoder.decode(nombre, "UTF-8") } catch (e: Exception) { }
        if (nombre.contains("..") || nombre.startsWith("/")) { enviar(salida, 403, "text/plain", "prohibido"); return }
        val datos = try { assets.open("movil/$nombre").use { it.readBytes() } } catch (e: Exception) { enviar(salida, 404, "text/plain", "no encontrado"); return }
        enviarBytes(salida, 200, tipo(nombre), datos)
    }

    private fun tipo(nombre: String): String {
        val n = nombre.lowercase(Locale.ROOT)
        return when {
            n.endsWith(".html") -> "text/html; charset=utf-8"
            n.endsWith(".js") -> "text/javascript; charset=utf-8"
            n.endsWith(".css") -> "text/css; charset=utf-8"
            n.endsWith(".svg") -> "image/svg+xml"
            n.endsWith(".png") -> "image/png"
            n.endsWith(".woff2") -> "font/woff2"
            n.endsWith(".json") -> "application/json"
            else -> "application/octet-stream"
        }
    }

    private fun parametro(consulta: String, nombre: String): String? {
        for (par in consulta.split("&")) {
            val eq = par.indexOf('=')
            if (eq > 0 && par.substring(0, eq) == nombre) return try { URLDecoder.decode(par.substring(eq + 1), "UTF-8") } catch (e: Exception) { null }
        }
        return null
    }

    private fun leerCabecera(entrada: InputStream): String? {
        val buf = ByteArrayOutputStream()
        var a = 0; var b = 0; var c = 0
        while (true) {
            val d = entrada.read()
            if (d < 0) return null
            buf.write(d)
            if (buf.size() > 32 * 1024) return null
            if (a == '\r'.code && b == '\n'.code && c == '\r'.code && d == '\n'.code) break
            a = b; b = c; c = d
        }
        return String(buf.toByteArray(), Charsets.ISO_8859_1).trim()
    }

    private fun leerExacto(entrada: InputStream, largo: Int): ByteArray {
        val datos = ByteArray(largo)
        var leido = 0
        while (leido < largo) {
            val n = entrada.read(datos, leido, largo - leido)
            if (n < 0) break
            leido += n
        }
        return datos
    }

    private fun enviar(salida: OutputStream, status: Int, tipo: String, texto: String) = enviarBytes(salida, status, tipo, texto.toByteArray(Charsets.UTF_8))

    private fun enviarBytes(salida: OutputStream, status: Int, tipo: String, datos: ByteArray) {
        val razon = when (status) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 409 -> "Conflict"; 413 -> "Payload Too Large"; 503 -> "Service Unavailable"; else -> "Error" }
        val cab = "HTTP/1.1 $status $razon\r\nContent-Type: $tipo\r\nContent-Length: ${datos.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        salida.write(cab.toByteArray(Charsets.ISO_8859_1))
        salida.write(datos)
        salida.flush()
    }

    companion object {
        /** La IP de esta tele en la red de casa. */
        fun ipLocal(): String {
            var otra: String? = null
            try {
                for (ni in NetworkInterface.getNetworkInterfaces().toList()) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (a in ni.inetAddresses.toList()) {
                        if (a.isLoopbackAddress || a.address.size != 4) continue
                        val ip = a.hostAddress ?: continue
                        if (ip.startsWith("192.168.")) return ip
                        if (otra == null && a.isSiteLocalAddress) otra = ip
                    }
                }
            } catch (e: Exception) { }
            return otra ?: "127.0.0.1"
        }
    }
}
