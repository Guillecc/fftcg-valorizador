package cl.guillecc.fftcg

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.text.Normalizer
import java.util.zip.GZIPInputStream

/** Precios de una terminación: mercado (ventas recientes) y más bajo publicado (null = sin stock). */
data class Finish(val market: Double?, val low: Double?)

/** Una impresión de una carta en TCGplayer (código + versión: base, Full Art, promo…). */
data class CardInfo(
    val id: Int,
    val code: String,
    val keys: List<String>,
    val name: String,
    val set: String,
    val setAbbr: String,
    val rarity: String,
    val normal: Finish?, // null = TCGplayer no tiene esa terminación
    val foil: Finish?,
) {
    /** Página de TCGplayer: ahí se ven las "Latest Sales". */
    val url get() = "https://www.tcgplayer.com/product/$id"
    val image get() = "https://tcgplayer-cdn.tcgplayer.com/product/${id}_in_400x400.jpg"

    /** Solo existe en foil (ej: promos Full Art). */
    val defaultFoil get() = normal == null && foil != null

    fun finish(foil: Boolean): Finish? = if (foil) this.foil else normal
    fun price(foil: Boolean): Double? = finish(foil)?.let { it.market ?: it.low }
    fun inStock(foil: Boolean): Boolean = finish(foil)?.low != null

    /** Búsqueda de vendidos en eBay. */
    fun ebayUrl(foil: Boolean): String {
        var q = "FFTCG ${code.substringBefore('/')} $name"
        if (foil && !name.contains("Full Art", ignoreCase = true)) q += " foil"
        return "https://www.ebay.com/sch/i.html?_nkw=${URLEncoder.encode(q, "UTF-8")}&LH_Sold=1&LH_Complete=1"
    }
}

/**
 * Índice de cartas y precios de mercado de TCGplayer. Lo genera .github/workflows/index.yml
 * cada día desde tcgcsv.com y lo publica en el release "index".
 */
object CardIndex {
    private const val INDEX_URL =
        "https://github.com/Guillecc/fftcg-valorizador/releases/download/index/index.json.gz"
    private const val MAX_AGE_MS = 12 * 60 * 60 * 1000L
    private val CODE = Regex("^([A-Z0-9]+)-+0*(\\d{1,4})[A-Z]?$")

    private class Index(
        val updated: String,
        val byId: Map<Int, CardInfo>,
        val byKey: Map<String, List<CardInfo>>,
        val searchable: List<Pair<String, CardInfo>>,
    )

    @Volatile private var index: Index? = null

    val ready get() = index != null
    val updated get() = index?.updated

    private fun file(ctx: Context) = File(ctx.filesDir, "index.json.gz")

    /** Carga la copia guardada (si hay). Llamar desde un hilo de IO. */
    fun load(ctx: Context) {
        val f = file(ctx)
        if (f.exists()) index = runCatching { parse(f) }.getOrNull()
    }

    /** Descarga el índice si falta o tiene más de 12 h. */
    fun refreshIfStale(ctx: Context, force: Boolean = false) {
        val f = file(ctx)
        if (!force && index != null && System.currentTimeMillis() - f.lastModified() < MAX_AGE_MS) return
        val tmp = File(ctx.filesDir, "index.json.gz.tmp")
        val conn = Net.open(INDEX_URL)
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        } finally {
            conn.disconnect()
        }
        val parsed = parse(tmp) // si viene corrupto, falla aquí y se conserva la copia anterior
        if (!tmp.renameTo(f)) throw IOException("No se pudo guardar el índice")
        index = parsed
    }

    /** "29-087L" -> "29-87": sin ceros a la izquierda ni letra de rareza. */
    fun codeKey(code: String): String? {
        val m = CODE.matchEntire(code.trim().uppercase()) ?: return null
        return "${m.groupValues[1]}-${m.groupValues[2].toInt()}"
    }

    fun exists(key: String) = index?.byKey?.containsKey(key) == true

    fun byId(id: Int): CardInfo? = index?.byId?.get(id)

    /** Versiones de un código: primero las del set que indica el código, y la base (nombre más corto) antes. */
    fun lookup(code: String): List<CardInfo> {
        val key = codeKey(code) ?: return emptyList()
        val prefix = key.substringBefore('-')
        return index?.byKey?.get(key).orEmpty().sortedWith(
            compareBy<CardInfo>(
                { if (it.setAbbr.uppercase() in listOf(prefix, "OP$prefix")) 0 else 1 },
                { it.name.length },
                { it.id },
            )
        )
    }

    /** Busca por código ("1-001H") o por palabras del nombre, sin acentos ni mayúsculas. */
    fun search(q: String): List<CardInfo> {
        val idx = index ?: return emptyList()
        if (q.isBlank()) return emptyList()
        if (codeKey(q) != null) return lookup(q)
        val words = normalize(q).split(' ').filter { it.isNotEmpty() }
        return idx.searchable.filter { (s, _) -> words.all { it in s } }
            .map { it.second }
            .sortedWith(compareBy({ it.name }, { it.code }))
            .take(200)
    }

    private fun normalize(s: String) =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

    /** "pn"/"ln" (o "pf"/"lf"): sin ninguna de las dos claves, la terminación no existe. */
    private fun finish(c: JSONObject, tag: String): Finish? {
        val p = "p$tag"
        val l = "l$tag"
        if (!c.has(p) && !c.has(l)) return null
        return Finish(
            market = if (c.isNull(p)) null else c.getDouble(p),
            low = if (c.isNull(l)) null else c.getDouble(l),
        )
    }

    private fun parse(f: File): Index {
        val root = JSONObject(GZIPInputStream(f.inputStream()).bufferedReader().use { it.readText() })
        val sets = root.getJSONObject("sets")
        val arr = root.getJSONArray("cards")
        val cards = (0 until arr.length()).map { i ->
            val c = arr.getJSONObject(i)
            val set = sets.optJSONArray(c.optInt("g").toString())
            val keys = c.getJSONArray("k")
            CardInfo(
                id = c.getInt("id"),
                code = c.optString("c"),
                keys = (0 until keys.length()).map { keys.getString(it) },
                name = c.optString("n"),
                set = set?.optString(0).orEmpty(),
                setAbbr = set?.optString(1).orEmpty(),
                rarity = c.optString("r"),
                normal = finish(c, "n"),
                foil = finish(c, "f"),
            )
        }
        val byKey = HashMap<String, MutableList<CardInfo>>()
        for (c in cards) for (k in c.keys) byKey.getOrPut(k) { mutableListOf() }.add(c)
        return Index(
            updated = root.optString("updated"),
            byId = cards.associateBy { it.id },
            byKey = byKey,
            searchable = cards.map { normalize("${it.name} ${it.code} ${it.set}") to it },
        )
    }
}
