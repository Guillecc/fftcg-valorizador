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
        val base: Map<Int, String>, // nombre base normalizado por productId
        val named: List<Pair<String, CardInfo>>, // " nombre base " (≥ 4 letras) para buscarlo dentro de un texto
        val prefixes: Set<String>, // prefijos de código conocidos ("1", "PR", "RE"…)
    )

    @Volatile private var index: Index? = null

    /** Correcciones aprendidas al escanear: clave escaneada ("PR-158") -> productId elegido por el usuario. */
    @Volatile private var overrides: Map<String, Int> = emptyMap()
    private const val OVERRIDES = "overrides"

    val ready get() = index != null
    val updated get() = index?.updated

    private fun file(ctx: Context) = File(ctx.filesDir, "index.json.gz")

    /** Carga la copia guardada (si hay). Llamar desde un hilo de IO. */
    fun load(ctx: Context) {
        val f = file(ctx)
        if (f.exists()) index = runCatching { parse(f) }.getOrNull()
        overrides = ctx.getSharedPreferences(OVERRIDES, Context.MODE_PRIVATE).all
            .mapNotNull { (k, v) -> (v as? Int)?.let { k to it } }.toMap()
    }

    /** Recuerda que [key] corresponde a la carta [id] (la eligió el usuario tras escanear). */
    fun remember(ctx: Context, key: String, id: Int) {
        val k = codeKey(key) ?: return
        overrides = overrides + (k to id)
        ctx.getSharedPreferences(OVERRIDES, Context.MODE_PRIVATE).edit().putInt(k, id).apply()
    }

    /** productId que el usuario asoció a esta clave, si la carta sigue en el índice. */
    fun overrideFor(key: String): Int? = overrides[key]?.takeIf { byId(it) != null }

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

    fun exists(key: String) = index?.byKey?.containsKey(key) == true || overrideFor(key) != null

    /** Prefijo con forma de código FFTCG: 1–2 dígitos, PR/RE/A/B/C/Wnn o uno que ya existe en el índice. */
    fun plausiblePrefix(prefix: String) =
        PREFIX.matches(prefix) || index?.prefixes?.contains(prefix) == true

    fun byId(id: Int): CardInfo? = index?.byId?.get(id)

    /**
     * Versiones de un código: primero la que el usuario asoció al escanear (si hay), luego las del set
     * que indica el código, y la base (nombre más corto) antes.
     */
    fun lookup(code: String): List<CardInfo> {
        val key = codeKey(code) ?: return emptyList()
        val prefix = key.substringBefore('-')
        val list = index?.byKey?.get(key).orEmpty().sortedWith(
            compareBy<CardInfo>(
                { if (it.setAbbr.uppercase() in listOf(prefix, "OP$prefix")) 0 else 1 },
                { it.name.length },
                { it.id },
            )
        )
        val pinned = overrideFor(key)?.let { byId(it) } ?: return list
        return listOf(pinned) + list.filter { it.id != pinned.id }
    }

    /** ¿Aparece el nombre base de la carta (sin versión ni sufijo de código) en el texto? */
    fun nameInText(card: CardInfo, text: String): Boolean {
        val n = index?.base?.get(card.id) ?: baseName(card.name)
        return n.isNotEmpty() && " ${words(text)} ".contains(" $n ")
    }

    /**
     * Cartas cuyo nombre base (≥ 4 letras) aparece completo en el texto (ej: lo leído por la cámara).
     * Primero las del prefijo [prefix], luego los nombres más largos (más específicos).
     */
    fun byNameInText(text: String, limit: Int, prefix: String? = null, exclude: Set<Int> = emptySet()): List<CardInfo> {
        val idx = index ?: return emptyList()
        val t = " ${words(text)} "
        if (t.isBlank()) return emptyList()
        return idx.named.filter { it.second.id !in exclude && t.contains(it.first) }
            .sortedWith(
                compareBy<Pair<String, CardInfo>>(
                    { p -> if (prefix != null && p.second.keys.any { it.substringBefore('-') == prefix }) 0 else 1 },
                    { p -> -p.first.length },
                    { p -> p.second.name.length },
                    { p -> p.second.id },
                )
            )
            .map { it.second }
            .take(limit)
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

    private val PREFIX = Regex("[1-9]\\d?|PR|RE|[ABC]|W\\d\\d")
    private val NON_WORD = Regex("[^a-z0-9]+")
    private val VERSION = Regex("\\s*[(\\[][^)\\]]*[)\\]]") // " (Full Art)", " [Foil]"
    private val CODE_SUFFIX = Regex("\\s+-\\s+\\S*\\d.*$") // " - PR-157"

    /** Minúsculas, sin acentos, solo letras y números separados por un espacio. */
    private fun words(s: String) = normalize(s).split(NON_WORD).filter { it.isNotEmpty() }.joinToString(" ")

    private fun baseName(name: String) = words(name.replace(VERSION, "").replace(CODE_SUFFIX, ""))

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
        val base = cards.associate { it.id to baseName(it.name) }
        return Index(
            updated = root.optString("updated"),
            byId = cards.associateBy { it.id },
            byKey = byKey,
            searchable = cards.map { normalize("${it.name} ${it.code} ${it.set}") to it },
            base = base,
            named = cards.mapNotNull { c -> base[c.id]?.takeIf { it.length >= 4 }?.let { " $it " to c } },
            prefixes = byKey.keys.map { it.substringBefore('-') }.toSet(),
        )
    }
}
