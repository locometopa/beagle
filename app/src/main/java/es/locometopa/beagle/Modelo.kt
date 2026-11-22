package es.locometopa.beagle

import org.json.JSONObject
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/** Un enlace de un canal: de Ace Stream (id) o directo (url, TV normal por internet). */
class Enlace(
    val id: String,
    val titulo: String,
    val kind: String,
    val url: String,
    val estado: String,
    val peers: Int,
) {
    val directo: Boolean get() = url.isNotEmpty()
    val vivo: Boolean get() = directo || estado == "alive"

    /** 0 = funciona, 1 = sin comprobar, 2 = caído la última vez que se miró. */
    val orden: Int get() = if (vivo) 0 else if (estado.isEmpty()) 1 else 2
}

/** Un canal tal como lo da el servidor de casa: ya agrupado, con todos sus enlaces. */
class Canal(
    val clave: String,
    val titulo: String,
    val categoria: String,
    var favorito: Boolean,
    val ahora: String,
    val luego: String,
    enlaces: List<Enlace>,
) {
    /** Los enlaces, con los que funcionan primero (dentro de cada grupo, en el orden del servidor). */
    val enlaces: List<Enlace> = enlaces.sortedBy { it.orden }
    val buscable: String = Modelo.sinTildes(titulo)

    /** Dirección de la imagen del logo (de los datos compartidos), o vacío. */
    var logo: String = ""
    val estado: Int get() = enlaces.firstOrNull()?.orden ?: 2
    val peers: Int get() = enlaces.maxOfOrNull { it.peers } ?: 0
}

/** Un evento de la agenda deportiva, ya cruzado por el servidor con los canales que tengo ([claves]). */
class Evento(
    val id: String,
    val inicio: Long,
    val titulo: String,
    val competicion: String,
    val deporte: String,
    val canales: List<String>,
    val gratis: List<String>,
    val aplazado: Boolean,
    val vivo: Boolean,
    val posible: Boolean,
    val apagada: Boolean,
    val claves: List<String>,
) {
    /** Se puede ver en abierto (alguna cadena gratuita lo emite). */
    val enAbierto: Boolean get() = gratis.isNotEmpty()
}

class Catalogo(val canales: List<Canal>, val categorias: List<String>, val eventos: List<Evento> = emptyList()) {
    private val porClave: Map<String, Canal> = canales.associateBy { it.clave }

    /** Mis canales que emiten el evento, con el mejor primero. */
    fun canalesDe(e: Evento): List<Canal> = e.claves.mapNotNull { porClave[it] }.sortedBy { if (it.estado == 2) 1 else 0 }  // se respeta el orden del servidor; solo los caídos al final
}

object Modelo {
    private val tildes = Regex("[\\u0300-\\u036f]")

    /** Minúsculas y sin tildes, para buscar («futbol» encuentra «Fútbol»). */
    fun sinTildes(t: String): String =
        tildes.replace(Normalizer.normalize(t, Normalizer.Form.NFD), "").lowercase(Locale.ROOT).trim()

    /** Nombres limpios, como en HomeTV: los «_» pasan a espacios. */
    fun limpio(t: String): String = t.replace('_', ' ').replace(Regex("\\s+"), " ").trim()

    /** Texto de un campo; cadena vacía si falta o es null (optString devolvería "null"). */
    private fun JSONObject.texto(nombre: String): String = if (isNull(nombre)) "" else optString(nombre, "")

    /** Lee la respuesta de /api/simple del servidor de casa. */
    fun leer(json: String): Catalogo {
        val raiz = JSONObject(json)
        val lista = raiz.optJSONArray("canales") ?: throw IllegalArgumentException("El servidor no ha mandado canales")
        val canales = ArrayList<Canal>(lista.length())
        for (i in 0 until lista.length()) {
            val c = lista.optJSONObject(i) ?: continue
            // Los canales de listas apagadas no se enseñan
            if (c.optBoolean("apagada", false)) continue
            val le = c.optJSONArray("enlaces") ?: continue
            val enlaces = ArrayList<Enlace>(le.length())
            for (j in 0 until le.length()) {
                val e = le.optJSONObject(j) ?: continue
                val id = e.texto("id")
                val url = e.texto("url")
                if (id.isEmpty() && url.isEmpty()) continue
                enlaces.add(Enlace(id, e.texto("titulo"), e.texto("kind"), url, e.texto("estado"), e.optInt("peers", 0)))
            }
            if (enlaces.isEmpty()) continue
            val titulo = limpio(c.texto("titulo").ifEmpty { enlaces[0].titulo }).ifEmpty { "Canal sin nombre" }
            canales.add(
                Canal(
                    clave = c.texto("clave").ifEmpty { enlaces[0].id + enlaces[0].url },
                    titulo = titulo,
                    categoria = c.texto("categoria"),
                    favorito = c.optBoolean("favorito", false),
                    ahora = c.texto("ahora"),
                    luego = c.texto("luego"),
                    enlaces = enlaces,
                )
            )
        }
        val orden = Collator.getInstance(Locale("es", "ES")).apply { strength = Collator.PRIMARY }
        val categorias = canales.map { it.categoria }.filter { it.isNotEmpty() }.distinct().sortedWith(orden)
        return Catalogo(canales, categorias, leerEventos(raiz))
    }

    private fun JSONObject.lista(nombre: String): List<String> {
        val a = optJSONArray(nombre) ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optString(i, "").ifEmpty { null } }
    }

    /** La agenda que trae /api/simple. Un evento mal formado se salta sin estropear el resto. */
    private fun leerEventos(raiz: JSONObject): List<Evento> {
        val a = raiz.optJSONArray("agenda") ?: return emptyList()
        val out = ArrayList<Evento>(a.length())
        for (i in 0 until a.length()) {
            val e = a.optJSONObject(i) ?: continue
            val inicio = e.optLong("inicio", 0)
            if (inicio <= 0) continue
            out.add(
                Evento(
                    id = e.texto("id"),
                    inicio = inicio,
                    titulo = limpio(e.texto("titulo")),
                    competicion = limpio(e.texto("competicion")),
                    deporte = limpio(e.texto("deporte")),
                    canales = e.lista("canales"),
                    gratis = e.lista("gratis"),
                    aplazado = e.optBoolean("aplazado", false),
                    vivo = e.optBoolean("vivo", false),
                    posible = e.optBoolean("posible", false),
                    apagada = e.optBoolean("apagada", false),
                    claves = e.lista("claves"),
                )
            )
        }
        return out.sortedBy { it.inicio }
    }

    /** Canales cuyo nombre contiene todas las palabras escritas. */
    fun buscar(canales: List<Canal>, texto: String): List<Canal> {
        val palabras = sinTildes(texto).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (palabras.isEmpty()) return emptyList()
        return canales.filter { c -> palabras.all { c.buscable.contains(it) } }
    }
}
