package cl.guillecc.fftcg

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

object Net {
    const val USER_AGENT = "FFTCGValor/1.0"
    const val MAX_TEXT = 1L shl 20 // respuestas JSON chicas (dólar, API de GitHub)

    fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", USER_AGENT)
            connectTimeout = 15_000
            readTimeout = 30_000
        }

    fun getText(url: String): String {
        val conn = open(url)
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { readLimited(it, MAX_TEXT) }
        } finally {
            conn.disconnect()
        }
    }

    /** Copia como máximo [max] bytes; si hay más, lanza IOException. Devuelve los bytes copiados. */
    fun copyLimited(input: InputStream, out: OutputStream, max: Long): Long {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) throw IOException("archivo demasiado grande")
            out.write(buf, 0, n)
        }
        return total
    }

    /** Lee todo el stream como UTF-8, con un tope de [max] bytes. */
    fun readLimited(input: InputStream, max: Long): String {
        val out = ByteArrayOutputStream()
        copyLimited(input, out, max)
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}
