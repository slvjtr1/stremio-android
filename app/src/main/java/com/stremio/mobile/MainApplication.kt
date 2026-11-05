package com.stremio.mobile

import android.app.Application
import android.content.Context
import com.stremio.mobile.di.AppContainer
import coil3.SingletonImageLoader
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import okhttp3.OkHttpClient
import okhttp3.Dns
import java.net.InetAddress
import okio.Path.Companion.toPath

import timber.log.Timber
import com.posthog.android.PostHogAndroid
import com.posthog.android.PostHogAndroidConfig

class MainApplication : Application(), SingletonImageLoader.Factory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        try {
            android.system.Os.setenv("TMPDIR", cacheDir.absolutePath, true)
        } catch (e: Exception) {
            Timber.e(e, "Failed to set TMPDIR environment variable")
        }
        container = AppContainer(this)
        val analyticsEnabled = container.authRepository.isAnalyticsEnabled()

        if (BuildConfig.POSTHOG_API_KEY.isNotEmpty()) {
            val posthogConfig = PostHogAndroidConfig(
                apiKey = BuildConfig.POSTHOG_API_KEY,
                host = BuildConfig.POSTHOG_HOST
            ).apply {
                captureApplicationLifecycleEvents = true
                captureScreenViews = true
                sessionReplay = false
                sessionReplayConfig.maskAllTextInputs = true
                sessionReplayConfig.maskAllImages = true
                optOut = !analyticsEnabled
            }
            PostHogAndroid.setup(this, posthogConfig)
        }

    }

    override fun newImageLoader(context: Context): ImageLoader {
        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache").absolutePath.toPath())
                    .maxSizeBytes(1024L * 1024L * 250L) // 250 MB
                    .build()
            }
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = {
                            OkHttpClient.Builder()
                                .dns(object : Dns {
                                    override fun lookup(hostname: String): List<InetAddress> {
                                        return Dns.SYSTEM.lookup(hostname).sortedBy { it is java.net.Inet6Address }
                                    }
                                })
                                .build()
                        }
                    )
                )
            }
            .build()
    }
}
