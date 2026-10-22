package cl.guillecc.fftcg

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Una línea de la colección: impresión de TCGplayer (productId), foil y copias.
 * [manual] es un precio en USD por copia anotado a mano (ej: última venta) que reemplaza al de mercado.
 */
data class Entry(val id: Int, val foil: Boolean, val qty: Int, val manual: Double? = null) {
    /** Precio por copia que se usa en totales: el manual si existe, si no el de TCGplayer. */
    fun unit(card: CardInfo?): Double? = manual ?: card?.price(foil)
}

object Collection {
    private fun file(ctx: Context) = File(ctx.filesDir, "collection.json")

    fun load(ctx: Context): List<Entry> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val manual = if (o.has("manual") && !o.isNull("manual")) o.getDouble("manual") else null
            Entry(o.getInt("id"), o.optBoolean("foil"), o.optInt("qty", 1), manual)
        }
    }

    fun save(ctx: Context, entries: List<Entry>) {
        val arr = JSONArray()
        entries.forEach {
            val o = JSONObject().put("id", it.id).put("foil", it.foil).put("qty", it.qty)
            it.manual?.let { m -> o.put("manual", m) }
            arr.put(o)
        }
        val tmp = File(ctx.filesDir, "collection.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file(ctx))
    }

    /** Suma copias si ya existe la misma impresión y foil; si no, la agrega al final. Un [manual] nuevo reemplaza al anterior. */
    fun add(entries: List<Entry>, id: Int, foil: Boolean, qty: Int, manual: Double? = null): List<Entry> {
        val i = entries.indexOfFirst { it.id == id && it.foil == foil }
        if (i < 0) return entries + Entry(id, foil, qty, manual)
        return entries.toMutableList().also { it[i] = it[i].copy(qty = it[i].qty + qty, manual = manual ?: it[i].manual) }
    }

    /** Mismo formato que coleccion.csv del script (version = productId de TCGplayer). */
    fun toCsv(entries: List<Entry>): String = buildString {
        append("codigo,cantidad,foil,version,nombre,precio_usd,precio_manual_usd\n")
        for (e in entries) {
            val c = CardIndex.byId(e.id)
            val name = c?.name.orEmpty().replace("\"", "\"\"")
            val price = c?.price(e.foil)?.toString().orEmpty()
            append("${c?.code?.substringBefore('/').orEmpty()},${e.qty},${if (e.foil) "si" else "no"},${e.id},\"$name\",$price,${e.manual?.toString().orEmpty()}\n")
        }
    }

    /**
     * Lee codigo,cantidad,foil[,version]. Si el encabezado trae precio_manual_usd (exportado por la app),
     * ese precio es la última columna. Devuelve las líneas reconocidas y las que no.
     */
    fun fromCsv(text: String): Pair<List<Entry>, List<String>> {
        val ok = mutableListOf<Entry>()
        val failed = mutableListOf<String>()
        var hasManual = false
        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("﻿")
            if (line.lowercase().startsWith("codigo")) {
                hasManual = "precio_manual_usd" in line.lowercase()
                continue
            }
            if (line.isEmpty() || line.startsWith("#")) continue
            val cols = line.split(',', ';').map { it.trim().trim('"') }
            val code = cols[0]
            val qty = cols.getOrNull(1)?.toIntOrNull() ?: 1
            val foil = cols.getOrNull(2).orEmpty().lowercase() in setOf("si", "sí", "s", "yes", "y", "1", "x", "foil")
            val version = cols.getOrNull(3).orEmpty()
            val cands = CardIndex.lookup(code)
            val card = when {
                version.toIntOrNull() != null -> CardIndex.byId(version.toInt())
                version.isNotEmpty() -> cands.firstOrNull { version.lowercase() in it.name.lowercase() }
                else -> cands.firstOrNull()
            }
            // Última columna: el nombre va entre comillas y puede traer comas que corren los índices
            val manual = if (hasManual && cols.size > 6) cols.last().toDoubleOrNull()?.takeIf { it > 0 } else null
            if (card == null) failed += code else ok += Entry(card.id, foil, qty, manual)
        }
        return ok to failed
    }
}
