package cl.guillecc.fftcg

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Una línea de la colección: impresión de TCGplayer (productId), foil y copias. */
data class Entry(val id: Int, val foil: Boolean, val qty: Int)

object Collection {
    private fun file(ctx: Context) = File(ctx.filesDir, "collection.json")

    fun load(ctx: Context): List<Entry> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        val arr = JSONArray(f.readText())
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Entry(o.getInt("id"), o.optBoolean("foil"), o.optInt("qty", 1))
        }
    }

    fun save(ctx: Context, entries: List<Entry>) {
        val arr = JSONArray()
        entries.forEach { arr.put(JSONObject().put("id", it.id).put("foil", it.foil).put("qty", it.qty)) }
        val tmp = File(ctx.filesDir, "collection.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file(ctx))
    }

    /** Suma copias si ya existe la misma impresión y foil; si no, la agrega al final. */
    fun add(entries: List<Entry>, id: Int, foil: Boolean, qty: Int): List<Entry> {
        val i = entries.indexOfFirst { it.id == id && it.foil == foil }
        if (i < 0) return entries + Entry(id, foil, qty)
        return entries.toMutableList().also { it[i] = it[i].copy(qty = it[i].qty + qty) }
    }

    /** Mismo formato que coleccion.csv del script (version = productId de TCGplayer). */
    fun toCsv(entries: List<Entry>): String = buildString {
        append("codigo,cantidad,foil,version,nombre,precio_usd\n")
        for (e in entries) {
            val c = CardIndex.byId(e.id)
            val name = c?.name.orEmpty().replace("\"", "\"\"")
            val price = c?.price(e.foil)?.toString().orEmpty()
            append("${c?.code?.substringBefore('/').orEmpty()},${e.qty},${if (e.foil) "si" else "no"},${e.id},\"$name\",$price\n")
        }
    }

    /** Lee codigo,cantidad,foil[,version]. Devuelve las líneas reconocidas y las que no. */
    fun fromCsv(text: String): Pair<List<Entry>, List<String>> {
        val ok = mutableListOf<Entry>()
        val failed = mutableListOf<String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim().removePrefix("﻿")
            if (line.isEmpty() || line.startsWith("#") || line.lowercase().startsWith("codigo")) continue
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
            if (card == null) failed += code else ok += Entry(card.id, foil, qty)
        }
        return ok to failed
    }
}
