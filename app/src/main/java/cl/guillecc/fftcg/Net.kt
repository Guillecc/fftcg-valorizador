package cl.guillecc.fftcg

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Net {
    const val USER_AGENT = "FFTCGValor/1.0"

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
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
