package cl.guillecc.fftcg

import android.content.Context
import org.json.JSONObject
import java.time.LocalDate

/** Tipo de cambio CLP por USD: dólar observado del día (mindicador.cl) o uno fijo elegido por el usuario. */
object Fx {
    private const val URL = "https://mindicador.cl/api/dolar"
    private const val FALLBACK = 950.0

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("fx", Context.MODE_PRIVATE)

    fun manual(ctx: Context): Double? = prefs(ctx).getFloat("manual", 0f).takeIf { it > 0f }?.toDouble()

    fun current(ctx: Context): Double =
        manual(ctx) ?: prefs(ctx).getFloat("auto", 0f).takeIf { it > 0f }?.toDouble() ?: FALLBACK

    fun setManual(ctx: Context, value: Double?) {
        prefs(ctx).edit().putFloat("manual", value?.toFloat() ?: 0f).apply()
    }

    /** Descarga el dólar observado una vez al día. Llamar desde un hilo de IO. */
    fun refreshIfStale(ctx: Context) {
        val today = LocalDate.now().toString()
        if (prefs(ctx).getString("autoDate", null) == today) return
        val serie = JSONObject(Net.getText(URL)).getJSONArray("serie")
        val value = serie.getJSONObject(0).getDouble("valor")
        prefs(ctx).edit().putFloat("auto", value.toFloat()).putString("autoDate", today).apply()
    }
}
