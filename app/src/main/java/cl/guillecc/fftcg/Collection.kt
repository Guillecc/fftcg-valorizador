package cl.guillecc.fftcg

import android.content.Context
import androidx.core.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicLong

/**
 * Una línea de la colección: impresión de TCGplayer (productId), foil y copias.
 * [manual] es un precio en USD por copia anotado a mano (ej: última venta) que reemplaza al de mercado.
 */
data class Entry(val id: Int, val foil: Boolean, val qty: Int, val manual: Double? = null) {
    /** Precio por copia que se usa en totales: el manual si existe, si no el de TCGplayer. */
    fun unit(card: CardInfo?): Double? = manual ?: card?.price(foil)
}

/** Resultado de leer la colección: [message] avisa al usuario si el archivo no se pudo leer. */
class Loaded(val entries: List<Entry>, val message: String? = null)

/** Respaldo diario local: fecha, archivo y total de copias. */
class Backup(val date: LocalDate, val file: File, val cards: Int)

/** Resultado de leer un CSV: líneas reconocidas y códigos sin match. */
class CsvResult(val entries: List<Entry>, val failed: List<String>)

object Collection {
    const val MAX_QTY = 9999
    private const val MAX_PRICE = 100_000.0
    private const val KEEP_BACKUPS = 7
    private const val MAX_CSV_LINES = 5000
    private val BACKUP_NAME = Regex("^collection-(\\d{4}-\\d{2}-\\d{2})\\.json$")

    private fun file(ctx: Context) = File(ctx.filesDir, "collection.json")
    private fun backupDir(ctx: Context) = File(ctx.filesDir, "backups")

    // Un solo escritor, y nunca una foto más vieja encima de una más nueva
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val seq = AtomicLong()
    private var savedSeq = 0L // protegido por lock

    /** Si el archivo dañado no se pudo apartar, no se guarda nada para no pisarlo. */
    @Volatile private var blocked = false

    /** Lee la colección. Si el archivo existe pero está dañado, lo aparta con otro nombre y parte vacía. Llamar desde IO. */
    fun load(ctx: Context): Loaded {
        val f = file(ctx)
        val bytes = try {
            AtomicFile(f).readFully()
        } catch (e: FileNotFoundException) {
            return Loaded(emptyList())
        } catch (e: IOException) {
            // No se puede leer (no es que esté dañado): no tocar nada y no guardar encima
            blocked = true
            return Loaded(emptyList(), "No se pudo leer la colección (${e.message}); no se guardarán cambios hasta reiniciar la app")
        }
        return try {
            Loaded(parse(String(bytes, Charsets.UTF_8)))
        } catch (e: Exception) {
            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val bad = File(ctx.filesDir, "collection.bad-$stamp.json")
            val moved = f.renameTo(bad) || (runCatching { f.copyTo(bad, overwrite = true) }.isSuccess && f.delete())
            if (!moved) {
                blocked = true
                Loaded(emptyList(), "No se pudo leer la colección; no se guardarán cambios para no perderla")
            } else {
                val hint = if (backups(ctx).isNotEmpty()) ". Puedes recuperarla en ⋮ → Restaurar respaldo" else ""
                Loaded(emptyList(), "No se pudo leer la colección; se guardó una copia en ${bad.name}$hint")
            }
        }
    }

    private fun parse(text: String): List<Entry> {
        val arr = JSONArray(text)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            val manual = if (o.has("manual") && !o.isNull("manual")) validPrice(o.getDouble("manual")) else null
            Entry(o.getInt("id"), o.optBoolean("foil"), o.optInt("qty", 1).coerceIn(1, MAX_QTY), manual)
        }
    }

    private fun toJson(entries: List<Entry>): String {
        val arr = JSONArray()
        entries.forEach {
            val o = JSONObject().put("id", it.id).put("foil", it.foil).put("qty", it.qty)
            it.manual?.let { m -> o.put("manual", m) }
            arr.put(o)
        }
        return arr.toString()
    }

    /**
     * Guarda una foto de la colección en segundo plano. Las escrituras van de a una y una foto más vieja
     * nunca reemplaza a una más nueva. [onError] llega desde un hilo de IO.
     */
    fun saveAsync(ctx: Context, entries: List<Entry>, onError: (Throwable) -> Unit = {}) {
        val app = ctx.applicationContext
        val snapshot = entries.toList()
        val n = seq.incrementAndGet()
        writer.launch {
            lock.withLock {
                if (n <= savedSeq) return@withLock
                try {
                    save(app, snapshot)
                    savedSeq = n
                } catch (e: Exception) {
                    onError(e)
                }
            }
        }
    }

    /** Escritura atómica (AtomicFile: archivo nuevo + fsync + rename) y respaldo del día si falta. */
    private fun save(ctx: Context, entries: List<Entry>) {
        if (blocked) throw IOException("la colección original no se pudo leer")
        val bytes = toJson(entries).toByteArray(Charsets.UTF_8)
        write(file(ctx), bytes)
        runCatching { backupIfNeeded(ctx, bytes) } // un respaldo fallido no debe impedir guardar
    }

    private fun write(f: File, bytes: ByteArray) {
        val af = AtomicFile(f)
        val out = af.startWrite()
        try {
            out.write(bytes)
            af.finishWrite(out)
        } catch (e: Exception) {
            af.failWrite(out)
            throw e
        }
    }

    private fun backupIfNeeded(ctx: Context, bytes: ByteArray) {
        val dir = backupDir(ctx).apply { mkdirs() }
        val today = File(dir, "collection-${LocalDate.now()}.json")
        if (!today.exists()) write(today, bytes)
        // Conserva los 7 más nuevos
        dir.listFiles().orEmpty()
            .filter { BACKUP_NAME.matches(it.name) }
            .sortedByDescending { it.name }
            .drop(KEEP_BACKUPS)
            .forEach { it.delete() }
    }

    /** Respaldos diarios, del más nuevo al más viejo (se omiten los que no se pueden leer). Llamar desde IO. */
    fun backups(ctx: Context): List<Backup> =
        backupDir(ctx).listFiles().orEmpty().mapNotNull { f ->
            val date = BACKUP_NAME.matchEntire(f.name)?.groupValues?.get(1)
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@mapNotNull null
            val entries = runCatching { readBackup(f) }.getOrNull() ?: return@mapNotNull null
            Backup(date, f, entries.sumOf { it.qty })
        }.sortedByDescending { it.date }

    /** Contenido de un respaldo. Llamar desde IO. */
    fun readBackup(f: File): List<Entry> = parse(String(AtomicFile(f).readFully(), Charsets.UTF_8))

    /** Suma copias si ya existe la misma impresión y foil; si no, la agrega al final. Un [manual] nuevo reemplaza al anterior. */
    fun add(entries: List<Entry>, id: Int, foil: Boolean, qty: Int, manual: Double? = null): List<Entry> {
        val i = entries.indexOfFirst { it.id == id && it.foil == foil }
        if (i < 0) return entries + Entry(id, foil, qty.coerceIn(1, MAX_QTY), manual)
        return entries.toMutableList().also {
            val sum = (it[i].qty.toLong() + qty).coerceIn(1L, MAX_QTY.toLong()).toInt()
            it[i] = it[i].copy(qty = sum, manual = manual ?: it[i].manual)
        }
    }

    // ------------------------------------------------------------------ CSV

    /** Mismo formato que coleccion.csv del script (version = productId de TCGplayer), separado por comas. */
    fun toCsv(entries: List<Entry>): String = buildString {
        append("codigo,cantidad,foil,version,nombre,precio_usd,precio_manual_usd\n")
        for (e in entries) {
            val c = CardIndex.byId(e.id)
            val cols = listOf(
                c?.code?.substringBefore('/').orEmpty(),
                e.qty.toString(),
                if (e.foil) "si" else "no",
                e.id.toString(),
                c?.name.orEmpty(),
                c?.price(e.foil)?.toString().orEmpty(),
                e.manual?.toString().orEmpty(),
            )
            append(cols.joinToString(",") { csvField(it) }).append('\n')
        }
    }

    private fun csvField(s: String): String =
        if (s.any { it == ',' || it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\""
        else s

    /**
     * Lee codigo,cantidad,foil[,version][,…,precio_manual_usd]. Separador ',' o ';' (detectado en la primera
     * línea), campos entre comillas y, si hay encabezado, columnas por nombre. Llamar desde IO (usa el índice).
     */
    fun fromCsv(text: String): CsvResult {
        val clean = text.removePrefix("﻿")
        val firstLine = clean.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val delim = if (countOutsideQuotes(firstLine, ';') > countOutsideQuotes(firstLine, ',')) ';' else ','
        val rows = records(clean, delim)
        val ok = mutableListOf<Entry>()
        val failed = mutableListOf<String>()
        // Columnas por defecto (coleccion.csv sin encabezado): codigo,cantidad,foil,version
        var iCode = 0
        var iQty = 1
        var iFoil = 2
        var iVersion = 3
        var iManual = -1
        var lineNo = 0
        for (cols in rows) {
            lineNo++
            val first = cols.firstOrNull()?.trim().orEmpty()
            if (cols.all { it.isBlank() } || first.startsWith("#")) continue
            val lower = first.lowercase()
            if (lower == "codigo" || lower == "código") {
                val names = cols.map { it.trim().lowercase() }
                fun idx(vararg n: String) = names.indexOfFirst { it in n }
                iCode = idx("codigo", "código")
                iQty = idx("cantidad")
                iFoil = idx("foil")
                iVersion = idx("version", "versión")
                iManual = idx("precio_manual_usd")
                continue
            }
            fun col(i: Int) = if (i < 0) "" else cols.getOrNull(i)?.trim().orEmpty()
            val code = col(iCode)
            val label = code.ifEmpty { "línea $lineNo" }
            val qtyText = col(iQty)
            val qty = if (qtyText.isEmpty()) 1 else parseQty(qtyText)
            if (qty == null) { failed += label; continue }
            val foil = col(iFoil).lowercase() in setOf("si", "sí", "s", "yes", "y", "1", "x", "foil", "true")
            val version = col(iVersion)
            val cands = CardIndex.lookup(code)
            val card = when {
                version.toIntOrNull() != null -> CardIndex.byId(version.toInt())
                version.isNotEmpty() -> cands.firstOrNull { version.lowercase() in it.name.lowercase() }
                else -> cands.firstOrNull()
            }
            val manual = parsePrice(col(iManual))
            if (card == null) failed += label else ok += Entry(card.id, foil, qty, manual)
        }
        return CsvResult(ok, failed)
    }

    /** Cantidad entera (acepta "2.0" de Excel), limitada a 1..9999; null si no es un número. */
    private fun parseQty(s: String): Int? {
        val n = s.toLongOrNull() ?: s.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }?.toLong() ?: return null
        return n.coerceIn(1L, MAX_QTY.toLong()).toInt()
    }

    /** Precio en USD: acepta "12.5", "12,5" (coma decimal de Excel en Chile), "1.234,5" y "US$ 12"; null si no es válido. */
    fun parsePrice(raw: String): Double? {
        var s = raw.trim().removePrefix("US$").removePrefix("$").trim().replace(" ", "")
        if (s.isEmpty()) return null
        val comma = s.lastIndexOf(',')
        val dot = s.lastIndexOf('.')
        s = when {
            comma >= 0 && dot >= 0 -> if (comma > dot) s.replace(".", "").replace(',', '.') else s.replace(",", "")
            else -> s.replace(',', '.')
        }
        return validPrice(s.toDoubleOrNull())
    }

    private fun validPrice(p: Double?): Double? = p?.takeIf { it.isFinite() && it > 0 && it <= MAX_PRICE }

    private fun countOutsideQuotes(line: String, ch: Char): Int {
        var quoted = false
        var n = 0
        for (c in line) {
            if (c == '"') quoted = !quoted else if (c == ch && !quoted) n++
        }
        return n
    }

    /** CSV RFC 4180: comillas dobles, "" como comilla escapada, saltos de línea dentro de comillas. */
    private fun records(text: String, delim: Char): List<List<String>> {
        val out = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length && out.size < MAX_CSV_LINES) {
            val ch = text[i]
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        field.append('"')
                        i++
                    } else {
                        quoted = false
                    }
                } else {
                    field.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> quoted = true
                    delim -> { row.add(field.toString()); field.setLength(0) }
                    '\r' -> {}
                    '\n' -> {
                        row.add(field.toString()); field.setLength(0)
                        out.add(row); row = mutableListOf()
                    }
                    else -> field.append(ch)
                }
            }
            i++
        }
        if (out.size < MAX_CSV_LINES && (field.isNotEmpty() || row.isNotEmpty())) {
            row.add(field.toString())
            out.add(row)
        }
        return out
    }
}
