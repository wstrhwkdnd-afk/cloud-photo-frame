package com.cloudframe.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

class FrameApp : Application(), ImageLoaderFactory {
    val prefs by lazy { Prefs(this) }
    val credentials by lazy { CredentialStore(this) }
    val api by lazy { DegooApi(prefs, credentials) }
    val library by lazy { PhotoLibrary(this, api, prefs) }

    // A large disk cache lets the screensaver keep showing recent photos when offline
    override fun newImageLoader() = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.15).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("photos"))
                .maxSizeBytes(800L * 1024 * 1024)
                .build()
        }
        .crossfade(false)
        .build()
}
