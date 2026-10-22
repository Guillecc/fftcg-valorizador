package cl.guillecc.fftcg

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Versión publicada más nueva que la instalada: "1.0.N", N = versionCode, y su APK. */
data class Update(val version: String, val code: Int, val url: String, val size: Long)

/**
 * Actualización dentro de la app: cada push a main publica un release "v1.0.<run>" con FFTCG-Valor.apk,
 * firmado con la misma llave, así que se instala encima conservando la colección.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/Guillecc/fftcg-valorizador/releases/latest"
    private const val ASSET = "FFTCG-Valor.apk"
    private val TAG = Regex("^v?1\\.0\\.(\\d+)$")

    private fun dir(ctx: Context) = File(ctx.cacheDir, "update")

    private fun info(ctx: Context) = ctx.packageManager.getPackageInfo(ctx.packageName, 0)

    fun installedCode(ctx: Context): Long = PackageInfoCompat.getLongVersionCode(info(ctx))

    fun installedVersion(ctx: Context): String = info(ctx).versionName ?: "?"

    /** Borra descargas anteriores (ya instaladas o a medias). */
    fun clean(ctx: Context) {
        dir(ctx).deleteRecursively()
    }

    /** Último release (/latest ignora el prerelease "index"); null si no hay nada más nuevo. Llamar desde un hilo de IO. */
    fun check(ctx: Context): Update? {
        val conn = Net.open(LATEST)
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        val text = try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        val json = JSONObject(text)
        val code = TAG.matchEntire(json.optString("tag_name"))?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (code <= installedCode(ctx)) return null
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name") == ASSET) {
                return Update("1.0.$code", code, a.getString("browser_download_url"), a.optLong("size"))
            }
        }
        return null
    }

    /** Descarga el APK a cache/update (vía .tmp) y verifica el tamaño. [onProgress] llega desde el hilo de IO. */
    fun download(ctx: Context, update: Update, onProgress: (Float) -> Unit): File {
        val dir = dir(ctx).apply { mkdirs() }
        val tmp = File(dir, "$ASSET.tmp")
        val out = File(dir, ASSET)
        // GitHub redirige a su CDN (https -> https): HttpURLConnection lo sigue solo
        val conn = Net.open(update.url)
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            val total = if (update.size > 0) update.size else conn.contentLengthLong
            var done = 0L
            var lastPct = -1
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        if (update.size > 0 && tmp.length() != update.size) {
            tmp.delete()
            throw IOException("descarga incompleta")
        }
        out.delete()
        if (!tmp.renameTo(out)) throw IOException("no se pudo guardar el archivo")
        return out
    }

    /**
     * Abre el instalador del sistema. Devuelve false si falta el permiso de instalar apps desconocidas:
     * en ese caso abre los ajustes para que el usuario lo dé y vuelva a intentar.
     */
    fun install(ctx: Context, file: File): Boolean {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}
