package cl.guillecc.fftcg

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import okhttp3.OkHttpClient

class FftcgApp : Application(), ImageLoaderFactory {
    // Imágenes del CDN de TCGplayer, pedidas con un User-Agent propio
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient {
                OkHttpClient.Builder()
                    .addInterceptor { chain ->
                        chain.proceed(chain.request().newBuilder().header("User-Agent", Net.USER_AGENT).build())
                    }
                    .build()
            }
            .crossfade(true)
            .build()
}
