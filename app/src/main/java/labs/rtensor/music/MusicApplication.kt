package labs.rtensor.music

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

/**
 * One shared image loader with a generous memory cache and a dedicated disk cache, so covers
 * seen once come back instantly when scrolling back or reopening the app, even offline.
 */
class MusicApplication : Application(), ImageLoaderFactory {
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base)
        if (getProcessName() == "${base.packageName}:apple_web") android.webkit.WebView.setDataDirectorySuffix("apple-account")
        if (getProcessName() == "${base.packageName}:spotify_metadata") android.webkit.WebView.setDataDirectorySuffix("spotify-catalogue")
    }
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("artwork")).maxSizeBytes(256L * 1024 * 1024).build() }
        // YouTube's image CDN sends short cache lifetimes; covers for a given URL never change.
        .respectCacheHeaders(false)
        .crossfade(true)
        .build()
}
