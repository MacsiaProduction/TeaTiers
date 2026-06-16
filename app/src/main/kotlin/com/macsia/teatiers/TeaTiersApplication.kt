package com.macsia.teatiers

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent

/**
 * Routes Coil's process-wide singleton (used by every `AsyncImage`) through the Hilt-configured
 * [ImageLoader] so user photos and the M3 catalog reference images share one loader: the Retrofit
 * OkHttp client (one connection pool) and the crossfade policy. Pulled via an entry point because
 * the [Application] itself is the Hilt component owner and cannot be member-injected.
 */
@HiltAndroidApp
class TeaTiersApplication : Application(), SingletonImageLoader.Factory {

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        EntryPointAccessors.fromApplication(this, ImageLoaderEntryPoint::class.java).imageLoader()

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ImageLoaderEntryPoint {
        fun imageLoader(): ImageLoader
    }
}
