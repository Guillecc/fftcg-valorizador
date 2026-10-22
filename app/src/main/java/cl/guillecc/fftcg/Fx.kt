package cl.guillecc.fftcg

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.time.LocalDate

/** Tipo de cambio CLP por USD: dólar observado del día (mindicador.cl) o uno fijo elegido por el usuario. */
object Fx {
    private const val URL = "https://mindicador.cl/api/dolar"
    private const val FALLBACK = 950.0
    private val RANGE = 300.0..3000.0 // fuera de esto, el valor de la API no es creíble

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("fx", Context.MODE_PRIVATE)

    fun manual(ctx: Context): Double? = prefs(ctx).getFloat("manual", 0f).takeIf { it > 0f }?.toDouble()

    /** Último dólar observado guardado (si es creíble). */
    private fun auto(ctx: Context): Double? =
        prefs(ctx).getFloat("auto", 0f).toDouble().takeIf { it.isFinite() && it in RANGE }

    fun current(ctx: Context): Double = manual(ctx) ?: auto(ctx) ?: FALLBACK

    /** De dónde sale [current], para mostrarlo junto al valor: "fijo", "dólar observado", "dólar del 20-10, sin conexión"… */
    fun source(ctx: Context): String {
        if (manual(ctx) != null) return "fijo"
        if (auto(ctx) == null) return "valor por defecto, sin conexión"
        val date = prefs(ctx).getString("autoDate", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        return when {
            date == null -> "dólar observado anterior, sin conexión"
            date == LocalDate.now() -> "dólar observado"
            else -> "dólar del %02d-%02d, sin conexión".format(date.dayOfMonth, date.monthValue)
        }
    }

    fun setManual(ctx: Context, value: Double?) {
        prefs(ctx).edit().putFloat("manual", value?.toFloat() ?: 0f).apply()
    }

    /** Descarga el dólar observado una vez al día. Llamar desde un hilo de IO. */
    fun refreshIfStale(ctx: Context) {
        val today = LocalDate.now().toString()
        if (prefs(ctx).getString("autoDate", null) == today && auto(ctx) != null) return
        val serie = JSONObject(Net.getText(URL)).getJSONArray("serie")
        val value = serie.getJSONObject(0).getDouble("valor")
        if (!value.isFinite() || value !in RANGE) throw IOException("dólar fuera de rango")
        prefs(ctx).edit().putFloat("auto", value.toFloat()).putString("autoDate", today).apply()
    }
}
