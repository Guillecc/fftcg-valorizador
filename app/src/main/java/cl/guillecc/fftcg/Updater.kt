package cl.guillecc.fftcg

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Versión publicada más nueva que la instalada: "1.0.N", N = versionCode, y su APK.
 * [sha256] viene del campo `digest` de la API de GitHub ("sha256:<hex>"); vacío si no lo trae.
 */
data class Update(val version: String, val code: Int, val url: String, val size: Long, val sha256: String)

/** El APK descargado no es confiable: no se instala. */
class VerificationException(detail: String) : IOException(detail)

/**
 * Actualización dentro de la app: cada push a main publica un release "v1.0.<run>" con FFTCG-Valor.apk,
 * firmado con la misma llave, así que se instala encima conservando la colección.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/Guillecc/fftcg-valorizador/releases/latest"
    private const val DL_PREFIX = "https://github.com/Guillecc/fftcg-valorizador/releases/download/"
    private const val ASSET = "FFTCG-Valor.apk"
    private const val MAX_APK = 150L * 1024 * 1024
    private val TAG = Regex("^v?1\\.0\\.(\\d+)$")
    private val SHA = Regex("^[0-9a-f]{64}$")

    /** Mensaje para el usuario cuando falla cualquier verificación. */
    const val NOT_VERIFIED = "La actualización no pasó la verificación de seguridad"

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
            conn.inputStream.use { Net.readLimited(it, Net.MAX_TEXT) }
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
                // Se valida al descargar, para poder decirle al usuario por qué no se instala
                val sha = a.optString("digest").trim().lowercase().removePrefix("sha256:")
                return Update("1.0.$code", code, a.optString("browser_download_url"), a.optLong("size"), sha)
            }
        }
        return null
    }

    /**
     * Descarga el APK a cache/update (vía .tmp) y lo verifica: URL del repo, tamaño, SHA-256 publicado,
     * paquete, versión y firma. Si algo no calza, borra el archivo y lanza [VerificationException].
     * [onProgress] llega desde el hilo de IO.
     */
    fun download(ctx: Context, update: Update, onProgress: (Float) -> Unit): File {
        if (!update.url.startsWith(DL_PREFIX)) throw VerificationException("URL de descarga desconocida")
        if (update.size !in 1..MAX_APK) throw VerificationException("tamaño inválido")
        if (update.sha256.isEmpty()) throw VerificationException("el release no publica el SHA-256")
        if (!SHA.matches(update.sha256)) throw VerificationException("SHA-256 publicado inválido")
        val dir = dir(ctx).apply { mkdirs() }
        val tmp = File(dir, "$ASSET.tmp")
        val out = File(dir, ASSET)
        out.delete()
        val md = MessageDigest.getInstance("SHA-256")
        try {
            // GitHub redirige a su CDN (https -> https): HttpURLConnection lo sigue solo
            val conn = Net.open(update.url)
            try {
                if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
                val total = update.size
                var done = 0L
                var lastPct = -1
                conn.inputStream.use { input ->
                    tmp.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            done += n
                            if (done > total) throw VerificationException("más grande de lo anunciado")
                            output.write(buf, 0, n)
                            md.update(buf, 0, n)
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            if (tmp.length() != update.size) throw IOException("descarga incompleta")
            if (hex(md.digest()) != update.sha256) throw VerificationException("el SHA-256 no coincide")
            verifyApk(ctx, tmp, update.code.toLong())
            if (!tmp.renameTo(out)) throw IOException("no se pudo guardar el archivo")
            return out
        } catch (e: Exception) {
            tmp.delete()
            out.delete()
            throw e
        }
    }

    /**
     * El APK debe ser de esta misma app, con un versionCode mayor al instalado (y el anunciado, si se conoce)
     * y firmado con el mismo certificado. Lanza [VerificationException] si no.
     */
    fun verifyApk(ctx: Context, apk: File, expectedCode: Long? = null) {
        val pm = ctx.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: throw VerificationException("APK inválido")
        if (archive.packageName != ctx.packageName) throw VerificationException("paquete distinto")
        val code = PackageInfoCompat.getLongVersionCode(archive)
        if (code <= installedCode(ctx)) throw VerificationException("versión no es más nueva")
        if (expectedCode != null && code != expectedCode) throw VerificationException("versión distinta a la anunciada")
        val mine = signers(pm.getPackageInfo(ctx.packageName, flags))
        val theirs = signers(archive)
        if (mine.isEmpty() || theirs != mine) throw VerificationException("firma distinta")
    }

    private fun signers(pi: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            pi.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pi.signatures
        }
        return sigs.orEmpty().map { s -> hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray())) }.toSet()
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    /**
     * Abre el instalador del sistema. Devuelve false si falta el permiso de instalar apps desconocidas:
     * en ese caso abre los ajustes para que el usuario lo dé y vuelva a intentar.
     * El archivo debe venir de [download] (verificado); si se reusa una descarga, llamar antes a [verifyApk].
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
